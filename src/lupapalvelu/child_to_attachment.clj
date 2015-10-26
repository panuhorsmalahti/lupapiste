(ns lupapalvelu.child-to-attachment
  (:require
    [lupapalvelu.attachment :as attachment]
    [lupapalvelu.pdf-export :as pdf-export]
    [lupapalvelu.i18n :refer [with-lang loc] :as i18n]
    [sade.core :refer [def- now]]
    [taoensso.timbre :as timbre :refer [trace tracef debug debugf info infof warn warnf error errorf fatal fatalf]]
    [clojure.pprint :refer [pprint]]
    [lupapalvelu.pdf-conversion :as pdf-conversion])
  (:import (java.io File FileOutputStream)))

(defn- get-child [application type id]
  (filter #(or (nil? id) (= id (:id %))) (type application)))

(defn- build-attachment [user application type lang id file]
  (let [is-pdf-a? (pdf-conversion/ensure-pdf-a-by-organization file (:organization application))
        type-name (case type
                    :statements (i18n/localize (name lang) "statement.lausunto")
                    :neighbors (i18n/localize (name lang) "application.MM.neighbors")
                    :verdicts (i18n/localize (name lang) "application.verdict.title"))
        child (get-child application type id)]
    {:application application
     :filename (case type
                 (str type-name ".pdf"))
     :size (.length file)
     :content file
     :attachment-id nil
     :attachment-type (case type
                        :neighbors {:type-group "ennakkoluvat_ja_lausunnot" :type-id "selvitys_naapurien_kuulemisesta"}
                        {:type-group "muut" :type-id "muu"})
     :op nil
     :comment-text (case type
                     :neighbors (get-in child [:owner :name])
                     type-name)
     :locked true
     :user user
     :created (now)
     :required false
     :valid-pdfa is-pdf-a?
     :missing-fonts []}))

(defn generate-attachment-from-children [user app lang child-type id]
  "Builds attachment and return attachment data as map"
  (debug "   generate-attachment-from-children lang=" (name lang) ", type=" (name child-type) ", id=" id ",org: " (:organization app) ", children: " (child-type app))
  (let [pdf-file (File/createTempFile (str "pdf-export-" (name lang) "-" (name child-type) "-") ".pdf")
        out (FileOutputStream. pdf-file)]
    (with-lang lang (pdf-export/generate-pdf-with-child app child-type out id))
    (build-attachment user app child-type lang id pdf-file)))

(defn create-attachment-from-children [user app child-type id lang]
  "Generates attachment from child and saves it"
  (let [child (generate-attachment-from-children user app lang child-type id)]
    (attachment/attach-file! child)))