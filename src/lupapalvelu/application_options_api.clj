(ns lupapalvelu.application-options-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error errorf]]
            [monger.operators :refer :all]
            [sade.core :refer :all]
            [lupapalvelu.action :refer [defcommand update-application] :as action]
            [lupapalvelu.application :as a]))

(defcommand set-municipality-hears-neighbors
  {:parameters [:id enabled]
   :user-roles #{:applicant :authority}
   :states     #{:draft :open}
   :input-validators [(partial action/boolean-parameters [:enabled])]
   :pre-checks       [a/validate-authority-in-drafts]}
  [command]
  (update-application command {$set {"options.municipalityHearsNeighbors" enabled}})
  (ok))
