(ns event-data-evidence-service.core
  (:require [clojure.data.json :as json])
  (:require [config.core :refer [env]]
            [korma.core :as k]
            [korma.db :as kdb]
            [liberator.core :as l]
            [liberator.representation :as representation]
            [compojure.core :as c]
            [compojure.route :as r]
            [org.httpkit.server :as server]
            [ring.middleware.params :refer [wrap-params]]
            [clj-time.coerce :as coerce])
  (:gen-class))

(kdb/defdb db (kdb/mysql {:db (:db-name env)
                           :user (:db-user env)
                           :password (:db-password env)}))

; Database.

(declare current-artifacts)
(declare historical-artifacts)

(k/defentity artifact-names
  (k/table "artifact_name")
  (k/pk :id)
  (k/has-many current-artifacts)
  (k/has-many historical-artifacts)
  (k/entity-fields :id :name))

(k/defentity current-artifacts
  (k/table "current_artifact")
  (k/pk :id)
  (k/belongs-to artifact-names)
  (k/entity-fields :id ["artifact_name_id" :name-id] ["version_id" :version-id] :updated :link)
  (k/transform (fn [{updated :updated :as artifact}]
                 (assoc artifact :updated (str (coerce/from-sql-date updated))))))

(k/defentity historical-artifacts
  (k/table "historical_artifact")
  (k/pk :id)
  (k/belongs-to artifact-names)
  (k/entity-fields :id ["artifact_name_id" :name-id] ["version_id" :version-id] :updated :link)
  (k/transform (fn [{updated :updated :as artifact}]
                 (assoc artifact :updated (str (coerce/from-sql-date updated))))))

(k/defentity evidence
  (k/table "evidence")
  (k/pk :id)
  (k/entity-fields :id ["version_id" :version-id] :data :processed))

(k/defentity events
  (k/table "event")
  (k/pk :id)
  (k/entity-fields :id ["event_id" :event-id ] :data :processed))             

(k/defentity event-evidence
  (k/table "event_evidence")
  (k/pk :id)
  (k/entity-fields [:event-id "event_id"] ["evidence_id" :evidence-id]))

; Service.

(l/defresource artifacts-all
  []
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             (when-let [a (k/select artifact-names)]
               {::artifact a}))
  :handle-ok (fn [ctx]
              (let [names (map (fn [{n :name :as artifact-name}]
                                 {:name n
                                  :current (str (:service-base env) "/artifacts/" n "/current")
                                  :versions (str (:service-base env) "/artifacts/" n "/versions")}) (::artifact ctx))]
               (json/write-str {:type "artifact-name-list" :artifact-names names}))))

(l/defresource artifact-current
  [artifact-id]
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             (when-let [a (-> (k/select artifact-names (k/where {:name artifact-id}) (k/with current-artifacts)) first :historical_artifact)]
               {::artifact a}))
  :handle-ok (fn [ctx] 
               (prn ctx)
               (let [current-link (str (:service-base env) "/artifacts/" (-> ctx ::artifact :name) "/versions/" (-> ctx ::artifact :current_artifact first :version-id))]
                 (representation/ring-response
                   {:status 303
                    :headers {"Location" current-link}
                    :body (json/write-str {:link current-link})}))))

(l/defresource artifact-versions
  [artifact-id]
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             (when-let [as (-> (k/select artifact-names (k/where {:name artifact-id}) (k/with historical-artifacts)) first)]
               {::artifacts (:historical_artifact as) ::name (:name as)}))
  :handle-ok (fn [ctx]
               (let [versions (map (fn [artifact]
                                     {:updated (:updated artifact)
                                      :link (str (:service-base env) "/artifacts/" (::name ctx) "/versions/" (:version-id artifact))}) (::artifacts ctx))]
               (json/write-str {:type :artifact-version-list :artifact-versions versions}))))

(l/defresource artifact-version
  [artifact-id version-id]
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             (let [a-name (first (k/select artifact-names (k/where {:name artifact-id})))
                   a-version (when a-name (first (k/select historical-artifacts (k/where {:artifact_name_id (:id a-name) :version_id version-id}))))]
               {::artifact-version a-version}))
  :handle-ok (fn [ctx]
               (let [link (-> ctx ::artifact-version :link)]
                 (representation/ring-response
                     {:status 303
                      :headers {"Location" link}
                      :body (json/write-str {:link link})}))))

(c/defroutes routes
  (c/GET "/artifacts" [] (artifacts-all))           
  (c/GET "/artifacts/:artifact-id/current" [artifact-id] (artifact-current artifact-id))
  (c/GET "/artifacts/:artifact-id/versions" [artifact-id] (artifact-versions artifact-id))
  (c/GET "/artifacts/:artifact-id/versions/:version-id" [artifact-id version-id] (artifact-version artifact-id version-id)))

(def app
  (-> routes
      (wrap-params)))

(defn -main
  [& args]  
  (server/run-server app {:port (Integer/parseInt (:port env))}))
