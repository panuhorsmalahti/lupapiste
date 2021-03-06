(defproject lupapalvelu "0.1.0-SNAPSHOT"
  :description "lupapalvelu"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/tools.nrepl "0.2.6"]
                 [org.clojure/tools.trace "0.7.8"]
                 [org.clojure/test.check "0.9.0"]
                 [commons-fileupload "1.3.1"] ; The latest version - ring requires 1.3
                 [ring "1.4.0"]
                 [noir "1.3.0" :exclusions [compojure clj-stacktrace org.clojure/tools.macro ring hiccup bultitude]]
                 [bultitude "0.2.6"] ; noir requires 0.2.0, midje 1.6 requires 0.2.2
                 [compojure "1.1.9" :exclusions [org.clojure/tools.macro]]
                 [com.novemberain/monger "3.0.0"]
                 [com.taoensso/timbre "4.0.2"]
                 [org.slf4j/slf4j-log4j12 "1.7.7"]
                 [enlive "1.1.5"]
                 [org.jasypt/jasypt "1.9.2"]
                 [org.mindrot/jbcrypt "0.3m"]
                 [crypto-random "1.2.0" :exclusions [commons-codec]]
                 [cheshire "5.5.0"]
                 [clj-http "2.0.0" :exclusions [commons-codec]]
                 [camel-snake-kebab "0.1.2"]
                 [org.bouncycastle/bcprov-jdk15on "1.46"]
                 [pandect "0.3.0" :exclusions [org.bouncycastle/bcprov-jdk15on]]
                 [clj-time "0.9.0"]
                 [org.apache.commons/commons-lang3 "3.3.2"] ; Already a dependency but required explicitly
                 [commons-io/commons-io "2.4"]
                 [commons-codec/commons-codec "1.10"]
                 [itext "4.2.1" :exclusions [org.bouncycastle/bctsp-jdk14 org.apache.xmlgraphics/batik-gvt]]
                 [net.java.dev.jai-imageio/jai-imageio-core-standalone "1.2-pre-dr-b04-2014-09-13"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [endophile "0.1.2" :exclusions [hiccup]]
                 [com.draines/postal "1.11.1" :exclusions [commons-codec/commons-codec]]
                 [swiss-arrows "1.0.0"]
                 [me.raynes/fs "1.4.6" :exclusions [org.apache.commons/commons-compress]] ; later version required by pantomime -> tika
                 [ontodev/excel "0.2.3" :exclusions [xml-apis org.apache.poi/poi-ooxml]]
                 [org.apache.poi/poi-ooxml "3.11"]
                 [com.googlecode.htmlcompressor/htmlcompressor "1.5.2"]
                 [com.yahoo.platform.yui/yuicompressor "2.4.8" :exclusions [rhino/js org.mozilla/rhino]] ; http://jira.xwiki.org/browse/XWIKI-6148?focusedCommentId=59523#comment-59523
                 [slingshot "0.12.2"]
                 [com.google.zxing/javase "2.2"]
                 [prismatic/schema "1.0.4"]
                 [cljts "0.3.0-20150228.035522-2" :exclusions [xerces/xercesImpl]]
                 ; batik-js includes a built-in rhino, which breaks yuicompressor (it too has rhino built in)
                 ; xalan excluded just to avoid bloat, presumably XSLT is not needed
                 [clj-pdf "1.11.21" :exclusions [xalan org.apache.xmlgraphics/batik-js]]
                 [org.freemarker/freemarker  "2.3.22"]
                 [fr.opensagres.xdocreport/fr.opensagres.xdocreport.converter.docx.xwpf  "1.0.5"]
                 [fr.opensagres.xdocreport/fr.opensagres.xdocreport.itext.extension  "1.0.5"]
                 [fr.opensagres.xdocreport/fr.opensagres.xdocreport.document.docx  "1.0.5"]
                 [fr.opensagres.xdocreport/fr.opensagres.xdocreport.template.freemarker "1.0.5" :exclusions [org.freemarker/freemarker]]
                 [org.clojure/core.memoize "0.5.7"]
                 [org.apache.pdfbox/pdfbox "1.8.9" :exclusions [commons-logging]]
                 [com.levigo.jbig2/levigo-jbig2-imageio "1.6.3"]
                 [org.geotools/gt-main "12.4"]
                 [org.geotools/gt-shapefile "12.4"]
                 [org.geotools/gt-geojson "12.4"]
                 [org.geotools/gt-referencing "12.4"]
                 [org.geotools/gt-epsg-wkt "12.4"]
                 [org.clojure/data.json "0.2.6"]
                 [com.novemberain/pantomime "2.8.0" :exclusions [org.opengis/geoapi org.bouncycastle/bcprov-jdk15on]]
                 [lupapiste/oskari "0.9.55"]
                 [lupapiste/commons "0.5.39"]
                 [pdfa-core "0.0.3"]]
  :profiles {:dev {:dependencies [[midje "1.7.0" :exclusions [org.clojure/tools.namespace]]
                                  [ring-mock "0.1.5"]
                                  [clj-ssh "0.5.7"]
                                  [rhizome "0.2.5"]
                                  [pdfboxing "0.1.5"]]
                   :plugins [[lein-midje "3.1.1"]
                             [jonase/eastwood "0.2.1" :exclusions [[org.clojure/tools.namespace] org.clojure/clojure]]
                             [lupapiste/lein-buildid "0.4.0"]
                             [lupapiste/lein-nitpicker "0.5.1"]]
                   :resource-paths ["dev-resources"]
                   :source-paths ["dev-src" "test-utils"]
                   :jvm-opts ["-Djava.awt.headless=true" "-Xmx2G" "-Dfile.encoding=UTF-8"]
                   :eastwood {:continue-on-exception true
                              :source-paths ["src"]
                              :test-paths []}}
             :uberjar  {:main lupapalvelu.main
                        :jar-exclusions [#"gems/.*"]
                        :uberjar-exclusions [#"gems/.*"]}
             :itest    {:test-paths ^:replace ["itest"]}
             :stest    {:test-paths ^:replace ["stest"]}
             :alltests {:source-paths ["test" "itest" "stest"]
                        :jvm-opts ["-Djava.awt.headless=true" "-Xmx1G"]}
             :lupadev  {:jvm-opts ["-Dtarget_server=https://www-dev.lupapiste.fi" "-Djava.awt.headless=true"]}
             :lupatest {:jvm-opts ["-Dtarget_server=https://www-test.lupapiste.fi" "-Djava.awt.headless=true"]}}
  :nitpicker {:exts ["clj" "js" "html"]
              :excludes [#"jquery" #"underscore" #"terms\.html" #"\/email-templates\/" #"proj4" #".debug"]}
  :repositories [["solita-archiva" {:url "http://mvn.solita.fi/repository/solita"
                                    :checksum :ignore}]
                 ["mygrid-repository" {:url "http://www.mygrid.org.uk/maven/repository"
                                       :snapshots false}]
                 ["osgeo" {:url "http://download.osgeo.org/webdav/geotools"}]
                 ["com.levigo.jbig2" {:url "http://jbig2-imageio.googlecode.com/svn/maven-repository"
                                      :snapshots false}]]
  :plugin-repositories [["solita-archiva" {:url "http://mvn.solita.fi/repository/solita"
                                           :checksum :ignore}]]
  :aliases {"integration" ["with-profile" "dev,itest" "midje"]
            "stest"       ["with-profile" "dev,stest" "midje"]
            "verify"      ["with-profile" "dev,alltests" "do" "nitpicker," "midje"]}
  :aot [lupapalvelu.main clj-time.core]
  :main ^:skip-aot lupapalvelu.server
  :repl-options {:init-ns lupapalvelu.server}
  :pom-plugins [[org.fusesource.mvnplugins/maven-graph-plugin "1.4"]
                [com.googlecode.maven-overview-plugin/maven-overview-plugin "1.6"]]
  :min-lein-version "2.0.0")
