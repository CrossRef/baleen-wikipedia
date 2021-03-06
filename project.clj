(defproject baleen-wikipedia "0.2.0"
  :description "Wikipedia Source for Crossref Event Data"
  :url "http://github.com/crossref/baleen-wikipedia"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
			     [org.clojure/tools.logging "0.3.1"]
			     [clj-time "0.8.0"]
  				 [org.apache.qpid/qpid-jms-client "0.8.0"]
  				 [overtone/at-at "1.2.0"]
  				 [gottox/socketio "0.1"]
  				 [org.clojure/data.json "0.2.6"]
           [org.clojure/core.async "0.2.371"]
  				 [crossref-util "0.1.7"]
  				 [environ "1.0.2"]
           [http-kit "2.1.18"]
           [enlive "1.1.6"]
           [ring "1.4.0"]
           [crossref/heartbeat "0.1.2"]
           [org.apache.qpid/qpid-jms-client "0.8.0"]
           [org.fusesource.stompjms/stompjms-client "1.19"]
           [liberator "0.13"]
           [selmer "1.0.3"]
           [compojure "1.5.0"]
           [org.jsoup/jsoup "1.8.3"]
           ]
  :plugins [[lein-localrepo "0.5.3"]
            [lein-environ "1.0.2"]
            [jonase/eastwood "0.2.3"]]
  :main baleen-wikipedia.main
  :aot [baleen-wikipedia.main]
  :java-source-paths ["src-java"]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :env {:push-endpoint "http://staging.api.eventdata.crossref.org/api/deposits"
        :subscribe-filter "*.wikipedia.org"
        :amqp-connection-uri "amqp://localhost:5672"
        :stomp-connection-uri "tcp://localhost:61613"
        :activemq-user "admin"
        :activemq-password "admin"
        :source-token "a147a49b-8ef1-4d2a-92b3-541ee7c87f2f"
        :auth-token ""
        :diff-threads "5"
        :development "false"
        :web-port "8080"
  })
