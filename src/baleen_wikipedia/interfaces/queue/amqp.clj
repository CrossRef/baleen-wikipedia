(ns baleen-wikipedia.interfaces.queue.amqp
  (:require [environ.core :refer [env]])

  (:import [org.apache.qpid.jms JmsConnectionFactory  ]
           [javax.jms Session DeliveryMode TextMessage]))



(def user (env :activemq-user))
(def password (env :activemq-password))
(def connection-uri (env :amqp-connection-uri))

(def factory (new JmsConnectionFactory connection-uri))
(def connection (.createConnection factory user password))

(defn- create-session
  []
  (let [session (.createSession connection false Session/CLIENT_ACKNOWLEDGE)]
    (.start connection)
    connection))

(defn queue-send-f
  "Return a function that allows broadcast into the named queue."
  [queue-name]
  (let [session (create-session)
        destination (.createQueue session queue-name)
        producer (.createProducer session destination)]
    (fn [text]
      (.send producer (.createTextMessage session text)))))


(defn topic-send-f
  "Return a function that allows broadcast into the named topic."
  [queue-name]
  (let [session (create-session)
        destination (.createTopic session queue-name)
        producer (.createProducer session destination)]
    (fn [text]
      (.send producer (.createTextMessage session text)))))

(defn queue-listen-f
  [queue-name callback-f]
  (let [session (create-session)
        destination (.createQueue session queue-name)
        consumer (.createConsumer session destination)]
    (loop []
      ; Block this thread on wait.
      (let [message (.receive consumer)]
            (when (instance? TextMessage message)
              (callback-f (.getText message) message))
      (recur)))))