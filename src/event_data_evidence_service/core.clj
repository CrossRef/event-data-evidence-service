(ns event-data-evidence-service.core
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log])
  (:require [config.core :refer [env]]
            [korma.core :as k]
            [korma.db :as kdb]
            [liberator.core :as l]
            [liberator.representation :as representation]
            [compojure.core :as c]
            [compojure.route :as r]
            [org.httpkit.server :as server]
            [org.httpkit.client :as client]
            [ring.middleware.params :refer [wrap-params]]
            [clj-time.coerce :as coerce]
            [overtone.at-at :as at-at]
            [robert.bruce :refer [try-try-again]])
  (:import [java.security MessageDigest])
  (:import [com.amazonaws.services.s3 AmazonS3 AmazonS3Client]
           [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.services.s3.model GetObjectRequest PutObjectRequest ObjectMetadata]
           [com.amazonaws AmazonServiceException AmazonClientException])
  (:gen-class))

(def evidence-status-not-uploaded 1)
(def evidence-status-uploaded 2)
(def evidence-status-upload-failed 3)

(defn send-heartbeat [heartbeat-name heartbeat-count]
  (try 
    (try-try-again {:sleep 10000 :tries 10}
       #(let [result @(client/post (str (:status-service-base env) (str "/status/" heartbeat-name))
                       {:headers {"Content-type" "text/plain" "Authorization" (str "Token " (:status-service-auth-token env))}
                        :body (str heartbeat-count)})]
         (when-not (= (:status result) 201)
           (log/error "Can't send heartbeat, status" (:status result)))))
   (catch Exception e (log/error "Can't send heartbeat, exception:" e))))


; Auth
; Tokens are comma-separated in the config.
(def auth-tokens (delay (set (.split (:auth-tokens env) ","))))

(defn md5 [s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        size (* 2 (.getDigestLength algorithm))
        raw (.digest algorithm (.getBytes s))
        sig (.toString (BigInteger. 1 raw) 16)
        padding (apply str (repeat (- size (count sig)) "0"))]
    (str padding sig)))

; AWS
(defn get-aws-client
  []
  (new AmazonS3Client (new BasicAWSCredentials (:s3-access-key-id env) (:s3-secret-access-key env))))

(defn upload-bytes
  "Upload a stream. Exception on failure."
  [bytes bucket-name remote-name content-type]
  (let [aws-client (get-aws-client)
        metadata (new ObjectMetadata)
        _ (.setContentType metadata content-type)
        _ (.setContentLength metadata (alength bytes))
        request (new PutObjectRequest bucket-name remote-name (new java.io.ByteArrayInputStream bytes) metadata)]
        (.putObject aws-client request)))

; Database.

(kdb/defdb db (kdb/mysql {:db (:db-name env)
                          :host (:db-host env) 
                          :port (Integer/parseInt (:db-port env))
                          :user (:db-user env)
                          :password (:db-password env)}))

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
  ; :data field also present, but not incldued as Korma doesn't allow subselection of fields.
  (k/entity-fields :id :evidence_id :processed_events :deposit_status))

(k/defentity events
  (k/table "event")
  (k/pk :id)
  (k/entity-fields :id :event_id))

(k/defentity event-evidence
  (k/table "event_evidence")
  (k/pk :id)
  (k/entity-fields :event_id :evidence_id))

; Service.

(l/defresource artifacts-all
  []
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             (when-let [a (k/select artifact-names)]
               {::artifact a}))
  :handle-ok (fn [ctx]
              (send-heartbeat "evidence-service/api/get-list-all" 1)
              (let [names (map (fn [{n :name :as artifact-name}]
                                 {:name n
                                  :current (str (:service-base env) "/artifacts/" n "/current")
                                  :versions (str (:service-base env) "/artifacts/" n "/versions")}) (::artifact ctx))]
               (json/write-str {:type "artifact-name-list" :artifact-names names}))))

(l/defresource artifact-current
  [artifact-id]
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             (when-let [a (-> (k/select artifact-names (k/where {:name artifact-id}) (k/with current-artifacts)) first)]
               {::artifact-name a}))
  :handle-ok (fn [ctx]
               (send-heartbeat "evidence-service/api/get-artifact-current" 1)
               (let [current-link (str (:service-base env) "/artifacts/" (-> ctx ::artifact-name :name) "/versions/" (-> ctx ::artifact-name :current_artifact first :version-id))]
                 (representation/ring-response
                   {:status 303
                    :headers {"Location" current-link}
                    :body (json/write-str {:link current-link})}))))

(l/defresource artifact-versions
  [artifact-id]
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             (send-heartbeat "evidence-service/api/get-artifact-versions" 1)
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
               (send-heartbeat "evidence-service/api/get-artifact-version" 1)
               (let [link (-> ctx ::artifact-version :link)]
                 (representation/ring-response
                     {:status 303
                      :headers {"Location" link}
                      :body (json/write-str {:link link})}))))

(l/defresource post-evidence
 []
 :allowed-methods [:post]
 :available-media-types ["text/plain"]
 :authorized? (fn [ctx]
                (try
                  (@auth-tokens
                    (nth (re-find #"Token (.*)" (get-in ctx [:request :headers "authorization"])) 1))
                  ; NPE if not found.
                  (catch NullPointerException _ false)))
 :malformed? (fn [ctx]
               (let [body (slurp (get-in ctx [:request :body]))
                     data (json/read-str body :key-fn keyword)]
                 [false {::input-body body ::data data}]))
 :handle-ok "ok"
 :post! (fn [ctx]
          (send-heartbeat "evidence-service/api/receive-evidence" 1)
          ; We have two 'ids' here, the integer PK id and the event-id (GUID) and evidence-id (content hash).
          (let [content-hash (md5 (::input-body ctx))
                deposits (:deposits (::data ctx))]
            
            (if-not (try 
                      (upload-bytes (.getBytes (::input-body ctx)) (:archive-bucket env) (str "evidence/" content-hash) "application/javascript")
                      true
                      (catch AmazonClientException ex 
                        (do
                          (log/error "Failed to upload Evidence" content-hash ex)
                          false))
                      (catch AmazonServiceException ex
                        (do
                          (log/error "Failed to upload Evidence" content-hash ex)
                          false)))
              false
              
              ; Upload ok!
              (do
                ; Idempotent POST for same data.
                (k/exec-raw ["INSERT IGNORE INTO evidence (evidence_id, data, deposit_status) VALUES (?,?,?)" [content-hash (::input-body ctx) evidence-status-not-uploaded]])
                            
                (log/info "Accept Evidence id" content-hash)
                (if-let [evidence-id (-> (k/select evidence (k/where {:evidence_id content-hash})) first :id)]
                  ; Got an Evidence record. Record the Events and links.
                  (let [deposit-results (map (fn [deposit]
                                               (log/info "Save Deposit ID " (:uuid deposit))
                                               (k/exec-raw ["INSERT IGNORE INTO event (event_id) VALUES (?)" [(:uuid deposit)]])
                                                 (if-let [event-id (-> (k/select events (k/where {:event_id (:uuid deposit)})) first :id)]
                                                   ; Got an event.
                                                   (do
                                                     (k/exec-raw ["INSERT IGNORE INTO event_evidence (event_id, evidence_id) VALUES (?,?)" [event-id evidence-id]])
                                                     (if-not (-> (k/select event-evidence (k/where {:evidence_id evidence-id :event_id event-id})) first)
                                                       (log/error "Failed to link Evidence" content-hash "to" (:uuid deposit) "using evidence-id" evidence-id ", event-id" event-id)
                                                       true))
                                                   ; Not got an event.
                                                   (log/error "Failed to create Event id " (:uuid deposit)))) deposits)]
                  
                    ; Might be empty due to no Deposits.
                    (if-not (every? true? deposit-results)
                      (log/error "Errors inserting event records " content-hash)
                      (if-not (= 1 (k/update evidence (k/set-fields {:processed_events true}) (k/where {:id evidence-id})))
                         (log/error "Failed to update Evidence")
                          ; Return true if everything worked for the Event and the link.
                         [true {::evidence-id content-hash}])))
                  
                  ; Not got an evidence record.
                  (log/error "Couldn't insert Evidence record " content-hash))))))
     
 :post-redirect? true
 :handle-see-other (fn [ctx]
                     (if-let [evidence-id (::evidence-id ctx)]
                       (representation/ring-response
                         {:status 303
                          :headers {"Location" (str (:service-base env) "/evidence/" evidence-id)}
                          :body (json/write-str {:message "Created"})})
                       
                       (representation/ring-response
                         {:status 500
                          :body (json/write-str {:error "Failed to process evidence"})}))))

(l/defresource get-event-evidence
  [external-event-id]
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             (let [evidence-id (->
                                 (k/select evidence
                                   (k/join :event_evidence (= :evidence.id :event_evidence.evidence_id))
                                   (k/join events (= :event_evidence.event_id :event.id))
                                   (k/where {:event.event_id external-event-id }))
                                 first
                                 :evidence_id)]
                [evidence-id {::evidence-id evidence-id}]))
  
  :handle-ok (fn [ctx]
               (send-heartbeat "evidence-service/api/get-event-evidence" 1)
               (let [link (str (:archive-base-url env) "/evidence/" (-> ctx ::evidence-id))]
                 (representation/ring-response
                     {:status 303
                      :headers {"Location" link}
                      :body (json/write-str {:link link})}))))

(l/defresource get-evidence
  [external-evidence-id]
  :available-media-types ["application/javascript"]
  :exists? (fn [ctx]
             ; Check it's in the DB.
             (let [evidence-id (->
                                 (k/select evidence (k/where {:evidence_id external-evidence-id}))
                                 first
                                 :evidence_id)]
                [evidence-id {::evidence-id evidence-id}]))
  
  :handle-ok (fn [ctx]
               (send-heartbeat "evidence-service/api/get-evidence" 1)
               (let [link (str (:archive-base-url env) "/evidence/" (-> ctx ::evidence-id))]
                 (representation/ring-response
                     {:status 303
                      :headers {"Location" link}
                      :body (json/write-str {:link link})}))))

(c/defroutes routes
  (c/GET "/artifacts" [] (artifacts-all))           
  (c/GET "/artifacts/:artifact-id/current" [artifact-id] (artifact-current artifact-id))
  (c/GET "/artifacts/:artifact-id/versions" [artifact-id] (artifact-versions artifact-id))
  (c/GET "/artifacts/:artifact-id/versions/:version-id" [artifact-id version-id] (artifact-version artifact-id version-id))
  (c/POST "/evidence" [] (post-evidence))
  (c/GET "/evidence/:evidence-id" [evidence-id] (get-evidence evidence-id))
  (c/GET "/events/:event-id/evidence" [event-id] (get-event-evidence event-id)))

; Background

(def schedule-pool (at-at/mk-pool))

(defn start-heartbeat []
  (log/info "Start heartbeat schedule")
  (at-at/every 60000 #(send-heartbeat "evidence-service/server/heartbeat" 1)
               schedule-pool))

(defn try-lagotto-upload
  "Upload to Lagotto, return success."
  [deposit]
  (send-heartbeat "evidence-service/deposits/send-deposit" 1)
  (let [payload (json/write-str deposit)]
    (let [endpoint (str (:lagotto-service-base env) "/api/deposits")
          result (try-try-again {:sleep 10000 :tries 10}
                                #(deref (client/post endpoint {:headers {"Authorization" (str "Token token=" (:lagotto-service-auth-token env)) "Content-Type" "application/json"} :body payload})))
            ok? (= (:status result) 202)]
      (log/info "Upload status" (:status result) "ok?" ok?)
      
      (when-not ok?
        (log/error "Failed upload " (str result)))
      
      (if ok?
        (send-heartbeat "evidence-service/deposits/send-deposit-ok" 1)
        (send-heartbeat "evidence-service/deposits/send-deposit-fail" 1))
      ok?)))

(defn start-lagotto-uploads []
  (log/info "Start Lagotto upload schedule")
  (at-at/every 60000 (fn []
                       (try
                         (log/info "Tick upload")
                         (let [evidence-items (k/select evidence (k/where {:deposit_status evidence-status-not-uploaded}) (k/fields [:data]))]
                           (doseq [evidence-item evidence-items]
                             (send-heartbeat "evidence-service/deposits/send-evidence" 1)
                             (let [data (json/read-str (:data evidence-item) :key-fn keyword)
                                   deposits (:deposits data)
                                   deposit-results (map try-lagotto-upload deposits)
                                   all-success (every? true? deposit-results)]
                               (log/info "Upload Evidence Item " (:evidence_id evidence-item) "to Lagotto." (count deposits) "deposits.")
                               (if all-success
                                 (do
                                   (log/info "Succeeded uploading Deposits.")
                                   (send-heartbeat "evidence-service/deposits/send-evidence-success" 1))
                                 (do
                                   (log/info "Failed to upload Deposits.")
                                   (send-heartbeat "evidence-service/deposits/send-evidence-failure" 1)))
                               (if all-success
                                 (k/update evidence (k/where {:id (:id evidence-item)}) (k/set-fields {:deposit_status evidence-status-uploaded}))
                                 (k/update evidence (k/where {:id (:id evidence-item)}) (k/set-fields {:deposit_status evidence-status-upload-failed}))))))
                        (catch Exception e (log/error "Error in Lagotto upload " (str e)))))
               schedule-pool :fixed-delay true))

(def app
  (-> routes
      (wrap-params)))

(defn -main
  [& args]
  ; Every instance has a heartbeat.
  (start-heartbeat)
  
  ; Nominated instances upload to Lagotto. Only one at once.
  ; Also useful for development.
  (when (:send-deposits-to-lagotto env)
    (start-lagotto-uploads))
  
  (server/run-server app {:port (Integer/parseInt (:port env))}))
