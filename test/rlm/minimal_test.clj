(ns rlm.minimal-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(defn resolve-var [sym]
  (try
    {:var (requiring-resolve sym)}
    (catch Throwable t
      {:error t})))

(defmacro thrown-with-msg?
  [klass msg-re expr]
  `(try
     ~expr
     false
     (catch ~klass e#
       (re-matches ~msg-re (.getMessage e#)))))

(deftest completion-returns-42
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)
        {mock-var :var mock-error :error} (resolve-var 'rlm.minimal/mock-llm)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      completion-error (is false (str "Could not load completion: " (.getMessage completion-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      mock-error (is false (str "Could not load mock-llm: " (.getMessage mock-error)))
      :else
      (let [session (start-var "Return 42." {:llm-fn mock-var
                                             :max-depth 2
                                             :max-iterations 4})
            result (completion-var session)]
        (try
          (is (= "42" (:response result)))
          (finally
            (close-var (:session result))))))))

(deftest close-session-removes-the-generated-namespace
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var nil {:llm-fn (constantly "")})
            ns-sym (:ns session)]
        (try
          (is (some? (find-ns ns-sym)))
          (close-var session)
          (is (nil? (find-ns ns-sym)))
          (finally
              (when (find-ns ns-sym)
              (close-var session))))))))

(deftest close-session-does-not-remove-unrelated-namespace
  (let [{close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)
        forged-ns (symbol (str "rlm.session.forged-" (random-uuid)))]
    (if close-error
      (is false (str "Could not load close-session!: " (.getMessage close-error)))
      (let [forged-state (atom {:ns (symbol (str "rlm.session.state-" (random-uuid)))})
            forged-session {:ns forged-ns :state forged-state}]
        (create-ns forged-ns)
        (try
          (is (some? (find-ns forged-ns)))
          (close-var forged-session)
          (is (some? (find-ns forged-ns)))
          (finally
            (when (find-ns forged-ns)
              (remove-ns forged-ns))))))))

(deftest close-session-idempotent
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var nil {:llm-fn (constantly "")})
            ns-sym (:ns session)]
        (try
          (is (some? (find-ns ns-sym)))
          (close-var session)
          (is (nil? (find-ns ns-sym)))
          (close-var session)
          (is (nil? (find-ns ns-sym)))
          (finally
            (when (find-ns ns-sym)
              (close-var session))))))))

(deftest completion-normalizes-context-prompt
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      completion-error (is false (str "Could not load completion: " (.getMessage completion-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var {:answer 42}
                               {:llm-fn (fn [{:keys [prompt]}]
                                          (when-not (string? prompt)
                                            (throw (ex-info "Prompt should be a string." {})))
                                          "```clj\n(final! (pr-str context))\n```")})
            result (completion-var session)]
        (try
          (is (= "{:answer 42}" (:response result)))
          (finally
            (close-var (:session result))))))))

(deftest completion-works-for-raw-context-when-llm-fn-is-provided
  (let [{completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)
        {mock-var :var mock-error :error} (resolve-var 'rlm.minimal/mock-llm)]
    (cond
      completion-error (is false (str "Could not load completion: " (.getMessage completion-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      mock-error (is false (str "Could not load mock-llm: " (.getMessage mock-error)))
      :else
      (let [result (completion-var "Return 42."
                                   {:llm-fn mock-var
                                    :max-depth 2
                                    :max-iterations 4})]
        (try
          (is (= "42" (:response result)))
          (finally
            (close-var (:session result))))))))

(deftest completion-requires-llm-fn-for-raw-context
  (let [{completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)]
    (if completion-error
      (is false (str "Could not load completion: " (.getMessage completion-error)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Raw-context completion requires :llm-fn in options."
           (completion-var "Return 42."))))))

(deftest completion-requires-llm-fn-for-session-input
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      completion-error (is false (str "Could not load completion: " (.getMessage completion-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var "x" {})]
        (try
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Session completion requires :llm-fn in state."
               (completion-var session)))
          (finally
            (close-var session)))))))

(deftest completion-records-execution-feedback-in-history
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)
        {mock-var :var mock-error :error} (resolve-var 'rlm.minimal/mock-llm)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      completion-error (is false (str "Could not load completion: " (.getMessage completion-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      mock-error (is false (str "Could not load mock-llm: " (.getMessage mock-error)))
      :else
      (let [session (start-var "Return 42." {:llm-fn mock-var
                                             :max-depth 2
                                             :max-iterations 4})
            result (completion-var session)
            history (:history @(:state session))
            history-in-session (:value (eval-var session "history"))
            first-item (first history)
            second-item (second history)
            role (:role first-item)]
        (try
          (is (= "42" (:response result)))
          (is (some? (:session result)))
          (is (pos? (:iterations result)))
          (is (number? (:execution-ms result)))
          (is (<= 0 (:execution-ms result)))
          (is (= 2 (count history)))
          (is (vector? history))
          (is (= "assistant" (if (keyword? role) (name role) role)))
          (is (str/includes? (:content second-item) "Executed code:"))
          (is (str/includes? (:content second-item) "Result:"))
          (is (= history history-in-session))
          (is (= 2 (count history-in-session)))
          (finally
            (close-var (:session result))))))))

(deftest completion-stops-executing-code-blocks-after-final
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      completion-error (is false (str "Could not load completion: " (.getMessage completion-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var "ignored"
                               {:llm-fn (fn [_]
                                          (str "```clj\n(final! :done)\n```\n\n"
                                               "```clj\n(def should-not-run true)\n```"))
                                :max-iterations 4})
            result (completion-var session)
            history (:history @(:state session))]
        (try
          (is (= ":done" (:response result)))
          (is (= session (:session result)))
          (is (pos? (:iterations result)))
          (is (number? (:execution-ms result)))
          (is (<= 0 (:execution-ms result)))
          (is (= 2 (count history)))
          (is (nil? (:value (eval-var session "(resolve 'should-not-run)"))))
          (is (str/includes? (:content (second history)) "(final! :done)"))
          (is (not (str/includes? (:content (second history)) "should-not-run")))
          (is (= history (:value (eval-var session "history"))))
          (finally
            (close-var (:session result))))))))

(deftest completion-stops-on-final-nil
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      completion-error (is false (str "Could not load completion: " (.getMessage completion-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [calls (atom 0)
            session (start-var "ignored"
                               {:llm-fn (fn [_]
                                          (swap! calls inc)
                                          (if (= 1 @calls)
                                            "```clj\n(final! nil)\n```"
                                            "assistant fallback"))
                                :max-iterations 4})
            result (completion-var session)]
        (try
          (is (= "nil" (:response result)))
          (is (= 1 @calls))
          (is (nil? (:final @(:state session))))
          (is (= 2 (count (:history @(:state session)))))
          (finally
            (close-var (:session result))))))))

(deftest completion-stops-on-final-false
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var completion-error :error} (resolve-var 'rlm.minimal/completion)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      completion-error (is false (str "Could not load completion: " (.getMessage completion-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [calls (atom 0)
            session (start-var "ignored"
                               {:llm-fn (fn [_]
                                          (swap! calls inc)
                                          (if (= 1 @calls)
                                            "```clj\n(final! false)\n```"
                                            "assistant fallback"))
                                :max-iterations 4})
            result (completion-var session)]
        (try
          (is (= "false" (:response result)))
          (is (= 1 @calls))
          (is (false? (:final @(:state session))))
          (is (= 2 (count (:history @(:state session)))))
          (finally
            (close-var (:session result))))))))

(deftest eval-sexpr-uses-context-and-persists-final
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var {:numbers [1 2 3 4]} {:llm-fn (constantly "unused")})]
        (try
          (let [define-result (eval-var session "(def total (apply + (:numbers context)))")
                final-result (eval-var session "(final! total)")]
            (is (var? (:value define-result)))
            (is (= 10 (:value final-result)))
            (is (= 10 (:final @(:state session))))
            (is (string? (:stdout final-result)))
            (is (string? (:stderr final-result))))
          (finally
            (close-var session)))))))

(deftest eval-sexpr-captures-stdout
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var {} {:llm-fn (constantly "unused")})]
        (try
          (let [result (eval-var session "(println \"hello\")")]
            (is (= "hello\n" (:stdout result)))
            (is (= "" (:stderr result)))
            (is (nil? (:value result))))
          (finally
            (close-var session)))))))

(deftest eval-sexpr-captures-error-stack
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var {} {:llm-fn (constantly "unused")})]
        (try
          (let [result (eval-var session "(throw (RuntimeException. \"boom\"))")]
            (is (nil? (:value result)))
            (is (string? (:error result)))
            (is (str/includes? (:error result) "RuntimeException"))
            (is (str/includes? (:error result) "boom")))
          (finally
            (close-var session)))))))

(deftest eval-sexpr-reads-in-session-namespace
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var {} {:llm-fn (constantly "unused")})]
        (try
          (let [result (eval-var session "::value")]
            (is (keyword? (:value result)))
            (is (= (str (:ns session)) (namespace (:value result)))))
          (finally
            (close-var session)))))))

(deftest show-vars-lists-user-publics-only
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var nil {:llm-fn (constantly "unused")})]
        (try
          (eval-var session "(def visible-answer 42)")
          (eval-var session "(defn -hidden [] :nope)")
          (let [vars (:value (eval-var session "(show-vars)"))]
            (is (= ['visible-answer] vars))
            (is (vector? vars)))
          (finally
            (close-var session)))))))

(deftest reserved-tool-names-are-rejected
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)]
    (if start-error
      (is false (str "Could not load start-session: " (.getMessage start-error)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Custom tools cannot override reserved names."
           (start-var nil {:llm-fn (constantly "")
                           :tools {'context inc}}))))))

(deftest core-tool-names-are-rejected
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)]
    (if start-error
      (is false (str "Could not load start-session: " (.getMessage start-error)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Custom tools cannot override reserved names."
           (start-var nil {:llm-fn (constantly "")
                           :tools {'map inc}}))))))

(deftest duplicate-canonical-tool-names-are-rejected
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)]
    (if start-error
      (is false (str "Could not load start-session: " (.getMessage start-error)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Custom tools must use unique names."
           (start-var nil {:llm-fn (constantly "")
                           :tools {'foo inc
                                   :foo dec}}))))))

(deftest custom-tools-are-available-in-session-namespace
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var nil {:llm-fn (constantly "")
                                    :tools {:triple (fn [x] (* 3 x))}})]
        (try
          (let [tools (:tools @(:state session))]
            (is (= '#{triple} (set (keys tools))))
            (is (fn? (get tools 'triple)))
            (is (= 12 (:value (eval-var session "(triple 4)")))))
          (finally
            (close-var session)))))))

(deftest rlm-query-spawns-a-child-session-before-the-depth-cap
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var nil {:llm-fn (fn [{:keys [prompt depth]}]
                                              (str prompt "|" depth))
                                    :max-depth 3})]
        (try
          (is (= "child|1" (:value (eval-var session "(rlm-query \"child\")"))))
          (finally
            (close-var session)))))))

(deftest rlm-query-falls-back-to-llm-query-at-the-depth-cap
  (let [{start-var :var start-error :error} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var eval-error :error} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var close-error :error} (resolve-var 'rlm.minimal/close-session!)]
    (cond
      start-error (is false (str "Could not load start-session: " (.getMessage start-error)))
      eval-error (is false (str "Could not load eval-sexpr!: " (.getMessage eval-error)))
      close-error (is false (str "Could not load close-session!: " (.getMessage close-error)))
      :else
      (let [session (start-var nil {:llm-fn (fn [{:keys [prompt depth]}]
                                              (str prompt "|" depth))
                                    :depth 1
                                    :max-depth 2})]
        (try
          (is (= "cap|1" (:value (eval-var session "(rlm-query \"cap\")"))))
          (finally
            (close-var session)))))))

;; ── scaffold restoration ─────────────────────────────────────

(deftest scaffold-restores-context-after-overwrite
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {exec-var :var} (resolve-var 'rlm.minimal/exec-code!)
        {eval-var :var} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [session (start-var {:original true} {:llm-fn (constantly "")})]
      (try
        (exec-var session "(def context \"overwritten\")")
        (let [ctx (:value (eval-var session "context"))]
          (is (= {:original true} ctx)))
        (finally
          (close-var session))))))

(deftest scaffold-restores-final-after-overwrite
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {exec-var :var} (resolve-var 'rlm.minimal/exec-code!)
        {eval-var :var} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [session (start-var nil {:llm-fn (constantly "")})]
      (try
        (exec-var session "(def final! 42)")
        (let [f (:value (eval-var session "(fn? final!)"))]
          (is (true? f)))
        (finally
          (close-var session))))))

;; ── output truncation ───────────────────────────────────────

(deftest summarize-result-truncates-long-output
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {exec-var :var} (resolve-var 'rlm.minimal/exec-code!)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)
        {completion-var :var} (resolve-var 'rlm.minimal/completion)]
    (let [session (start-var nil
                             {:llm-fn (fn [_]
                                        "```clj\n(final! (apply str (repeat 30000 \"x\")))\n```")
                              :max-iterations 2})
          result (completion-var session)
          history (:history @(:state session))
          exec-msg (:content (second history))]
      (try
        (is (< (count exec-msg) 25000))
        (is (str/includes? exec-msg "chars truncated"))
        (finally
          (close-var (:session result)))))))

;; ── default-answer fallback ─────────────────────────────────

(deftest completion-uses-default-answer-when-iterations-exhaust
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var} (resolve-var 'rlm.minimal/completion)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [calls (atom 0)
          session (start-var "test"
                             {:llm-fn (fn [_]
                                        (let [n (swap! calls inc)]
                                          (if (<= n 2)
                                            "I'm thinking..."
                                            "The answer is 99.")))
                              :max-iterations 2})
          result (completion-var session)]
      (try
        ;; 2 iterations of "thinking" + 1 default-answer call = 3 total
        (is (= 3 @calls))
        (is (= "The answer is 99." (:response result)))
        (is (= 2 (:iterations result)))
        (finally
          (close-var (:session result)))))))

;; ── timeout ─────────────────────────────────────────────────

(deftest completion-stops-on-timeout
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var} (resolve-var 'rlm.minimal/completion)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [session (start-var nil
                             {:llm-fn (fn [_]
                                        (Thread/sleep 200)
                                        "still thinking")
                              :max-iterations 100
                              :max-timeout 0.3})
          result (completion-var session)]
      (try
        (is (< (:iterations result) 100))
        (is (string? (:response result)))
        (finally
          (close-var (:session result)))))))

;; ── max errors ──────────────────────────────────────────────

(deftest completion-stops-on-consecutive-errors
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var} (resolve-var 'rlm.minimal/completion)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [session (start-var nil
                             {:llm-fn (fn [_]
                                        "```clj\n(/ 1 0)\n```")
                              :max-iterations 20
                              :max-errors 3})
          result (completion-var session)]
      (try
        (is (= 3 (:iterations result)))
        (is (string? (:response result)))
        (finally
          (close-var (:session result)))))))

;; ── batched queries ─────────────────────────────────────────

(deftest llm-query-batched-returns-vector-of-results
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [session (start-var nil {:llm-fn (fn [{:keys [prompt]}]
                                            (str "echo:" prompt))
                                  :max-depth 1})]
      (try
        (let [result (:value (eval-var session
                               "(llm-query-batched [\"a\" \"b\" \"c\"])"))]
          (is (vector? result))
          (is (= 3 (count result)))
          (is (every? #(str/starts-with? % "echo:") result)))
        (finally
          (close-var session))))))

(deftest rlm-query-batched-falls-back-at-depth-cap
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {eval-var :var} (resolve-var 'rlm.minimal/eval-sexpr!)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [session (start-var nil {:llm-fn (fn [{:keys [prompt depth]}]
                                            (str prompt "|" depth))
                                  :depth 1
                                  :max-depth 2})]
      (try
        (let [result (:value (eval-var session
                               "(rlm-query-batched [\"x\" \"y\"])"))]
          (is (vector? result))
          (is (= 2 (count result)))
          (is (= "x|1" (first result)))
          (is (= "y|1" (second result))))
        (finally
          (close-var session))))))

;; ── no-code-block continuation ──────────────────────────────

(deftest completion-continues-loop-when-no-code-blocks
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var} (resolve-var 'rlm.minimal/completion)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [calls (atom 0)
          session (start-var nil
                             {:llm-fn (fn [_]
                                        (let [n (swap! calls inc)]
                                          (if (< n 3)
                                            "Let me think about this..."
                                            "```clj\n(final! :got-it)\n```")))
                              :max-iterations 5})
          result (completion-var session)]
      (try
        (is (= ":got-it" (:response result)))
        (is (= 3 (:iterations result)))
        (finally
          (close-var (:session result)))))))

;; ── root-prompt ─────────────────────────────────────────────

(deftest completion-passes-root-prompt-to-llm
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)
        {completion-var :var} (resolve-var 'rlm.minimal/completion)
        {close-var :var} (resolve-var 'rlm.minimal/close-session!)]
    (let [seen-messages (atom [])
          session (start-var nil
                             {:llm-fn (fn [{:keys [messages]}]
                                        (reset! seen-messages messages)
                                        "```clj\n(final! :ok)\n```")})
          result (completion-var session {:root-prompt "Find the answer"})]
      (try
        (is (= ":ok" (:response result)))
        (let [last-user (->> @seen-messages
                             (filter #(= "user" (:role %)))
                             last
                             :content)]
          (is (str/includes? last-user "Find the answer")))
        (finally
          (close-var (:session result)))))))

;; ── new reserved names ──────────────────────────────────────

(deftest batched-query-names-are-reserved
  (let [{start-var :var} (resolve-var 'rlm.minimal/start-session)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Custom tools cannot override reserved names."
         (start-var nil {:llm-fn (constantly "")
                         :tools {'llm-query-batched inc}})))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'rlm.minimal-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
