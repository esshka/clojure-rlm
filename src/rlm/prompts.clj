(ns rlm.prompts
  (:require [clojure.string :as str]))

(def default-system-prompt
  (str
   "You are tasked with answering a query with associated context. "
   "You can access, transform, and analyze this context interactively in a Clojure REPL environment "
   "that can recursively query sub-LLMs, which you are strongly encouraged to use. "
   "You will be queried iteratively until you provide a final answer.\n\n"
   "The REPL environment is initialized with:\n"
   "1. A `context` variable containing the input for your query. Examine it to understand what you are working with.\n"
   "2. `(llm-query prompt)` — single LLM completion call (no REPL). Fast and lightweight for extraction, summarization, or Q&A. Optionally `(llm-query prompt model)`.\n"
   "3. `(llm-query-batched prompts)` — runs multiple `llm-query` calls concurrently, returns a vector of strings in input order. Much faster than sequential calls.\n"
   "4. `(rlm-query prompt)` — spawns a recursive RLM sub-call with its own REPL for multi-step reasoning. Falls back to `llm-query` at depth limit. Optionally `(rlm-query prompt model)`.\n"
   "5. `(rlm-query-batched prompts)` — spawns multiple recursive RLM sub-calls concurrently.\n"
   "6. `(show-vars)` — returns all user-defined variables in the REPL.\n"
   "{custom_tools_section}\n\n"
   "**When to use `llm-query` vs `rlm-query`:**\n"
   "- Use `llm-query` for simple one-shot tasks: extracting info, summarizing, answering factual questions, classifying.\n"
   "- Use `rlm-query` when the subtask needs deeper thinking: multi-step reasoning, its own REPL, iterative problem-solving.\n\n"
   "**Breaking down problems:** Break problems into digestible components — chunk large contexts, "
   "decompose hard tasks into sub-problems, delegate via `llm-query` / `rlm-query`. "
   "Write a programmatic strategy using the REPL.\n\n"
   "**REPL for computation:** Use the REPL for computation and chain results into LLM calls.\n\n"
   "Example — chunking a large context:\n"
   "```clj\n"
   "(let [chunks (partition-all 5 context)\n"
   "      answers (llm-query-batched\n"
   "               (mapv #(str \"Summarize this chunk: \" (pr-str %)) chunks))]\n"
   "  (final! (llm-query (str \"Combine these summaries into a final answer: \" (pr-str answers)))))\n"
   "```\n\n"
   "Example — recursive delegation:\n"
   "```clj\n"
   "(let [analysis (rlm-query (str \"Analyze trends in: \" (pr-str (:data context))))]\n"
   "  (final! {:analysis analysis\n"
   "           :recommendation (llm-query (str \"Recommend action given: \" analysis))}))\n"
   "```\n\n"
   "Write Clojure code in fenced ```clj blocks. Call `(final! x)` to return your answer and stop.\n"
   "You have access to all of `clojure.core`. You can `def` vars, `defn` functions, and `require` libraries.\n"
   "Use `println` to inspect intermediate values.\n\n"
   "Think step by step, plan, and execute immediately. Do not just describe what you will do — write and run code. "
   "Use the REPL and sub-LLMs as much as possible. Explicitly examine the context before answering."))

(defn format-tools-for-prompt
  "Format custom tools map into a string for prompt injection. Returns nil if no tools."
  [tools]
  (when (seq tools)
    (str/join "\n"
              (map (fn [[sym _]]
                     (str "   - `(" (name sym) " ...)` — a custom tool function"))
                   tools))))

(defn context-metadata
  "Build a user message describing the context type and size."
  [context]
  (let [ctx-type (cond
                   (string? context) "string"
                   (map? context)    "map"
                   (vector? context) "vector"
                   (sequential? context) "sequence"
                   (nil? context)    "nil"
                   :else (str (type context)))
        ctx-str  (pr-str context)
        total-len (count ctx-str)]
    (str "The full input is available in the REPL variable `context`.\n"
         "Your context is a " ctx-type " with " total-len " total characters.\n\n"
         "User task:\n"
         (if (> total-len 2000)
           (str (subs ctx-str 0 2000)
                "... [" (- total-len 2000) " chars truncated — access full data via `context`]")
           ctx-str))))

(defn build-system-messages
  "Build the initial system + context-metadata messages for a session."
  [system-prompt context tools]
  (let [tools-section (format-tools-for-prompt tools)
        final-prompt  (str/replace
                       system-prompt
                       "{custom_tools_section}"
                       (if tools-section
                         (str "\n7. Custom tools available in the REPL:\n" tools-section)
                         ""))]
    [{:role "system" :content final-prompt}
     {:role "user"   :content (context-metadata context)}]))

(defn build-user-prompt
  "Build a per-iteration user prompt. Iteration 0 gets a safeguard; later iterations get a continuation nudge."
  [iteration root-prompt]
  (let [base (if (zero? iteration)
               (str "You have not interacted with the REPL environment yet. "
                    "Your next action should be to examine the context and figure out "
                    "how to answer the prompt. Do not provide a final answer without "
                    "first using the REPL.\n\n")
               "The history above shows your previous REPL interactions. ")
        prompt-ref (if root-prompt
                     (str "Think step-by-step on how to answer the original prompt: \""
                          root-prompt
                          "\".\n\nContinue using the REPL (which has the `context` variable) "
                          "and sub-LLMs. Your next action:")
                     (str "Continue using the REPL environment (which has the `context` variable) "
                          "and sub-LLMs. Your next action:"))]
    {:role "user" :content (str base prompt-ref)}))
