(ns baleen-wikipedia.ingest
    (:import [baleen RCStreamLegacyClient]
             [java.util.logging Logger Level]
             [java.util UUID])
    (:require [overtone.at-at :as at-at]
              [clj-time.core :as clj-time]
              [environ.core :refer [env]])
    (:require [baleen-wikipedia.interfaces.queue.stomp :refer [queue-send-f]])
    (:require [clojure.tools.logging :refer [error info debug]]
              [clojure.data.json :as json]))

(defonce at-at-pool (at-at/mk-pool))

(def send-f (atom nil))

(defn- process-message [data-str]
  (try
  (let [data (json/read-str data-str)
        event-type (get data "type")
        input-event-uuid (.toString (UUID/randomUUID))
        new-payload (json/write-str (assoc data "input-event-id" input-event-uuid))]
        (info "Process " input-event-uuid)
      (when (= event-type "edit")
        (@send-f new-payload)))
  (catch Exception e (prn e))))

(defn- callback [type-name args]
  ; Delay event to give a chance for Wikimedia servers to propagage edit.
  (let [arg (first args)
        arg-str (.toString arg)]
    ; Wait 10 seconds.
    (at-at/after (* 1000 10)
      #(process-message arg-str)
      at-at-pool)))

(defn- error-callback []
  ; Just panic. Whole process will be restarted.
  ; EX_IOERR
  (System/exit 74))

(defn- new-client []
  (let [subscribe-to (env :subscribe-filter)
        the-client (new RCStreamLegacyClient callback subscribe-to error-callback)]
    (.run the-client)))
 
(defn run
  "Run the ingestion, blocking. If the RCStreamLegacyClient dies, exit."
  []
    (info "Connect wikimedia...")
    ; The logger is mega-chatty (~50 messages per second at INFO). We have alternative ways of seeing what's going on.
    (info "Start wikimedia...")
    (.setLevel (Logger/getLogger "io.socket") Level/OFF)
    (info "Wikimedia running.")

    (reset! send-f (queue-send-f "change"))
    
    ; No error handling - crash the process if there's a failure.  
    (new-client))
    