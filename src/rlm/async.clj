(ns rlm.async
  "Core.async utilities for bounded-parallel execution and per-call timeouts."
  (:require [clojure.core.async :as async]))

(defn call-with-timeout
  "Call f with an optional timeout (ms). Returns the result or throws on timeout.
   If timeout-ms is nil, calls f directly with no timeout."
  [f timeout-ms]
  (if-not timeout-ms
    (f)
    (let [result-ch (async/thread (f))
          [v ch] (async/alt!! result-ch ([v] [v :ok])
                               (async/timeout timeout-ms) [nil :timeout])]
      (if (= ch :timeout)
        (throw (ex-info "LLM call timed out."
                        {:timeout-ms timeout-ms}))
        v))))

(defn run-batched
  "Run f over items with bounded parallelism using a semaphore channel.
   Returns a vector of results in input order. Each call gets call-timeout-ms.
   Failed calls return \"Error: <message>\" strings."
  [f items max-concurrent call-timeout-ms]
  (if (empty? items)
    []
    (let [n (count items)
          results (object-array n)
          sem-ch (async/chan max-concurrent)
          done-ch (async/chan n)]
      (dotimes [_ max-concurrent]
        (async/>!! sem-ch :permit))
      (doseq [[idx item] (map-indexed vector items)]
        (async/thread
          (async/<!! sem-ch)
          (try
            (let [v (call-with-timeout #(f item) call-timeout-ms)]
              (aset results idx v))
            (catch Throwable t
              (aset results idx (str "Error: " (.getMessage t))))
            (finally
              (async/>!! sem-ch :permit)
              (async/>!! done-ch idx)))))
      (dotimes [_ n]
        (async/<!! done-ch))
      (vec results))))
