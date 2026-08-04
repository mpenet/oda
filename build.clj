(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.tools.build.tasks.process :as p]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.s-exp/oda)
(def version (format "1.0.0-alpha%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
;; Jar contents are staged separately from `class-dir`: target/classes
;; is on the dev/test/bench classpaths, and copying src/clj into it
;; leaves stale .clj snapshots that shadow the live sources.
(def jar-class-dir "target/jar-classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def target-dir "target")

(defn clean [_]
  (b/delete {:path "target"}))

(defn javac [_]
  (b/javac {:src-dirs ["java"]
            :class-dir class-dir
            :basis @basis
            :javac-opts ["--release" "25"
                         "--add-modules" "jdk.incubator.vector"
                         "-Xlint:all"
                         "-Werror"]}))

(defn jar [_]
  (javac nil)
  (b/delete {:path jar-class-dir})
  (b/copy-dir {:src-dirs [class-dir]
               :target-dir jar-class-dir})
  (b/write-pom {:class-dir jar-class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data [[:description "Fast JSON parser/writer for Clojure."]
                           [:url "https://github.com/mpenet/oda"]
                           [:licenses
                            [:license
                             [:name "Mozilla Public License 2.0"]
                             [:url "https://www.mozilla.org/en-US/MPL/2.0/"]]]
                           [:scm
                            [:url "https://github.com/mpenet/oda"]
                            [:connection "scm:git:git://github.com/mpenet/oda.git"]
                            [:developerConnection "scm:git:ssh://git@github.com/mpenet/oda.git"]]]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir jar-class-dir})
  (b/jar {:class-dir jar-class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir jar-class-dir}))

(defn deploy
  [opts]
  (dd/deploy {:artifact jar-file
              :pom-file (format "%s/META-INF/maven/%s/pom.xml"
                                jar-class-dir
                                lib)
              :installer :remote
              :sign-releases? false})
  opts)

(defn- sh
  [& cmds]
  (doseq [cmd cmds]
    (p/process {:command-args ["sh" "-c" cmd]})))

(defn tag
  [opts]
  (sh
   (format "git tag -a \"%s\" --no-sign -m \"Release %s\"" version version)
   "git pull"
   "git push --follow-tags")
  opts)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn release
  [opts]
  (-> opts
      clean
      jar
      deploy
      tag))
