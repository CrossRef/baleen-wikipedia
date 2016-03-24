(ns baleen-wikipedia.push
  (:import  [java.util.logging Logger Level])
  (:require [baleen-wikipedia.interfaces.queue.stomp :refer [queue-listen-f]])
  (:require [clojure.tools.logging :refer [error info]]
            [clojure.data.json :as json])
  (:require [org.httpkit.client :as http-client]
            [environ.core :refer [env]]
            [crossref.util.doi :as cr-doi]))


(defn send-triple
  [payload-str message]
  (prn "SEND TRIPLE" payload-str)
  (let [payload (json/read-str payload-str)

        old-revision (get payload "old-revision")
        new-revision (get payload "new-revision")
        input-event-id (get payload "input-event-id")
        ; TODO NORMALIZE
        doi (get payload "doi")
        title (get payload "title")
        url (get payload "url")
        server-name (get payload "server-name")
        action (get payload "action")
        event-id (get payload "event-id")
        timestamp (get payload "timestamp")

        endpoint (env :push-endpoint)
        source-token (env :source-token)
        auth-token (env :auth-token)

        subject_metadata {"pid" url
                          ; "author" nil,
                          "title" title
                          "container-title" "Wikipedia",
                          "issued" timestamp,
                          "URL" url
                          "type" "entry-encyclopedia",
                          "tracked" false,
                          "registration_agency" "wikipedia" }

        payload (condp = action
          ; No message action when adding, only deleting.
          "add" {:deposit {:uuid event-id
                           :source_token source-token
                           :subj_id url
                           :obj_id (cr-doi/normalise-doi doi)
                           :relation_type_id "references"
                           :source_id "wikipedia"
                           :subj subject_metadata}}
          "remove" {:deposit {:uuid event-id
                              :message_action "delete"
                              :source_token source-token
                              :subj_id url
                              :obj_id (cr-doi/normalise-doi doi)
                              :relation_type_id "references"
                              :source_id "wikipedia"
                              :subj subject_metadata}}
          nil)]
        (prn "PAYLOAD" payload)
    (if payload
      ; Block on reply otherwise we ack messages out of order.
      @(http-client/request 
        {:url endpoint
         :method :post
         :headers {"Authorization" (str "Token token=" auth-token) "Content-Type" "application/json"}
         :body (json/write-str payload)}
        (fn [response]
          (prn "RESPONSE" response)
          (.acknowledge message)))
      (.acknowledge message))
    

    ))




(defn run []
  (queue-listen-f "push-diffs" send-triple))
