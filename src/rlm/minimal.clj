(ns rlm.minimal
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.io PrintWriter StringWriter]))

(def reserved-names
  '#{context
     history
     llm-query
     rlm-query
     final!
     show-vars})

(defn- canonicalize-tool-key [tool-key]
  (symbol (name tool-key)))

(defn- validate-tools! [tool-map]
  (let [entries (map (fn [[tool f]] [(canonicalize-tool-key tool) f]) tool-map)
        canonical-keys (map first entries)
        tool-forbidden-names (into reserved-names (keys (ns-publics 'clojure.core)))
        reserved-conflicts (->> canonical-keys
                               (filter tool-forbidden-names)
                               set)
        duplicates (->> canonical-keys
                        frequencies
                        (filter #(> (val %) 1))
                        (map key)
                        set)]
    (when (seq reserved-conflicts)
      (throw (ex-info "Custom tools cannot override reserved names."
                      {:conflicts (sort reserved-conflicts)})))
    (when (seq duplicates)
      (throw (ex-info "Custom tools must use unique names."
                      {:duplicates (sort duplicates)})))
    (into {} entries)))

(defn- printable [x]
  (cond
    (string? x) x
    (nil? x) "nil"
     :else (pr-str x)))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- deep-stringify-keys [x]
  (walk/postwalk
   (fn [node]
     (if (map? node)
       (into {}
             (map (fn [[k v]]
                    [(cond
                       (keyword? k) (name k)
                       (symbol? k) (name k)
                       :else k)
                     v]))
             node)
       node))
   x))

(defn- summarize-result [code {:keys [value stdout stderr error ms final]}]
  (let [result-text (if error
                      error
                      (str/trimr (with-out-str (pprint/pprint value))))]
    (str/join
     "\n"
     (remove nil?
             ["Executed code:"
              "```clj"
              code
              "```"
              "Result:"
              result-text
              (when (seq stdout)
                (str "Stdout:\n" stdout))
              (when (seq stderr)
                (str "Stderr:\n" stderr))
              (when (some? final)
                (str "Final:\n" (printable final)))
              (str "Execution time (ms): " ms)]))))

(defn- extract-code-blocks [s]
  (if (string? s)
    (map second
         (re-seq #"(?s)```(?:clj|clojure)\s*\n(.*?)```" s))
    []))

(declare completion close-session!)

(defn- user-publics [{:keys [ns state]}]
  (let [tool-names (set (keys (:tools @state)))]
    (->> (ns-publics ns)
         keys
         (remove reserved-names)
         (remove tool-names)
         (remove #(str/starts-with? (name %) "-"))
         sort
         vec)))

(defn- append-history!
  [{:keys [ns state] :as _session} entries]
  (let [history (:history (swap! state update :history into entries))]
    (alter-var-root (ns-resolve ns 'history) (constantly history))
    history))

(defn start-session
  [context opts]
  (let [opts (merge {:max-depth 1
                     :depth 0
                     :max-iterations 8
                      :system-prompt
                     (str
                      "You are inside a Clojure REPL-backed agent.\n"
                      "The input is available as `context`.\n"
                      "Use fenced ```clj blocks when you want code executed.\n"
                      "Call `(final! x)` when done.\n")
                     :tools {}}
                    opts)
        tools (validate-tools! (:tools opts))
        id (str (random-uuid))
        ns-sym (symbol (str "rlm.session." id))
        state (atom {:id id
                     :ns ns-sym
                     :context context
                     :history []
                    :final nil
                    :final-set? false
                    :depth (:depth opts)
                    :max-depth (:max-depth opts)
                    :max-iterations (:max-iterations opts)
                    :llm-fn (:llm-fn opts)
                    :model (:model opts)
                    :system-prompt (:system-prompt opts)
                    :tools tools})]
    (create-ns ns-sym)
    (binding [*ns* (the-ns ns-sym)]
      (refer 'clojure.core))
    (intern ns-sym 'context context)
    (intern ns-sym 'history (:history @state))
    (intern ns-sym 'final!
            (fn [x]
              (swap! state assoc :final x :final-set? true)
              x))
    (intern ns-sym 'show-vars
            (fn []
              (user-publics {:ns ns-sym
                             :state state})))
    (intern ns-sym 'llm-query
            (fn
              ([prompt] ((var-get (ns-resolve ns-sym 'llm-query)) prompt nil))
              ([prompt model]
               (let [{:keys [llm-fn depth]} @state]
                 (when-not llm-fn
                   (throw (ex-info "No :llm-fn supplied." {})))
                 (printable
                  (llm-fn {:prompt (printable prompt)
                           :messages [{:role "user" :content prompt}]
                           :model (or model (:model @state))
                           :depth depth}))))))
    (intern ns-sym 'rlm-query
            (fn
              ([prompt] ((var-get (ns-resolve ns-sym 'rlm-query)) prompt nil))
              ([prompt model]
               (let [{:keys [depth max-depth llm-fn]} @state]
                 (when-not llm-fn
                   (throw (ex-info "No :llm-fn supplied." {})))
                 (if (>= (inc depth) max-depth)
                   ((var-get (ns-resolve ns-sym 'llm-query)) prompt model)
                   (let [child (start-session prompt
                                              {:llm-fn llm-fn
                                               :model (or model (:model @state))
                                               :depth (inc depth)
                                               :max-depth max-depth
                                               :max-iterations (:max-iterations @state)
                                               :system-prompt (:system-prompt @state)
                                               :tools (:tools @state)})]
                     (try
                       (:response (completion child))
                       (finally
                         (close-session! child)))))))))
    (doseq [[sym v] (:tools @state)]
      (intern ns-sym sym v))
    {:id id
     :ns ns-sym
     :state state}))

(defn close-session!
  [{:keys [ns state]}]
  (when (and (some? state)
             (some? ns)
             (str/starts-with? (str ns) "rlm.session.")
             (instance? clojure.lang.Atom state)
             (= ns (:ns @state))
             (find-ns ns))
    (remove-ns ns)))

(defn eval-sexpr!
  [{:keys [ns state]} form-string]
  (let [start  (now-ms)
        out-sw (StringWriter.)
        err-sw (StringWriter.)]
    (try
      (binding [*ns* (the-ns ns)
                *out* out-sw
                *err* err-sw]
        (let [form  (read-string form-string)
              value (eval form)
              ms    (- (now-ms) start)]
          {:value value
           :stdout (str out-sw)
           :stderr (str err-sw)
           :error nil
           :ms ms}))
      (catch Throwable t
        (let [error-sw (StringWriter.)
              error-writer (PrintWriter. error-sw)]
          (.printStackTrace t error-writer)
          (.flush error-writer)
          {:value nil
           :stdout (str out-sw)
           :stderr (str err-sw)
           :error (str error-sw)
           :ms (- (now-ms) start)})))))

(defn exec-code!
  [session code]
  (assoc (eval-sexpr! session (str "(do\n" code "\n)"))
         :final (:final @(-> session :state))))

(defn- initial-messages [{:keys [system-prompt context]}]
  (let [normalized-context (deep-stringify-keys context)]
    [{:role "system" :content system-prompt}
     {:role "user"
      :content (str
                "The full input is available in the REPL variable `context`.\n\n"
                "User task:\n"
                (printable normalized-context))}]))

(defn completion
  ([context-or-session]
   (completion context-or-session {}))
  ([context-or-session opts]
   (let [session (if (:state context-or-session)
                   context-or-session
                   (do
                     (when-not (:llm-fn opts)
                       (throw (ex-info "Raw-context completion requires :llm-fn in options."
                                       {})))
                     (start-session context-or-session opts)))
         state (:state session)
         max-iterations (:max-iterations @state)]
     (when-not (:llm-fn @state)
       (throw (ex-info "Session completion requires :llm-fn in state."
                       {})))
     (loop [iterations 0
           execution-ms 0
           last-response nil]
      (if (or (:final-set? @state)
              (>= iterations max-iterations))
        {:response (printable (if (:final-set? @state)
                                (:final @state)
                                last-response))
         :iterations iterations
         :execution-ms execution-ms
         :session session}
        (let [assistant-text (printable
                              ((:llm-fn @state)
                               {:messages (vec (concat (initial-messages @state)
                                                       (:history @state)))
                                :model (:model @state)
                                :depth (:depth @state)
                                :prompt (printable (:context @state))}))
              code-blocks (extract-code-blocks assistant-text)]
          (append-history! session [{:role "assistant" :content assistant-text}])
          (if (seq code-blocks)
            (let [results (loop [remaining code-blocks
                                 executed []]
                            (if (or (empty? remaining) (:final-set? @state))
                              executed
                              (let [code (first remaining)
                                    result (exec-code! session code)]
                                (recur (rest remaining)
                                       (conj executed [code result])))))]
              (append-history!
               session
               (mapv (fn [[code result]]
                       {:role "user"
                        :content (summarize-result code result)})
                     results))
              (recur (inc iterations)
                     (+ execution-ms (reduce + 0 (map (comp :ms second) results)))
                     assistant-text))
            {:response (printable (if (:final-set? @state)
                                    (:final @state)
                                    assistant-text))
             :iterations (inc iterations)
             :execution-ms execution-ms
             :session session})))))))

(defn mock-llm
  [{:keys [messages]}]
  (let [last-user (->> messages
                       reverse
                       (filter #(= "user" (name (:role %))))
                       first
                       :content)]
    (cond
      (and (string? last-user)
           (str/includes? last-user "Return 42"))
      "```clj\n(def answer 42)\n(final! answer)\n```"

      :else
      "I do not know what to do yet.")))
