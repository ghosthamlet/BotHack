(defproject bothack "0.6.0-SNAPSHOT"
  :description "BotHack – A NetHack Bot Framework"
  :url "https://github.com/krajj7/BotHack"
  :license {:name "GPLv2"}
  :java-source-paths ["jta26/de/mud" "java"]
  :javac-options ["-target" "1.7" "-source" "1.7"]
  :plugins [[lein-javadoc "0.1.1"]]
  :javadoc-opts {:package-names ["bothack.actions"
                                 "bothack.bot"
                                 "bothack.events"
                                 "bothack.prompts"]
                 :additional-args ["-overview" "doc/overview.html"
                                   "-docencoding" "utf-8"
                                   "-noqualifier" "all"
                                   "-charset" "utf-8"
                                   "-windowtitle" "BotHack JavaDoc"
                                   "-encoding" "utf-8"
                                   "-notimestamp"]}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.priority-map "0.0.5"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/core.logic "0.8.8"]
                 [com.cemerick/pomegranate "0.3.0"]
                 [org.flatland/ordered "1.5.2"]
                 [org.clojars.achim/multiset "0.1.0-SNAPSHOT"]
                 [com.jcraft/jsch "0.1.42"]
                 [criterium "0.4.3"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]]
  ;:global-vars {*warn-on-reflection* true}
  :aot [bothack.delegator bothack.actions bothack.term
        bothack.ttyrec bothack.main]
  :main bothack.main)
