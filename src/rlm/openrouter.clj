(ns rlm.openrouter
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]))

(defn- load-env-file
  "Reads .env file from project root, returns a map of key=value pairs."
  [path]
  (try
    (->> (slurp path)
         str/split-lines
         (remove #(or (str/blank? %) (str/starts-with? % "#")))
         (map #(str/split % #"=" 2))
         (filter #(= 2 (count %)))
         (into {} (map (fn [[k v]] [(str/trim k) (str/trim v)]))))
    (catch Exception _ {})))

(defn- get-api-key []
  (or (System/getenv "OPENROUTER_API_KEY")
      (get (load-env-file ".env") "OPENROUTER_API_KEY")
      (throw (ex-info "OPENROUTER_API_KEY not found in env or .env file" {}))))

(def ^:private http-client
  (delay (HttpClient/newHttpClient)))

(defn chat-completion
  "Calls OpenRouter chat completions API. Returns the assistant message content string."
  [{:keys [messages model]
    :or {model "qwen/qwen3.5-9b"}}]
  (let [api-key (get-api-key)
        body (json/write-str {:model model
                              :messages messages})
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "https://openrouter.ai/api/v1/chat/completions"))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    .build)
        response (.send @http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        resp-body (json/read-str (.body response) :key-fn keyword)]
    (when (not= 200 status)
      (throw (ex-info (str "OpenRouter API error (HTTP " status ")")
                      {:status status
                       :body resp-body})))
    (get-in resp-body [:choices 0 :message :content])))

(defn make-llm-fn
  "Returns an :llm-fn compatible with rlm.minimal/completion.
   Optionally override the default model."
  ([] (make-llm-fn {}))
  ([{:keys [default-model]
     :or {default-model "qwen/qwen3.5-9b"}}]
   (fn [{:keys [messages model]}]
     (chat-completion {:messages messages
                       :model (or model default-model)}))))
