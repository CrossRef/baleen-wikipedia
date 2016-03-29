(ns baleen-wikipedia.serve
  (:require [environ.core :refer [env]])

  (:require [baleen-wikipedia.interfaces.queue.stomp :refer [topic-listen-f queue-listen-f]]
            [baleen-wikipedia.util :as util])

  (:require [clj-time.core :as t]
            [clj-time.coerce :as coerce]
            [clj-time.format :as f])
  (:require [compojure.core :refer [context defroutes GET ANY POST]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [overtone.at-at :as at-at])
  (:require [ring.util.response :refer [redirect]])
  (:require [liberator.core :refer [defresource resource]]
            [liberator.representation :refer [ring-response]])
  (:require [selmer.parser :refer [render-file cache-off!]]
            [selmer.filters :refer [add-filter!]])
  (:require [clojure.data.json :as json])
  (:require [crossref.util.doi :as crdoi]
            [crossref.util.config :refer [config]])
  (:require [clojure.walk :refer [prewalk]]
            [clojure.tools.logging :refer [error info]])
  (:require [clojure.core.async :as async :refer [<! <!! >!! >! go chan]])
  (:require [org.httpkit.server :refer [with-channel on-close on-receive send! run-server]]
            [org.httpkit.client :as httpkit-client]
            [org.httpkit.server :as httpkit-server])
  (:require [heartbeat.core :refer [def-service-check]]
            [heartbeat.ring :refer [wrap-heartbeat]]))

; (def-service-check :mysql (fn [] (db/heartbeat)))
; (def-service-check :events (fn [] (events/ok)))

(when (Boolean/parseBoolean (env :development))
  (selmer.parser/cache-off!))

; Channels for websockets.
(defonce broadcast-channels (atom #{}))

; Datetime of most recent things to happen.
(defonce most-recent-input (atom nil))
(defonce most-recent-citation (atom nil))

; n-second buckets to count historical number of events per period.
(defonce input-count-buckets (atom (list)))
(defonce processed-count-buckets (atom (list)))
(defonce citation-count-buckets (atom (list)))

; list of citation events to show.
(defonce citation-history (atom (list)))

(defonce server (atom nil))

; Scheduling pool.
(defonce at-at-pool (delay (at-at/mk-pool)))

(defn- shift-bucket [buckets]
  ; Conj will create a lazy seq and blow up the stack eventually. Realize the bucket list each time.
  (apply list (conj (drop-last buckets) 0)))

(defn- inc-bucket [buckets]
  (conj (rest buckets) (inc (first buckets))))

(defn- push-bucket [buckets item]
  (apply list (conj (drop-last buckets) item)))


(def vocab {:title "Wikipedia DOI citation live stream"
            :input-count-label "Wikipedia edits"
            :citation-count-label "DOI citation events"})

; Just serve up a blank page with JavaScript to pick up from event-types-socket.
(defresource home
  []
  :available-media-types ["text/html"] 
  :handle-ok (fn [ctx]
               (render-file "templates/home.html" {:vocab vocab})))

(defn register-listener
  "Register a websocket listener."
  [listener-chan]
  (info "Register websocket listener" (str listener-chan) "now" (inc (count broadcast-channels)))
  (swap! broadcast-channels conj listener-chan))

(defn unregister-listener
  "Unregister a websocket listener."
  [listener-chan]
  (info "Unregister websocket listener" (str listener-chan) "now" (dec (count broadcast-channels)))
  (swap! broadcast-channels disj listener-chan))


(defn event-websocket
  [request]
   (with-channel request output-channel
    (register-listener output-channel)
    
    (on-close output-channel
              (fn [status]
                (unregister-listener output-channel)))
    (on-receive output-channel
              (fn [data]))))

(defresource status
  []
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
                  (json/write-str {:subscribers (count @broadcast-channels)
                                   :most-recent-input (when-let [x @most-recent-input] (str x))
                                   :most-recent-citation (when-let [x @most-recent-citation] (str x))
                                   :input-history @input-count-buckets
                                   :processed-history @processed-count-buckets
                                   :citation-history @citation-count-buckets
                                   :recent-events (> (apply + (take 10 @input-count-buckets)) 0)})))



(defn export [{old-revision :old-revision 
               new-revision :new-revision 
               input-event-id :input-event-id 
               doi :doi 
               title :title 
               url :url 
               server-name :server-name 
               action :action 
               event-id :event-id 
               timestamp :timestamp }]

  (let [pretty-url (str server-name "/wiki/" title)
        action-url (str "https://" server-name "/w/index.php?" (#'httpkit-client/query-string {:title title :type "revision" :oldid old-revision :diff new-revision}))]
    {:id event-id
     :input-container-title (util/server-name server-name)
     :date timestamp  
     :doi doi
     :title title
     :url url
     :pretty-url pretty-url
     :action-url action-url
     :action action}))

(defn- publish-diffs-handler [text message]
  (let [data (json/read-str text :key-fn keyword)]
    (let [exported (export data)]
      (swap! citation-history #(push-bucket %1 exported))
      (doseq [c @broadcast-channels]
        (httpkit-server/send! c (json/write-str exported))))))


(defresource events
  []
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
    ; Buckets initially nil. Don't send those.
    (let [events (remove nil? @citation-history)]
      (json/write-str {:events events}))))

(defroutes app-routes
  (GET "/" [] (home))
  (GET "/status" [] (status))
  (GET ["/socket/events"] [] event-websocket)
  (GET ["/events"] [] (events))
  (route/resources "/"))

(def app
  (-> app-routes
      handler/site
      (wrap-heartbeat)))

(def num-input-buckets 100)
(def num-processed-count-buckets 100)
(def num-citation-count-buckets 100)
(def num-citation-history 20)

(defn start-background []
  (reset! input-count-buckets (apply list (repeat num-input-buckets 0)))
  (reset! processed-count-buckets (apply list (repeat num-processed-count-buckets 0)))
  (reset! citation-count-buckets (apply list (repeat num-citation-count-buckets 0)))
  (reset! citation-history (apply list (repeat num-citation-history nil)))

  (at-at/every 5000 #(swap! input-count-buckets shift-bucket) @at-at-pool)
  (at-at/every 5000 #(swap! processed-count-buckets shift-bucket) @at-at-pool)
  (at-at/every 300000 #(swap! citation-count-buckets shift-bucket) @at-at-pool)

  (.start
    (Thread.
      (fn [] (queue-listen-f "publish-diffs" publish-diffs-handler))))

  ; Monitor the citations
  (.start
    (Thread.
      (fn [] (topic-listen-f "heartbeat"
        (fn [payload message] 
          (condp = payload
            "change" (do (swap! input-count-buckets inc-bucket) (reset! most-recent-input (t/now)))
            "diff" (swap! processed-count-buckets inc-bucket)
            "citation" (do (swap! citation-count-buckets inc-bucket) (reset! most-recent-citation (t/now)))
            nil)))))))

(defn run []
  (start-background)
  (reset! server (run-server #'app {:port (Integer/parseInt (env :web-port))})))
