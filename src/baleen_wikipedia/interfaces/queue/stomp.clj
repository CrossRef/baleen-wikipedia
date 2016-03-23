(ns baleen-wikipedia.interfaces.queue.stomp
  (:require [environ.core :refer [env]])

  (:import [org.fusesource.stomp.jms StompJmsDestination StompJmsConnectionFactory]
           [javax.jms Session DeliveryMode TextMessage JMSException]))



(def user (env :activemq-user))
(def password (env :activemq-password))
(def connection-uri (env :stomp-connection-uri))

(def factory (new StompJmsConnectionFactory))
(.setBrokerURI factory connection-uri)

(def connection (.createConnection factory user password))

(.start connection)

; TODO transactions?
(def session (.createSession connection false Session/CLIENT_ACKNOWLEDGE))

(defn queue-send-f
  "Return a function that allows broadcast into the named queue."
  [queue-name]
  (let [destination (new StompJmsDestination (str "queue/" queue-name))
        producer (.createProducer session destination)]
    (fn [text]
      (.send producer (.createTextMessage session text)))))


(defn topic-send-f
  "Return a function that allows broadcast into the named topic."
  [queue-name]
  (let [destination (new StompJmsDestination (str "topic/" queue-name))
        producer (.createProducer session destination)]
    (fn [text]
      (.send producer (.createTextMessage session text)))))

(defn queue-listen-f
  [queue-name callback-f]
  (let [destination (new StompJmsDestination (str "queue/" queue-name))
        consumer (.createConsumer session destination)]
    (loop []
      ; Block this thread on wait.
      (let [message (.receive consumer)]
            (when (instance? TextMessage message)
              (callback-f (.getText message) message))
      (recur)))))