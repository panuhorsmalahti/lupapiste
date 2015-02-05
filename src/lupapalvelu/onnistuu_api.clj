(ns lupapalvelu.onnistuu-api
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error errorf fatal]]
            [noir.core :refer [defpage]]
            [noir.response :as resp]
            [schema.core :as sc]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [hiccup.form :as form]
            [slingshot.slingshot :refer [try+]]
            [sade.env :as env]
            [sade.util :refer [y? max-length-string valid-email?]]
            [sade.core :refer [ok fail fail! now]]
            [sade.strings :as ss]
            [lupapalvelu.action :refer [defquery defcommand]]
            [lupapalvelu.onnistuu.process :as p]
            [lupapalvelu.company :as c]
            [lupapalvelu.user :as u]
            [lupapalvelu.i18n :as i18n]))

;;
;; Onnistuu.fi integration: Web API
;;

;
; Workflow:
;   - user decides to sign contract
;   - front -> init-sign command
;       * create uniq sign process ID
;       * save sign data to db with process ID as key
;       * sign process state is :created
;       * return a HTML form with sign data in hidden fields
;

(defcommand init-sign
  {:parameters [company signer lang]
   :feature :companyRegistration
   :roles [:anonymous]}
  [{:keys [created]}]
  (sc/validate c/Company company)
  (sc/validate p/Signer signer)
  (if-not ((set (map name i18n/languages)) lang) (fail! :bad-lang))
  (if (u/get-user-by-email (:email signer)) (fail! :email-in-use))
  (let [config       (env/value :onnistuu)
        base-url     (or (env/value :onnistuu :return-base-url) (env/value :host))
        document-url (str base-url "/api/sign/document")
        success-url  (str base-url "/api/sign/success")
        process-data (p/init-sign-process (java.util.Date. created) (:crypto-key config) success-url document-url company signer lang)]
    (ok :processId (:process-id process-data)
        :form (html
                (form/form-to [:post (:post-to config)]
                              (form/hidden-field "customer"        (:customer-id config))
                              (form/hidden-field "data"            (:data process-data))
                              (form/hidden-field "iv"              (:iv process-data))
                              (form/hidden-field "return_failure"  (str base-url "/api/sign/fail/" (:process-id process-data)))
                              (form/submit-button ""))))))

; Cancel signing:

(defcommand cancel-sign
  {:parameters [processId]
   :feature :companyRegistration
   :roles [:anonymous]}
  [{:keys [created]}]
  (p/cancel-sign-process! processId created)
  (ok))

;
; Error handling util:
;

(defmacro with-error-handling [& body]
  `(try+
     ~@body
     (catch map? {error# :error}
       (resp/status (get {:not-found 404 :bad-request 400} error# 500)
                    (name error#)))))

;
; Workflow:
;  - Onnistuu.fi gets the document to sign:
;      * onnistuu.fi -> GET "/api/sign/document/" + process ID
;

(defpage "/api/sign/document/:id" {:keys [id]}
  (with-error-handling
    (let [[content-type document] (p/fetch-document id (now))]
      (->> document
           (resp/status 200)
           (resp/content-type content-type)))))

;
; Workflow:
;  - User signs document in onnistuu.fi, and gets a redirect back
;    to here:
;       browser -> GET "/api/sign/done/id?data=...&iv=..."
;         * mark process done, save status
;         * redirect to proper lupapiste.fi url
;

(defpage "/api/sign/success/:id" {:keys [id data iv]}
  (with-error-handling
    (let [process (p/success id data iv (now))
          lang    (-> process :lang)]
      (resp/redirect (str (env/value :host) "/app/" lang "/welcome#!/register-company-success")))))

;
; Something went terribly wrong!
;

(defpage "/api/sign/fail/:id" {:keys [id error message]}
  (with-error-handling
    (let [process (p/failed! id error message (now))
          lang    (-> process :lang)]
      (resp/redirect (str (env/value :host) "/app/" lang "/welcome#!/register-company-fail")))))

(when (env/feature? :dummy-onnistuu)

  (defquery find-sign-process
    {:parameters [processId]
     :roles [:anonymous]}
    [_]
    (ok :process (p/find-sign-process! processId)))

  ;
  ; Load dummy onnistuu.fi simulator:
  ;

  (require 'lupapalvelu.onnistuu.dummy-server))