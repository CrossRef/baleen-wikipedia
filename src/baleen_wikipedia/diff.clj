(ns baleen-wikipedia.diff
    (:import  [java.util.logging Logger Level]
              [java.net URLEncoder]
              [java.util UUID])
    (:require [clj-time.core :as clj-time]
              [clj-time.coerce :as coerce]
              [environ.core :refer [env]]
              [org.httpkit.client :as http]
              [net.cgrand.enlive-html :as html])
    (:require [baleen-wikipedia.interfaces.queue.stomp :refer [queue-listen-f queue-send-f]]
              [baleen-wikipedia.util :as util])
    (:require [clojure.tools.logging :refer [error info debug]]
              [clojure.data.json :as json]
              [clojure.set :refer [difference]]
              [clojure.core.async :refer [chan buffer go-loop <! >!!]]))


; Three outgoing queues: "publish-diffs" go to the webserver, "push-diffs" go to the Lagotto pusher, "store-diffs" get stored.
; Content identical.
(def publish-diffs-send-f (atom nil))
(def push-diffs-send-f (atom nil))
(def store-diffs-send-f (atom nil))

(def num-threads (Integer/parseInt (or (env :diff-threads) "1")))

(defn build-restbase-url
  "Build a URL that can be retrieved from RESTBase"
  [server-name title revision]
  (str "https://" server-name "/api/rest_v1/page/html/" (URLEncoder/encode title) "/" revision))

(defn fetch-canonical-url [server-name title]
  (let [fetch-url (str "https://" server-name "/w/index.php?" (#'http/query-string {:title title}))
        req (http/get fetch-url {:timeout 200})
        body (-> fetch-url http/get deref :body)
        links (when body (html/select (html/html-snippet body) [:link]))
        canonical (filter #(= (-> % :attrs :rel) "canonical") links)
        hrefs (keep #(-> % :attrs :href) canonical)]
    (first hrefs)))


(defn dois-from-body
  "Fetch the set of DOIs that are mentioned in the body text.
  Also flag up the presence of DOIs in text that aren't linked."
  [body]
  ; As some character encoding inconsistencies crop up from time to time in the live stream, this reduces the chance of error. 
  (let [hrefs (util/extract-a-hrefs-from-html body)

        dois (set (keep util/is-doi-url? hrefs))

        ; Get the body text i.e. only visible stuff, not a href links.
        body-text (util/text-fragments-from-html body)

        ; Remove the DOIs that we already found.
        body-text-without-dois (util/remove-all body-text dois)

        ; As we only want to flag the existence of non-linked DOIs for later investigation,
        ; we only need to capture the prefix.
        unlinked-doi-prefixes (re-seq #"10\.\d\d\d+/" body-text-without-dois)

        num-unlinked-dois (count unlinked-doi-prefixes)]
    [dois num-unlinked-dois]))



(defn process-bodies [{old-revision :old-revision old-body :old-body new-revision :new-revision new-body :new-body title :title server-name :server-name input-event-id :input-event-id timestamp :timestamp}]
  (prn "process bodies")
  (let [[old-dois _] (dois-from-body old-body)
        [new-dois num-unlinked-dois] (dois-from-body new-body)
        added-dois (difference new-dois old-dois)
        removed-dois (difference old-dois new-dois)
        url (fetch-canonical-url server-name title)
        timestamp-iso (coerce/to-string timestamp)

        ]
    
    (prn "AD" added-dois)
    ; Broadcast and fan-out to queues.
    (doseq [doi added-dois]
      (let [event-id (.toString (UUID/randomUUID))
            payload (json/write-str {:old-revision old-revision
                                     :new-revision new-revision
                                     :input-event-id input-event-id
                                     :doi doi
                                     :title title
                                     :url url
                                     :server-name server-name
                                     :action "add"
                                     :event-id event-id
                                     :timestamp timestamp-iso})]
        (@publish-diffs-send-f payload)
        (@push-diffs-send-f payload)
        (@store-diffs-send-f payload)))

    (prn "RD" removed-dois)
    (doseq [doi removed-dois]
      (let [event-id (.toString (UUID/randomUUID))
            payload (json/write-str {:old-revision old-revision
                                     :new-revision new-revision
                                     :input-event-id input-event-id
                                     :doi doi
                                     :title title
                                     :url url
                                     :server-name server-name
                                     :action "remove"
                                     :event-id event-id
                                     :timestamp timestamp-iso})]
        (@publish-diffs-send-f payload)
        (@push-diffs-send-f payload)
        (@store-diffs-send-f payload))))

  (prn "DONE process-bodies")
)


(defn process
  "Process a new input event by looking up old and new revisions."
  ; Implemented using callbacks which are passed through http-kit. 
  ; Previous implementation using futures that block on promises from http-kit pegged all CPUs at 100%. 
  ; Previous implementation using channel and fixed number of workers that block on promises from http-kit don't scale to load (resource starvation).

  ; Chaining has the effect of retrieving the first revision first (which is more likely to exist in RESTBase) giving a bit more time before the new one is fetched.
  [data-str message]
  (let [data (json/read-str data-str)
        server-name (get data "server_name")
        server-url (get data "server_url")
        title (get data "title")
        old-revision (get-in data ["revision" "old"])
        new-revision (get-in data ["revision" "new"])
        input-event-id (get data "input-event-id")
        
        {old-status :status old-body :body} @(http/get (build-restbase-url server-name title old-revision))
        {new-status :status new-body :body} @(http/get (build-restbase-url server-name title new-revision))

        timestamp (coerce/from-long (* 1000 (get data "timestamp")))
        ]

        (prn "Status" old-status new-status)
        (when (and (= 200 old-status)) (= 200 new-status)
          ; Put a channel in here for disconnect.
          ; Without this there's a memory leak with large buffers being retained.
          (process-bodies {:old-revision old-revision :old-body old-body
                           :new-revision new-revision :new-body new-body
                           :title title
                           :server-name server-name
                           :input-event-id input-event-id
                           :timestamp timestamp})))
  (.acknowledge message))



        


(defn run []
  (reset! publish-diffs-send-f (queue-send-f "publish-diffs"))
  (reset! push-diffs-send-f (queue-send-f "push-diffs"))
  (reset! store-diffs-send-f (queue-send-f "store-diffs"))

  (prn "run")
  (let [threads (map (fn [_] (Thread. (fn [] (queue-listen-f "change" process)))) (range 0 num-threads))]
    (doseq [thread threads]
      (prn "Start" thread)
      (.start thread))

    ; These should never exit, but stop the process if they do.
    (doseq [thread threads]
      (prn "Join" thread)
      (.join thread))))
