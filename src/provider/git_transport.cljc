(ns provider.git-transport
  "Production git run transport for `provider.git` (ADR 0148 / 0149).

  Does NOT define a new capability. Builds `(fn [request] -> reply)` for
  injection as `:run` into `provider.git/provider`.

  ## No ambient git authority

  - No PATH scan — host supplies absolute `:git-bin`
  - No ambient CWD — host supplies absolute `:worktree` directory
  - Subcommand policy remains in `provider.git/validate-run`

  ## Dual runtime

  - **`:clj`** — ProcessBuilder in worktree dir
  - **`:cljs`** — explicit gap (compose process-transport os-spawn later)"
  (:require [clojure.string :as str]
            [provider.git :as git]
            [provider.process-transport :as process-transport])
  #?(:clj
     (:import (java.io File)
              (java.util.concurrent TimeUnit))))

(defn echo-run
  "Alias of `provider.git/echo-transport` for transport ns symmetry."
  []
  (git/echo-transport))

#?(:clj
   (defn- read-stream
     [^java.io.InputStream in max-bytes]
     (let [buf (byte-array 4096)
           out (java.io.ByteArrayOutputStream.)]
       (loop [total 0]
         (let [n (.read in buf)]
           (cond
             (neg? n) (.toString out java.nio.charset.StandardCharsets/UTF_8)
             (>= total max-bytes) (.toString out java.nio.charset.StandardCharsets/UTF_8)
             :else
             (let [take (min n (- max-bytes total))]
               (.write out buf 0 take)
               (recur (+ total take)))))))))

#?(:clj
   (defn os-run
     "Build a production git `:run` transport (JVM).

     opts:
       :git-bin    required absolute path to git executable
       :worktree   required absolute directory containing .git (or worktree)
       :timeout-ms optional default timeout if request omits one

     Returns `(fn [{:keys [args max-stdout-bytes timeout-ms]}] -> reply)`."
     [{:keys [git-bin worktree timeout-ms] :as opts}]
     (when-not (and (string? git-bin)
                    (process-transport/absolute-path? git-bin)
                    (.canExecute (File. ^String git-bin)))
       (throw (ex-info "git-transport/os-run requires absolute executable :git-bin"
                       {:phase :git-transport})))
     (let [dir (if (instance? File worktree) worktree (File. (str worktree)))]
       (when-not (and (.isAbsolute dir) (.isDirectory dir))
         (throw (ex-info "git-transport/os-run requires absolute :worktree directory"
                         {:phase :git-transport})))
       (fn [{:keys [args max-stdout-bytes timeout-ms]
             :or {max-stdout-bytes git/max-stdout-bytes
                  timeout-ms (or timeout-ms 5000)}}]
         (try
           (let [pb (doto (ProcessBuilder. ^java.util.List (vec (cons git-bin (map str args))))
                      (.directory dir)
                      (.redirectErrorStream false))
                 proc (.start pb)
                 finished (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
             (if-not finished
               (do (.destroyForcibly proc)
                   {:tag :error
                    :code :git/timeout
                    :message (str "timeout after " timeout-ms "ms")})
               (let [out (read-stream (.getInputStream proc) (long max-stdout-bytes))
                     err (read-stream (.getErrorStream proc) (long max-stdout-bytes))
                     exit (.exitValue proc)]
                 {:tag :ok
                  :exit (long exit)
                  :stdout (str out)
                  :stderr (str err)})))
           (catch Exception e
             {:tag :error
              :code :git/run
              :message (or (.getMessage e) "git spawn failed")}))))))

#?(:cljs
   (defn os-run
     "cljs gap — compose process-transport/os-spawn under sync contract later."
     [_opts]
     (throw (ex-info "git-transport/os-run is JVM-only in ADR 0149"
                     {:phase :git-transport}))))
