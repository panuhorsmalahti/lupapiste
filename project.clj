(defproject lupapalvelu "0.1.0-SNAPSHOT"
  :description "lupapalvelu"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [noir "1.3.0-beta10" :exclusions [org.clojure/clojure]]
                 [com.novemberain/monger "1.3.1"]
                 [enlive "1.0.1" :exclusions [org.clojure/clojure]]
                 [org.clojure/tools.nrepl "0.2.0-beta10"]
                 [org.mindrot/jbcrypt "0.3m"]
                 [digest "1.4.0"]
                 [clj-http "0.5.6"]
                 [clj-time "0.4.4"]
                 [org.clojure/data.xml "0.0.6"]
                 [fi.sito.oskari/oskari "0.1"]] 
  :profiles {:dev {:dependencies [[midje "1.4.0" :exclusions [org.clojure/clojure]]]
                   :plugins [[lein-midje "2.0.0"]
                             [lein-buildid "0.1.0-SNAPSHOT"]]}}
  :main lupapalvelu.server
  :repl-options {:init-ns lupapalvelu.server}
  :min-lein-version "2.0.0"
  :nitpicker {:exts ["clj" "js" "html"]
              :excludes [#"\/jquery\/" #"\/theme\/default\/" #"\/public\/lib\/"]})
