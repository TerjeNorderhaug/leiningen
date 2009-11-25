(ns leiningen.pom
  "Write a pom.xml file to disk for Maven interop."
  (:require [lancet])
  (:use [clojure.contrib.duck-streams :only [reader copy]]
        [clojure.contrib.java-utils :only [file as-properties]])
  (:import [java.io StringWriter]
           [org.apache.maven.model Model Parent Dependency Repository Scm]
           [org.apache.maven.project MavenProject]
           [org.apache.maven.artifact.ant Pom]))

(def #^{:doc "A notice to place at the bottom of generated files."} disclaimer
     "\n<!-- This file was autogenerated by the Leiningen build tool.
  Please do not edit it directly; instead edit project.clj and regenerate it.
  It should not be considered canonical data. For more information see
  http://github.com/technomancy/leiningen -->\n")

(defn read-git-ref
  "Reads the commit SHA1 for a git ref path."
  [git-dir ref-path]
  (.trim (slurp (str (file git-dir ref-path)))))

(defn read-git-head
  "Reads the value of HEAD and returns a commit SHA1."
  [git-dir]
  (let [head (.trim (slurp (str (file git-dir "HEAD"))))]
    (if-let [ref-path (second (re-find #"ref: (\S+)" head))]
      (read-git-ref git-dir ref-path)
      head)))

(defn read-git-origin
  "Reads the URL for the remote origin repository."
  [git-dir]
  (with-open [rdr (reader (file git-dir "config"))]
    (->> (map #(.trim %) (line-seq rdr))
         (drop-while #(not= "[remote \"origin\"]" %))
         (next)
         (take-while #(not (.startsWith % "[")))
         (map #(re-matches #"url\s*=\s*(\S*)\s*" %))
         (filter identity)
         (first)
         (second))))

(defn parse-github-url
  "Parses a GitHub URL returning a [username repo] pair."
  [url]
  (when url
    (next
     (or
      (re-matches #"(?:git@)?github.com:([^/]+)/([^/]+).git" url)
      (re-matches #"[^:]+://(?:git@)?github.com/([^/]+)/([^/]+).git" url)))))

(defn github-urls [url]
  (when-let [[user repo] (parse-github-url url)]
    {:public-clone (str "git://github.com/" user "/" repo ".git")
     :dev-clone (str "ssh://git@github.com/" user "/" repo ".git")
     :browse (str "http://github.com/" user "/" repo)}))

(defn make-git-scm [git-dir]
  (try
   (let [origin (read-git-origin git-dir)
         head (read-git-head git-dir)
         urls (github-urls origin)
         scm (Scm.)]
     (.setUrl scm (:browse urls))
     (.setTag scm head)
     (when (:public-clone urls)
       (.setConnection scm (str "scm:git:" (:public-clone urls))))
     (when (:dev-clone urls)
       (.setDeveloperConnection scm (str "scm:git:" (:dev-clone urls))))
     scm)
   (catch java.io.FileNotFoundException e
     nil)))

(defn make-dependency [[dep version]]
  (doto (Dependency.)
    (.setGroupId (or (namespace dep) (name dep)))
    (.setArtifactId (name dep))
    (.setVersion version)))

(defn make-repository [[id url]]
  (doto (Repository.)
    (.setId id)
    (.setUrl url)))

(def default-repos {"central" "http://repo1.maven.org/maven2"
                    "clojure-snapshots" "http://build.clojure.org/snapshots"
                    "clojars" "http://clojars.org/repo/"})

(defn make-model [project]
  (let [model (doto (Model.)
                (.setModelVersion "4.0.0")
                (.setArtifactId (:name project))
                (.setName (:name project))
                (.setVersion (:version project))
                (.setGroupId (:group project))
                (.setDescription (:description project)))]
    ;; TODO: add leiningen as a test-scoped dependency
    (doseq [dep (:dependencies project)]
      (.addDependency model (make-dependency dep)))
    (doseq [repo (concat (:repositories project) default-repos)]
      (.addRepository model (make-repository repo)))
    (when-let [scm (make-git-scm (file (:root project) ".git"))]
      (.setScm model scm))
    model))

(defn make-pom
  ([project] (make-pom project false))
  ([project disclaimer?]
     (with-open [w (StringWriter.)]
       (.writeModel (MavenProject. (make-model project)) w)
       (when disclaimer?
         (.write w disclaimer))
       (.getBytes (str w)))))

(defn make-pom-properties [project]
  (with-open [w (StringWriter.)]
    (.store (as-properties {:version (:version project)
                            :groupId (:group project)
                            :artifactId (:name project)})
            w "Leiningen")
    (.getBytes (str w))))

(defn pom [project & [pom-location silently?]]
  (let [pom-file (file (:root project) (or pom-location "pom.xml"))]
    (copy (make-pom project true) pom-file)
    (when-not silently? (println "Wrote" (.getName pom-file)))
    (.getAbsolutePath pom-file)))
