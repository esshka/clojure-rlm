# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run all tests
clojure -M:test

# Run a single test (by name)
clojure -M:test -v rlm.minimal-test/completion-returns-42

# Start a REPL
clojure -M:test   # includes test path
clojure            # production classpath only

# Lint (clj-kondo is configured)
clj-kondo --lint src test
```

No build step required — this is a plain `tools.deps` project with zero external dependencies.

## Architecture

This is **RLM** (REPL-backed Language Model) — a Clojure agent runtime that gives an LLM a live REPL. The LLM emits fenced `clj` code blocks, the runtime evaluates them, feeds results back, and loops until the LLM calls `(final! x)` or iterations are exhausted.

### Single-namespace design

All production code lives in `src/rlm/minimal.clj`. All tests live in `test/rlm/minimal_test.clj`. This is intentional for v1 — a module split into `rlm.session`, `rlm.exec`, `rlm.core` is planned but deferred.

### Session lifecycle

`start-session` creates an isolated Clojure namespace (`rlm.session.<uuid>`) with its own atom-backed state. Into that namespace it interns:

- `context` — the original input
- `history` — conversation/execution log (vector, kept in sync with atom)
- `final!` — signals completion and stores the answer
- `show-vars` — lists user-defined vars (excludes reserved names and `-` prefixed)
- `llm-query` — single-turn LLM call
- `rlm-query` — recursive: spawns a child session until `max-depth`, then falls back to `llm-query`
- Custom tools from the `:tools` map

`close-session!` removes the generated namespace. It is idempotent and guards against removing unrelated namespaces (ns in atom must match session ns).

### Completion loop

`completion` drives the agent loop:
1. Calls `:llm-fn` with system + user messages + history
2. Extracts ` ```clj ` / ` ```clojure ` fenced blocks from the response
3. Executes each block via `exec-code!` (wraps in `(do ...)`, captures stdout/stderr/error/timing)
4. Appends assistant message + execution summaries to history
5. Stops on `final!` or when `max-iterations` is reached
6. If no code blocks and no `final!`, returns the raw assistant text

### Key design decisions

- **`:llm-fn` is injected** — the runtime has no built-in LLM client. Callers provide a `(fn [{:keys [messages model depth prompt]}] ...)` that returns a string.
- **`:final-set?` flag** — tracks whether `final!` was called, so `(final! nil)` and `(final! false)` correctly terminate the loop.
- **Tool name validation** — custom tools cannot shadow reserved names or `clojure.core` publics. Keyword/symbol tool keys are canonicalized to symbols.
- **`deep-stringify-keys`** — keyword keys in context maps are converted to strings before sending to the LLM.
- **`mock-llm`** — included as a test adapter; recognizes "Return 42" in the last user message and emits a code block that calls `final!`.

### Test runner

Tests use `requiring-resolve` to load vars, so a missing function produces a clear error message instead of a compile-time failure. The `-main` entry point calls `System/exit` with a non-zero code on failures (used by `clojure -M:test`).
