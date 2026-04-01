# RLM — REPL-backed Language Model

RLM is a Clojure runtime that gives a Large Language Model a **live REPL** as its action space. Instead of tool-calling via JSON schemas, the LLM writes ordinary Clojure code in fenced blocks, the runtime evaluates it, feeds results back, and the LLM iterates until it arrives at an answer.

The core insight: a general-purpose programming language is the most expressive "tool" you can give an LLM. Rather than defining a fixed set of actions, RLM lets the model define variables, write functions, query sub-agents, and compose arbitrary logic — all inside a sandboxed namespace that exists only for the duration of the session.

## How it works

### The agent loop

```
                    +-----------+
                    |  Caller   |
                    |  provides |
                    |  context  |
                    +-----+-----+
                          |
                          v
                 +--------+--------+
                 |  start-session  |
                 |  creates an     |
                 |  isolated ns    |
                 +--------+--------+
                          |
                          v
              +-----------+-----------+
              |     completion loop   |<---------+
              |                       |          |
              |  1. Build messages    |          |
              |     (system + user    |          |
              |      + history)       |          |
              |                       |          |
              |  2. Call :llm-fn      |          |
              |     -> assistant text |          |
              |                       |          |
              |  3. Extract ```clj    |          |
              |     code blocks       |          |
              |                       |          |
              |  4. Execute each      |          |
              |     block in the      |          |
              |     session namespace |          |
              |                       |          |
              |  5. Append results    |          |
              |     to history        |          |
              +-----------+-----------+          |
                          |                      |
                    +-----+------+               |
                    | final!     |  No           |
                    | called?    +---------------+
                    | or max     |
                    | iterations?|
                    +-----+------+
                          | Yes
                          v
                   +------+------+
                   |   Return    |
                   |  {:response |
                   |   :session  |
                   |   ...}      |
                   +-------------+
```

1. The caller provides a **context** (any Clojure value — a string, a map, a vector) and an **`:llm-fn`** that talks to an actual LLM.
2. `start-session` creates a fresh Clojure namespace (`rlm.session.<uuid>`) and interns helper vars into it.
3. The `completion` loop calls `:llm-fn` with the accumulated message history.
4. The LLM responds with natural language and fenced `` ```clj `` code blocks.
5. Each code block is wrapped in `(do ...)` and evaluated in the session namespace. Stdout, stderr, return values, errors, and execution time are captured.
6. Execution results are appended to the conversation history as user messages, so the LLM sees what happened.
7. The loop repeats until the LLM calls `(final! x)` to declare its answer, `max-iterations` is reached, or a timeout/error limit is hit.
8. If iterations exhaust without `final!`, one last LLM call asks for a default answer.
9. Each iteration includes a per-iteration user prompt that nudges the LLM to use the REPL (iteration 0 gets a safeguard preventing premature answers).

### What the LLM sees inside the REPL

When code runs inside a session, these symbols are available:

| Symbol | Description |
|---|---|
| `context` | The original input the caller provided |
| `history` | Vector of all messages so far (assistant responses + execution results) |
| `final!` | `(final! x)` — stores `x` as the answer and stops the loop |
| `show-vars` | `(show-vars)` — lists user-defined vars in the session (excludes reserved names and `-`-prefixed) |
| `llm-query` | `(llm-query prompt)` or `(llm-query prompt model)` — single-turn LLM call, returns a string |
| `llm-query-batched` | `(llm-query-batched prompts)` or `(llm-query-batched prompts model)` — concurrent `llm-query` over a vector of prompts, returns a vector of strings |
| `rlm-query` | `(rlm-query prompt)` or `(rlm-query prompt model)` — spawns a **child RLM session** recursively |
| `rlm-query-batched` | `(rlm-query-batched prompts)` or `(rlm-query-batched prompts model)` — concurrent `rlm-query` over a vector of prompts |
| Custom tools | Any functions passed via the `:tools` option |
| `clojure.core` | The full standard library is referred into the session namespace |

All reserved symbols are protected by **scaffold restoration** — if the LLM's code accidentally overwrites `context`, `llm-query`, etc., they are automatically re-interned after each execution.

The LLM can also `def` new vars, `defn` functions, `require` libraries — it has a real Clojure namespace.

### Recursive sub-agents

`rlm-query` is the key to composability. When called, it creates a child session with `depth + 1` and runs a full completion loop inside it. This means an LLM can delegate sub-problems to fresh agent instances:

```clojure
;; The LLM might write this inside a session:
(let [summary (rlm-query "Summarize this data" )
      analysis (rlm-query "Analyze trends in this data")]
  (final! {:summary summary :analysis analysis}))
```

Recursion is bounded by `:max-depth`. When `depth + 1 >= max-depth`, `rlm-query` falls back to `llm-query` (a single-turn call with no REPL, no code execution). This prevents infinite nesting.

```
depth 0: root session        (has REPL, can spawn children)
depth 1: child session       (has REPL, can spawn children if max-depth > 2)
depth N: at max-depth - 1    (rlm-query falls back to llm-query, no REPL)
```

### Session isolation

Each session gets its own namespace (`rlm.session.<uuid>`). This means:

- Variables defined in one session don't leak into another.
- Sessions can run concurrently without interference.
- `close-session!` removes the namespace entirely, cleaning up all vars.
- The close function is idempotent and guards against removing unrelated namespaces (the ns stored in the atom must match the session ns).

### Iteration-aware prompting

Each iteration includes a structured user prompt that guides the LLM:

- **Iteration 0** gets a safeguard: *"You have not interacted with the REPL yet. Examine the context first."* This prevents the LLM from guessing an answer without using the REPL.
- **Subsequent iterations** get: *"The history above shows your previous REPL interactions. Continue..."*
- If `:root-prompt` is set, it's repeated each iteration so the LLM doesn't lose sight of the original task.

### Compaction

For long-running sessions, history can grow beyond the LLM's context window. With `:compaction true`, the runtime monitors total message size. When it exceeds `:compaction-threshold` (default 100K chars), it asks the LLM to summarize its progress, then replaces the history with the summary. The REPL namespace is preserved — all `def`'d vars survive compaction.

### Safety features

- **Scaffold restoration:** After each code block executes, all reserved vars (`context`, `final!`, `llm-query`, etc.) are re-interned from the original values. The LLM cannot permanently shadow them.
- **Output truncation:** Execution results are capped at 20,000 characters to prevent context window blowout.
- **Timeout:** `:max-timeout` (seconds) stops the loop if wall-clock time is exceeded.
- **Error cap:** `:max-errors` stops the loop after N consecutive code execution errors.
- **Default answer:** When iterations exhaust without `final!`, one final LLM call asks for the best answer given the conversation so far.

## The `:llm-fn` contract

RLM has **no built-in LLM client**. The caller injects a function:

```clojure
(fn [{:keys [messages model depth prompt]}]
  ;; messages — vector of {:role "system"/"user"/"assistant", :content "..."}
  ;; model    — optional model identifier (passed through from session opts)
  ;; depth    — current recursion depth (0 = root)
  ;; prompt   — the original context as a string
  ;;
  ;; Must return: a string (the assistant's response)
  ...)
```

This makes RLM transport-agnostic. You can wire it to OpenAI, Anthropic, Ollama, a local model, or a mock for testing.

## Usage

### Minimal example

```clojure
(require '[rlm.minimal :as rlm])

;; Define your LLM adapter
(defn my-llm-fn [{:keys [messages]}]
  ;; Call your preferred LLM API here
  ;; Must return a string
  (call-some-api messages))

;; Run a task
(let [result (rlm/completion
               "What is the factorial of 10?"
               {:llm-fn my-llm-fn
                :max-iterations 8
                :max-depth 2})]
  (println (:response result)))
```

The LLM would receive system instructions telling it to use `` ```clj `` blocks and call `(final! x)` when done. It might respond:

```
I'll compute the factorial of 10.

\`\`\`clj
(final! (reduce * (range 1 11)))
\`\`\`
```

The runtime evaluates the code, sees that `final!` was called with `3628800`, and returns `{:response "3628800", ...}`.

### Using a pre-built session

For more control, create a session explicitly:

```clojure
(let [session (rlm/start-session
                {:data [1 2 3 4 5]}
                {:llm-fn my-llm-fn
                 :max-iterations 10
                 :max-depth 3
                 :tools {:fetch-url (fn [url] (slurp url))}})
      result (rlm/completion session)]
  (try
    (println (:response result))
    (println "Iterations:" (:iterations result))
    (println "Execution time:" (:execution-ms result) "ms")
    (finally
      (rlm/close-session! (:session result)))))
```

### Custom tools

Tools are plain functions passed as a map. They become callable vars in the session namespace:

```clojure
(rlm/start-session "Analyze this"
  {:llm-fn my-llm-fn
   :tools {:fetch-page  (fn [url] (slurp url))
           :search-docs (fn [query] (my-search-fn query))
           :save-result (fn [data] (spit "result.edn" (pr-str data)))}})
```

The LLM can then write:

```clojure
(let [page (fetch-page "https://example.com")
      results (search-docs "relevant query")]
  (final! {:page page :results results}))
```

Tool names are validated:
- Cannot shadow reserved names (`context`, `history`, `final!`, `show-vars`, `llm-query`, `rlm-query`)
- Cannot shadow `clojure.core` publics (`map`, `filter`, `reduce`, etc.)
- Must be unique after canonicalization (`:foo` and `'foo` are the same tool)

### Direct eval

You can evaluate code in a session without going through the LLM loop:

```clojure
(let [session (rlm/start-session {:x 10} {:llm-fn my-llm-fn})]
  (rlm/eval-sexpr! session "(* (:x context) 2)")
  ;; => {:value 20, :stdout "", :stderr "", :error nil, :ms 1}

  (rlm/eval-sexpr! session "(println \"hello\")")
  ;; => {:value nil, :stdout "hello\n", :stderr "", :error nil, :ms 0}

  (rlm/eval-sexpr! session "(/ 1 0)")
  ;; => {:value nil, :stdout "", :stderr "", :error "java.lang.ArithmeticException: ...", :ms 0}

  (rlm/close-session! session))
```

## Completion result

`completion` returns a map:

```clojure
{:response     "..."        ;; The final answer (from final!) or last assistant text
 :iterations   3            ;; Number of loop iterations
 :execution-ms 42           ;; Total time spent in code execution (not LLM calls)
 :session      {...}}       ;; The session (for inspection or cleanup)
```

## How history works

History is a vector of messages that grows throughout the session:

```clojure
[{:role "assistant" :content "I'll compute this.\n```clj\n(+ 1 2)\n```"}
 {:role "user"      :content "Executed code:\n```clj\n(+ 1 2)\n```\nResult:\n3\nExecution time (ms): 0"}
 {:role "assistant" :content "The answer is 3.\n```clj\n(final! 3)\n```"}
 {:role "user"      :content "Executed code:\n```clj\n(final! 3)\n```\nResult:\n3\n..."}]
```

Each LLM turn produces an `"assistant"` message. Each executed code block produces a `"user"` message containing the code, its result (pretty-printed), stdout, stderr, and execution time. The history is also accessible as the `history` var inside the session namespace, so the LLM's code can inspect its own conversation.

## Configuration options

| Option | Default | Description |
|---|---|---|
| `:llm-fn` | *required* | Function that calls an LLM. See contract above. |
| `:max-iterations` | `8` | Maximum number of LLM turns before the loop stops |
| `:max-depth` | `1` | Maximum recursion depth for `rlm-query`. `1` = no child sessions. |
| `:depth` | `0` | Current depth (set automatically for child sessions) |
| `:model` | `nil` | Passed through to `:llm-fn` |
| `:system-prompt` | Built-in prompt | Comprehensive prompt with function docs, examples, and strategy guidance |
| `:tools` | `{}` | Map of `symbol-or-keyword -> function` to inject into the session |
| `:max-timeout` | `nil` | Wall-clock timeout in seconds. `nil` = no limit. |
| `:max-errors` | `nil` | Max consecutive code execution errors before stopping. `nil` = no limit. |
| `:compaction` | `false` | Enable history compaction when context grows large |
| `:compaction-threshold` | `100000` | Character count threshold to trigger compaction |
| `:root-prompt` | `nil` | *(completion opt)* Reminder prompt shown to the LLM each iteration |

## OpenRouter adapter

An included adapter (`rlm.openrouter`) connects RLM to any model available on [OpenRouter](https://openrouter.ai/).

### Setup

```bash
cp .env.example .env
# Edit .env and add your OpenRouter API key
```

### Usage

```clojure
(require '[rlm.minimal :as rlm])
(require '[rlm.openrouter :as or])

(let [result (rlm/completion
               "Compute the sum of the first 20 prime numbers."
               {:llm-fn (or/make-llm-fn)
                :model "qwen/qwen3.5-35b-a3b"
                :max-iterations 8})]
  (println (:response result))
  (rlm/close-session! (:session result)))
```

`make-llm-fn` accepts an optional `:default-model` (defaults to `"qwen/qwen3.5-9b"`). The `:model` key in session options overrides it per-session.

## Benchmark results

Tested with real LLM calls via OpenRouter. All tasks used the completion loop with no human intervention — the model writes code, the REPL runs it, results feed back, the model iterates until `(final! ...)`.

### `qwen/qwen3.5-9b`

| Task | Result | Iterations | Exec time |
|------|--------|-----------|-----------|
| Sum of first 20 primes | `639` (correct) | 2 | 7ms |

### `qwen/qwen3.5-35b-a3b`

| Task | Result | Iterations | Exec time | Correct |
|------|--------|-----------|-----------|---------|
| **Sales data analysis** — best/worst month, averages, month-over-month growth from 12-month map | Full analysis map with all fields | 1 | 15ms | Yes |
| **Tower of Hanoi N=4** — implement recursive algorithm, collect moves, verify count = 2^4-1 | `{:moves [...] :count 15 :valid? true}` | 2 | 19ms | Yes |
| **Employee DB with custom tool** — query mock DB via injected `query-db` tool, compute avg salary/dept, top earner, total payroll | Grouped results map | 4 | 7ms | Yes |
| **ISO datetime parsing** — parse `"2024-03-15T10:30:00Z"` into a map using only string ops | `{:year 2024 :month 3 :day 15 :hour 10 :minute 30 :second 0}` | 1 | 6ms | Yes |
| **Matrix multiplication** — implement from scratch, multiply 2x3 * 3x2 | `[[58 64] [139 154]]` | 2 | 14ms | Yes |
| **Graph traversal** — find all paths from `:a` to `:f` in a directed graph with cycle detection | 6 paths found | 1 | 6ms | Yes |

### Observations

- **Single-iteration solves** are common for well-scoped problems (data analysis, string parsing, graph traversal). The model writes correct Clojure on the first try and calls `final!` immediately.
- **Multi-iteration loops** happen when the model builds incrementally — defining helper functions, testing intermediate results, then composing the final answer. The REPL feedback loop lets it self-correct.
- **Custom tools work naturally.** The employee DB test injected a `query-db` function. The model called it like any other Clojure function, processed the results with standard library functions, and returned structured data.
- **Execution time is negligible** (6–19ms). Nearly all wall-clock time is spent waiting for the LLM API. The REPL evaluation itself is instant.
- **Even small models work.** The 9B parameter Qwen model solved the prime summation task correctly in 2 iterations. The 35B model handled all six tasks without failures.

## Running

```bash
# Run all tests (unit tests, no LLM calls)
clojure -M:test

# Start a REPL (with test classpath)
clojure -M:test
```

## Project structure

```
deps.edn                   — tools.deps config (one dep: data.json)
src/rlm/prompts.clj        — system prompt, iteration prompts, context metadata (~100 lines)
src/rlm/minimal.clj        — core runtime (~490 lines)
src/rlm/openrouter.clj     — OpenRouter LLM adapter (~60 lines)
test/rlm/minimal_test.clj  — unit test suite (~460 lines)
docs/                       — design spec and implementation plan
```

## Design philosophy

- **LLM as programmer, not tool-caller.** Instead of a fixed action schema, the LLM gets a full programming language. It can define abstractions, handle errors, compose results — whatever the task requires.
- **Inject, don't embed.** The runtime knows nothing about HTTP, API keys, or specific LLM providers. The `:llm-fn` and `:tools` options are the only extension points needed.
- **Namespace-per-session.** JVM namespaces provide natural isolation. Each session is a fresh environment with `clojure.core` referred. No global state leaks between sessions.
- **Recursive agents.** `rlm-query` enables agent-spawns-agent patterns with depth bounds. A root agent can delegate sub-problems without the caller managing child lifecycles.
- **Minimal surface.** One file, zero dependencies, six public functions. The entire runtime is readable in a single sitting.
