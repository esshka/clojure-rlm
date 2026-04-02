(ns rlm.ollama
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]))

(def ^:private http-client
  (delay (HttpClient/newHttpClient)))

(defn chat-completion
  "Calls Ollama chat API. Returns the assistant message content string."
  [{:keys [messages model think]
    :or {model "qwen3.5:9b" think false}}]
  (let [body (json/write-str (cond-> {:model model
                                       :messages messages
                                       :stream false}
                               (not think) (assoc :options {:num_predict 4096}
                                                  :think false)))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "http://localhost:11434/api/chat"))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    .build)
        response (.send @http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        resp-body (json/read-str (.body response) :key-fn keyword)]
    (when (not= 200 status)
      (throw (ex-info (str "Ollama API error (HTTP " status ")")
                      {:status status :body resp-body})))
    (get-in resp-body [:message :content])))

(defn make-llm-fn
  "Returns an :llm-fn compatible with rlm.minimal/completion.
   Options: :default-model, :think (default false — disables Qwen thinking tokens)."
  ([] (make-llm-fn {}))
  ([{:keys [default-model think]
     :or {default-model "qwen3.5:9b" think false}}]
   (fn [{:keys [messages model]}]
     (chat-completion {:messages messages
                       :model (or model default-model)
                       :think think}))))
