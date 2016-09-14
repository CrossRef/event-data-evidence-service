(defproject event-data-evidence-service "0.1.3"
  :description "Event Data Evidence Service"
  :url "http://eventdata.crossref.org"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-time "0.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [http-kit "2.1.18"]
                 [org.clojure/java.jdbc "0.4.2"]
                 [korma "0.4.0"]
                 [ring "1.5.0"]
                 [mysql-java "5.1.21"]
                 [robert/bruce "0.8.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [compojure "1.5.1"]                 
                 [liberator "0.14.1"]
                 [yogthos/config "0.8"]
                 [javax/javaee-api "7.0"]
                 [overtone/at-at "1.2.0"]
                 [com.amazonaws/aws-java-sdk "1.11.6"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.apache.logging.log4j/log4j-core "2.6.2"]
                 [org.slf4j/slf4j-simple "1.7.21"]]
  :main ^:skip-aot event-data-evidence-service.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :prod {:resource-paths ["config/prod"]}
             :dev  {:resource-paths ["config/dev"]}})
