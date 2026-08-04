(ns build
  (:require [clojure.tools.build.api :as b]))

(defn javac [_]
  (b/javac {:src-dirs ["java"]
            :class-dir "classes"
            :basis (b/create-basis {:project "deps.edn"})
            :javac-opts ["--release" "25" "--add-modules" "jdk.incubator.vector"]}))

(defn clean [_]
  (b/delete {:path "classes"}))
