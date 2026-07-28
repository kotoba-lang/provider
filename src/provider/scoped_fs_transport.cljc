(ns provider.scoped-fs-transport
  "Production root-mount store for `provider.scoped-fs` (ADR 0144).

  Does NOT define a new capability. Builds the store `(fn [op] -> reply)`
  host injects as `:store` into `provider.scoped-fs/provider`.

  ## No ambient filesystem authority

  There is **no default root** (not CWD, not home, not /tmp). The host must
  supply `:roots` as `{root-kw absolute-directory-path}`. Guest paths are
  resolved with `scoped-fs/resolve-path` then joined under the root; the
  canonical path must remain under the root's canonical directory
  (symlink escape is fail-closed).

  ## `:cljs`

  Documented gap for dual-runtime OS mounts (Node fs sync vs reference
  contract). Use `mem-store` on nbb until a dedicated design lands."
  (:require [clojure.string :as str]
            [provider.scoped-fs :as scoped-fs])
  #?(:clj
     (:import (java.io File)
              (java.nio.charset StandardCharsets)
              (java.nio.file Files LinkOption Path Paths))))

(defn under-root?
  "True when `canonical-child` is the root itself or a path strictly under
  `canonical-root` (prefix + separator). Pure string check on already-
  canonical absolute paths."
  [canonical-root canonical-child]
  (let [r (str canonical-root)
        c (str canonical-child)]
    (or (= r c)
        (and (str/starts-with? c r)
             (or (str/ends-with? r "/")
                 (str/starts-with? (subs c (count r)) "/"))))))

#?(:clj
   (defn- as-file ^File [p]
     (if (instance? File p) p (File. (str p)))))

#?(:clj
   (defn- canonical ^String [^File f]
     (.getCanonicalPath f)))

#?(:clj
   (defn- resolve-file
     "Map root + guest relative path to a File under root, or error map."
     [root-file relative]
     (let [r (scoped-fs/resolve-path relative)]
       (if-let [err (:error r)]
         {:error err}
         (let [root-c (canonical root-file)
               child (File. root-file ^String (:ok r))
               child-c (try (canonical child)
                            (catch Exception _
                              nil))]
           (cond
             (nil? child-c) {:error :fs/io}
             (not (under-root? root-c child-c)) {:error :fs/escape}
             :else {:file child :path (:ok r)}))))))

#?(:clj
   (defn os-store
     "Build a production filesystem store.

     opts:
       :roots  required map {qualified-keyword absolute-dir-path-or-File}

     Returns store fn compatible with `provider.scoped-fs` mem-store shape."
     [{:keys [roots]}]
     (when-not (and (map? roots) (seq roots)
                    (every? qualified-keyword? (keys roots)))
       (throw (ex-info "scoped-fs-transport requires non-empty :roots map"
                       {:phase :scoped-fs-transport})))
     (let [root-files
           (into {}
                 (map (fn [[k p]]
                        (let [f (as-file p)]
                          (when-not (.isAbsolute f)
                            (throw (ex-info "scoped-fs-transport roots must be absolute"
                                            {:phase :scoped-fs-transport :root k})))
                          (when-not (and (.exists f) (.isDirectory f))
                            (throw (ex-info "scoped-fs-transport root must be an existing directory"
                                            {:phase :scoped-fs-transport :root k
                                             :path (.getPath f)})))
                          [k f]))
                      roots))]
       (fn [{:keys [op root path value]}]
         (let [rf (get root-files root)]
           (if-not rf
             {:tag :error :code :fs/unknown-root :message "unknown root"}
             (let [resolved (resolve-file rf path)]
               (if-let [err (:error resolved)]
                 {:tag :error :code err :message (name err)}
                 (let [^File f (:file resolved)]
                   (case op
                     :read
                     (if-not (.isFile f)
                       {:tag :error :code :fs/not-found :message "not found"}
                       (try
                         (let [bytes (Files/readAllBytes (.toPath f))
                               s (String. ^bytes bytes StandardCharsets/UTF_8)]
                           (if (> (count (.getBytes s StandardCharsets/UTF_8))
                                  scoped-fs/max-value-bytes)
                             {:tag :error :code :fs/too-large :message "file too large"}
                             {:tag :content :value s}))
                         (catch Exception e
                           {:tag :error :code :fs/io
                            :message (or (.getMessage e) "read failed")})))

                     :write
                     (try
                       (let [parent (.getParentFile f)]
                         (when (and parent (not (.exists parent)))
                           (.mkdirs parent))
                         (Files/writeString (.toPath f) (str value)
                                            StandardCharsets/UTF_8
                                            (into-array
                                             java.nio.file.OpenOption
                                             [java.nio.file.StandardOpenOption/CREATE
                                              java.nio.file.StandardOpenOption/TRUNCATE_EXISTING
                                              java.nio.file.StandardOpenOption/WRITE]))
                         {:tag :written})
                       (catch Exception e
                         {:tag :error :code :fs/io
                          :message (or (.getMessage e) "write failed")}))

                     {:tag :error :code :fs/unknown-op :message "unknown op"}))))))))))

#?(:cljs
   (defn os-store
     "cljs gap — see ns docstring."
     [_opts]
     (throw (ex-info "scoped-fs-transport/os-store is JVM-only in ADR 0144"
                     {:phase :scoped-fs-transport}))))
