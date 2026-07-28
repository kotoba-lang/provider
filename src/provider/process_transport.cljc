(ns provider.process-transport
  "Production OS spawn transport for `provider.process` (ADR 0144).

  Does NOT define a new capability. Builds the `(fn [request] -> reply)`
  host injects as `:spawn` into `provider.process/provider`.

  ## No ambient process authority

  There is **no default PATH scan**. The host must supply an explicit
  `:binaries` map of `{basename absolute-path}` for every command it
  intends to allow. Basename validation remains in `provider.process`;
  this transport only runs binaries the host named.

  ## Bounds

  - stdout/stderr captured up to `:max-stdout-bytes` (truncate rest)
  - wall timeout via destroyForcibly after `:timeout-ms`
  - never inherits ambient shell; argv is the full command vector

  ## `:cljs`

  Documented gap for true dual-runtime OS spawn (async child_process vs
  sync provider contract). Use `echo-transport` or a host-specific
  spawnSync adapter on nbb until a dedicated design lands."
  (:require [clojure.string :as str]
            [provider.process :as process])
  #?(:clj
     (:import (java.io ByteArrayOutputStream InputStream)
              (java.nio.charset StandardCharsets)
              (java.util.concurrent TimeUnit))))

(defn resolve-binary
  "Look up `basename` in host-supplied `binaries` map. Returns absolute
  path string or nil. Pure map lookup — never searches PATH."
  [binaries basename]
  (when (and (map? binaries) (string? basename))
    (let [p (get binaries basename)]
      (when (and (string? p) (not (str/blank? p)))
        p))))

#?(:clj
   (defn- read-bounded
     "Drain `in` into a string of at most `max-bytes` UTF-8 bytes."
     [^InputStream in max-bytes]
     (let [buf (byte-array 4096)
           out (ByteArrayOutputStream.)]
       (loop [total 0]
         (let [n (.read in buf)]
           (cond
             (neg? n) (.toString out StandardCharsets/UTF_8)
             (>= total max-bytes) (.toString out StandardCharsets/UTF_8)
             :else
             (let [take (min n (- max-bytes total))]
               (.write out buf 0 take)
               (recur (+ total take)))))))))

#?(:clj
   (defn os-spawn
     "Build a production spawn transport.

     opts:
       :binaries  required map {\"echo\" \"/bin/echo\", ...}
                  absolute paths only; no ambient PATH.

     Returns `(fn [{:keys [argv max-stdout-bytes timeout-ms]}] -> reply)`
     where reply is `{:tag :ok :exit :stdout :stderr}` or
     `{:tag :error :code :message}`."
     [{:keys [binaries] :as opts}]
     (when-not (and (map? binaries) (seq binaries)
                    (every? string? (keys binaries))
                    (every? string? (vals binaries)))
       (throw (ex-info "process-transport requires non-empty :binaries map"
                       {:phase :process-transport})))
     (doseq [[_ p] binaries]
       (when (or (str/blank? p)
                 (not (.isAbsolute (java.io.File. ^String p))))
         (throw (ex-info "process-transport binary paths must be absolute"
                         {:phase :process-transport :path p}))))
     (fn [{:keys [argv max-stdout-bytes timeout-ms]
           :or {max-stdout-bytes process/max-stdout-bytes
                timeout-ms 5000}}]
       (let [cmd (first argv)
             bin (resolve-binary binaries cmd)]
         (cond
           (nil? bin)
           {:tag :error
            :code :process/no-binary
            :message (str "no host binary for " cmd)}

           :else
           (try
             (let [pb (doto (ProcessBuilder. ^java.util.List (vec (cons bin (rest argv))))
                        (.redirectErrorStream false))
                   proc (.start pb)
                   finished (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
               (if-not finished
                 (do
                   (.destroyForcibly proc)
                   {:tag :error
                    :code :process/timeout
                    :message (str "timeout after " timeout-ms "ms")})
                 (let [out (read-bounded (.getInputStream proc) (long max-stdout-bytes))
                       err (read-bounded (.getErrorStream proc) (long max-stdout-bytes))
                       exit (.exitValue proc)]
                   {:tag :ok
                    :exit (long exit)
                    :stdout (str out)
                    :stderr (str err)})))
             (catch Exception e
               {:tag :error
                :code :process/spawn
                :message (or (.getMessage e) "spawn failed")})))))))

#?(:cljs
   (defn os-spawn
     "cljs gap — see ns docstring. Throws if called."
     [_opts]
     (throw (ex-info "process-transport/os-spawn is JVM-only in ADR 0144"
                     {:phase :process-transport}))))
