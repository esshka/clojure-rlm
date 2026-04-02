(ns rlm.minimal
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rlm.async :as ra]
            [rlm.prompts :as prompts])
  (:import [java.io PrintWriter StringWriter]))
(def reserved-names
  '#{context
     history
     llm-query
     llm-query-batched
     rlm-query
     rlm-query-batched
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

(def ^:private max-result-length
  "Maximum characters in a single execution result before truncation."
  20000)

(defn- summarize-result [code {:keys [value stdout stderr error ms final]}]
  (let [result-text (if error
                      error
                      (str/trimr (with-out-str (pprint/pprint value))))
        raw (str/join
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
                      (str "Execution time (ms): " ms)]))]
    (if (> (count raw) max-result-length)
      (str (subs raw 0 max-result-length)
           "\n... [" (- (count raw) max-result-length) " chars truncated]")
      raw)))

(defn- extract-code-blocks [s]
  (if (string? s)
    (map second
         (re-seq #"(?s)```(?:clj|clojure)\s*\n(.*?)```" s))
    []))
(declare start-session completion close-session!)
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
(defn- total-chars
  "Total character count of all message :content values."
  [messages]
  (reduce + 0 (map #(count (str (:content %))) messages)))

(defn- intern-scaffold!
  "Create and intern all reserved session vars. Returns the scaffold map."
  [ns-sym state]
  (let [context (:context @state)

        final-fn
        (fn [x]
          (swap! state assoc :final x :final-set? true)
          x)

        show-vars-fn
        (fn []
          (user-publics {:ns ns-sym :state state}))

        llm-query-fn
        (fn
          ([prompt] ((var-get (ns-resolve ns-sym 'llm-query)) prompt nil))
          ([prompt model]
           (let [{:keys [llm-fn depth call-timeout]} @state]
             (when-not llm-fn
               (throw (ex-info "No :llm-fn supplied." {})))
             (printable
              (ra/call-with-timeout
               #(llm-fn {:prompt (printable prompt)
                         :messages [{:role "user" :content prompt}]
                         :model (or model (:model @state))
                         :depth depth})
               (some-> call-timeout (* 1000)))))))

        llm-query-batched-fn
        (fn
          ([prompts] ((var-get (ns-resolve ns-sym 'llm-query-batched)) prompts nil))
          ([prompts model]
           (let [lqfn (var-get (ns-resolve ns-sym 'llm-query))
                 {:keys [max-concurrent call-timeout]} @state]
             (ra/run-batched #(lqfn % model)
                          prompts
                          (or max-concurrent 4)
                          (some-> call-timeout (* 1000))))))

        rlm-query-fn
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
                     (close-session! child))))))))

        rlm-query-batched-fn
        (fn
          ([prompts] ((var-get (ns-resolve ns-sym 'rlm-query-batched)) prompts nil))
          ([prompts model]
           (let [rqfn (var-get (ns-resolve ns-sym 'rlm-query))
                 {:keys [max-concurrent call-timeout]} @state]
             (ra/run-batched #(rqfn % model)
                          prompts
                          (or max-concurrent 4)
                          (some-> call-timeout (* 1000))))))

        scaffold {'context           context
                  'history           (:history @state)
                  'final!            final-fn
                  'show-vars         show-vars-fn
                  'llm-query         llm-query-fn
                  'llm-query-batched llm-query-batched-fn
                  'rlm-query         rlm-query-fn
                  'rlm-query-batched rlm-query-batched-fn}]
    (doseq [[sym v] scaffold]
      (intern ns-sym sym v))
    scaffold))

(defn- restore-scaffold!
  "Re-intern reserved vars so LLM code cannot permanently shadow them."
  [{:keys [ns state]}]
  (let [{:keys [context history scaffold]} @state]
    (intern ns 'context context)
    (intern ns 'history history)
    (doseq [[sym v] (dissoc scaffold 'context 'history)]
      (intern ns sym v))))
(defn start-session
  [context opts]
  (let [opts (merge {:max-depth 1
                     :depth 0
                     :max-iterations 8
                     :max-timeout nil
                     :max-errors nil
                     :call-timeout nil
                     :max-concurrent 4
                     :compaction false
                     :compaction-threshold 100000
                     :system-prompt prompts/default-system-prompt
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
                     :max-timeout (:max-timeout opts)
                     :max-errors (:max-errors opts)
                     :call-timeout (:call-timeout opts)
                     :max-concurrent (:max-concurrent opts)
                     :compaction (:compaction opts)
                     :compaction-threshold (:compaction-threshold opts)
                     :llm-fn (:llm-fn opts)
                     :model (:model opts)
                     :system-prompt (:system-prompt opts)
                     :tools tools})]
    (create-ns ns-sym)
    (binding [*ns* (the-ns ns-sym)]
      (refer 'clojure.core))
    (let [scaffold (intern-scaffold! ns-sym state)]
      (swap! state assoc :scaffold scaffold))
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
  (let [result (eval-sexpr! session (str "(do\n" code "\n)"))]
    (restore-scaffold! session)
    (assoc result :final (:final @(-> session :state)))))
(defn- initial-messages
  "Build system + context-metadata messages for a session."
  [{:keys [state]}]
  (let [{:keys [system-prompt context tools]} @state]
    (prompts/build-system-messages
     system-prompt
     (deep-stringify-keys context)
     tools)))
(defn- compact-history
  "Ask the LLM to summarize progress, return a shorter message-history."
  [session system-messages message-history compaction-count]
  (let [state (:state session)
        llm-fn (:llm-fn @state)
        summary-prompt (conj (vec message-history)
                             {:role "user"
                              :content (str "Summarize your progress so far. Include:\n"
                                            "1. Which steps you have completed and which remain.\n"
                                            "2. Any concrete intermediate results — preserve exactly.\n"
                                            "3. What your next action should be.\n"
                                            "Be concise but preserve all key results.")})
        summary (printable
                 (llm-fn {:messages summary-prompt
                          :model (:model @state)
                          :depth (:depth @state)
                          :prompt (printable (:context @state))}))]
    (into (vec (take 2 system-messages))
          [{:role "assistant" :content summary}
           {:role "user"
            :content (str "Your conversation has been compacted " compaction-count " time(s). "
                          "Continue from the summary above. Do NOT repeat completed work. "
                          "Use (show-vars) to check existing REPL variables. Your next action:")}])))

(defn- default-answer
  "When iterations exhaust without final!, make one last LLM call for a final answer."
  [session message-history]
  (let [state (:state session)
        llm-fn (:llm-fn @state)
        prompt (conj (vec message-history)
                     {:role "user"
                      :content (str "You have run out of iterations. "
                                    "Based on everything above, provide your best final answer now. "
                                    "Use a ```clj block with (final! your-answer) or just state it.")})]
    (printable
     (llm-fn {:messages prompt
              :model (:model @state)
              :depth (:depth @state)
              :prompt (printable (:context @state))}))))
(defn completion
  ([context-or-session]
   (completion context-or-session {}))
  ([context-or-session opts]
   (let [root-prompt (:root-prompt opts)
         session (if (:state context-or-session)
                   context-or-session
                   (do
                     (when-not (:llm-fn opts)
                       (throw (ex-info "Raw-context completion requires :llm-fn in options."
                                       {})))
                     (start-session context-or-session opts)))
         state (:state session)
         {:keys [max-iterations max-timeout max-errors
                 compaction compaction-threshold]} @state
         system-messages (initial-messages session)
         start-time (now-ms)]
     (when-not (:llm-fn @state)
       (throw (ex-info "Session completion requires :llm-fn in state."
                       {})))
     (loop [message-history system-messages
            iteration 0
            execution-ms 0
            consecutive-errors 0
            last-response nil
            compaction-count 0]
       (let [timed-out? (and max-timeout
                             (> (- (now-ms) start-time) (* max-timeout 1000)))
             error-cap? (and max-errors
                             (>= consecutive-errors max-errors))
             done? (or (:final-set? @state)
                       (>= iteration max-iterations)
                       timed-out?
                       error-cap?)]
         (if done?
           (cond
             (:final-set? @state)
             {:response (printable (:final @state))
              :iterations iteration
              :execution-ms execution-ms
              :session session}

             (or timed-out? error-cap?)
             {:response (printable (or last-response
                                       (str (if timed-out? "Timeout" "Error threshold")
                                            " exceeded.")))
              :iterations iteration
              :execution-ms execution-ms
              :session session}

             :else
             (let [da (default-answer session message-history)]
               {:response (printable (if (:final-set? @state)
                                       (:final @state)
                                       da))
                :iterations iteration
                :execution-ms execution-ms
                :session session}))

           (let [[message-history compaction-count]
                 (if (and compaction
                          (> (total-chars message-history) compaction-threshold))
                   [(compact-history session system-messages
                                     message-history (inc compaction-count))
                    (inc compaction-count)]
                   [message-history compaction-count])

                 user-prompt (prompts/build-user-prompt iteration root-prompt)
                 current-messages (conj (vec message-history) user-prompt)

                 ;; Call LLM with per-call timeout; catch errors outside recur
                 llm-result (try
                              {:ok (printable
                                    (ra/call-with-timeout
                                     #((:llm-fn @state)
                                       {:messages current-messages
                                        :model (:model @state)
                                        :depth (:depth @state)
                                        :prompt (printable (:context @state))})
                                     (some-> (:call-timeout @state) (* 1000))))}
                              (catch Exception e
                                {:error (.getMessage e)}))]

             (if (:error llm-result)
               ;; LLM call failed (timeout or other) — treat as error iteration
               (do
                 (append-history! session [{:role "assistant"
                                            :content (str "Error: " (:error llm-result))}])
                 (recur (vec (concat system-messages (:history @state)))
                        (inc iteration)
                        execution-ms
                        (inc consecutive-errors)
                        last-response
                        compaction-count))

               ;; LLM call succeeded — process response
               (let [assistant-text (:ok llm-result)
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
                                              (conj executed [code result])))))
                         has-error (boolean (some (fn [[_ r]] (:error r)) results))
                         new-errors (if has-error (inc consecutive-errors) 0)
                         iter-ms (reduce + 0 (map (comp :ms second) results))]
                     (append-history!
                      session
                      (mapv (fn [[code result]]
                              {:role "user"
                               :content (summarize-result code result)})
                            results))
                     (recur (vec (concat system-messages (:history @state)))
                            (inc iteration)
                            (+ execution-ms iter-ms)
                            new-errors
                            assistant-text
                            compaction-count))

                   (recur (vec (concat system-messages (:history @state)))
                          (inc iteration)
                          execution-ms
                          0
                          assistant-text
                          compaction-count)))))))))))

(defn mock-llm
  [{:keys [messages]}]
  (let [user-texts (->> messages
                        (filter #(= "user" (name (:role %))))
                        (map :content)
                        (filter string?))]
    (cond
      (some #(str/includes? % "Return 42") user-texts)
      "```clj\n(def answer 42)\n(final! answer)\n```"

      :else
      "I do not know what to do yet.")))
