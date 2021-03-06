(ns lupapalvelu.kopiolaitos-api
  (:require [sade.core :refer [ok fail]]
            [sade.util :as util]
            [lupapalvelu.action :refer [defquery defcommand update-application notify] :as action]
            [lupapalvelu.states :as states]
            [lupapalvelu.kopiolaitos :as kopiolaitos]))

(defcommand order-verdict-attachment-prints
  {:description "Orders prints of marked verdict attachments from copy institute.
                 If the command is run more than once, the already ordered attachment copies are ordered again."
   :parameters [:id :lang :attachmentsWithAmounts :orderInfo]
   :states     states/post-verdict-states
   :user-roles #{:authority}
   :input-validators [(partial action/non-blank-parameters [:lang])
                      (partial action/map-parameters-with-required-keys [:orderInfo]
                        [:address :ordererPhone :kuntalupatunnus :ordererEmail :ordererAddress :ordererOrganization :applicantName :propertyId :lupapisteId])
                      (partial action/vector-parameters-with-map-items-with-required-keys [:attachmentsWithAmounts] [:forPrinting :amount :versions])
                      (fn [{{attachments :attachmentsWithAmounts} :data}]
                        (when (some #(or
                                       (not (= true (:forPrinting %)))
                                       (nil? (util/->int (:amount %) nil))
                                       (empty? (:versions %)))
                                attachments)
                          (fail :error.kopiolaitos-print-order-invalid-parameters-content)))]}
  [command]
  (kopiolaitos/do-order-verdict-attachment-prints command)
  (ok))
