(ns provider.git-transport
  "Git run transports for `provider.git` (ADR 0148).

  Does NOT define a new capability. Builds `(fn [op] -> reply)` for
  injection as `:run`.

  ## No ambient git authority

  - No default `git` on PATH
  - No ambient CWD — host supplies absolute worktree paths
  - Read-only: status + log only (no commit/push/reset)

  ## Sources

  ### `mem-run`
  Test double (delegates to `provider.git/mem-run`).

  ### `os-run` (JVM)
  Spawns host-mapped `git` binary via `process-transport/os-spawn` under
  each worktree directory (`ProcessBuilder.directory`)."
  (:require [clojure.string :as str]
            [provider.git :as git]
            [provider.process :as process]
            [provider.process-transport :as process-transport])
  #?(:clj
     (:import (java.io File)
              (java.util.concurrent TimeUnit))))

(defn mem-run
  "Host-sealed mem table — see `provider.git/mem-run`."
  [db]
  (git/mem-run db))

#?(:clj
   (defn- absolute-dir?
     [^File f]
     (and (.isAbsolute f) (.isDirectory f))))

#?(:clj
   (defn- run-git
     "Spawn absolute git binary in worktree dir; return {:exit :out :err}."
     [git-bin ^File dir argv timeout-ms max-out]
     (try
       (let [pb (doto (ProcessBuilder. ^java.util.List (vec (cons git-bin argv)))
                  (.directory dir)
                  (.redirectErrorStream false))
             proc (.start pb)
             finished (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
         (if-not finished
           (do (.destroyForcibly proc)
               {:tag :error :code :git/timeout :message "git timeout"})
           (let [out (slurp (.getInputStream proc))
                 err (slurp (.getErrorStream proc))
                 exit (.exitValue proc)
                 out' (if (> (count out) max-out) (subs out 0 max-out) out)
                 err' (if (> (count err) max-out) (subs err 0 max-out) err)]
             {:tag :ok :exit exit :stdout out' :stderr err'})))
       (catch Exception e
         {:tag :error :code :git/run :message (or (.getMessage e) "git spawn failed")}))))

#?(:clj
   (defn os-run
     "Production read-only git transport (JVM).

     opts:
       :git-bin     absolute path to git (required; no PATH)
       :worktrees   {qualified-kw absolute-dir}
       :timeout-ms  default 5000
       :max-out     default process/max-stdout-bytes

     Returns `(fn [{:keys [op worktree n]}] -> reply)`."
     [{:keys [git-bin worktrees timeout-ms max-out]
       :or {timeout-ms 5000
            max-out process/max-stdout-bytes}}]
     (when-not (and (string? git-bin)
                    (process-transport/absolute-path? git-bin)
                    (.canExecute (File. ^String git-bin)))
       (throw (ex-info "git-transport/os-run requires absolute executable :git-bin"
                       {:phase :git-transport})))
     (when-not (and (map? worktrees) (seq worktrees)
                    (every? qualified-keyword? (keys worktrees)))
       (throw (ex-info "git-transport/os-run requires :worktrees map"
                       {:phase :git-transport})))
     (let [dirs
           (into {}
                 (map (fn [[k p]]
                        (let [f (if (instance? File p) p (File. (str p)))]
                          (when-not (absolute-dir? f)
                            (throw (ex-info "git worktree must be absolute directory"
                                            {:phase :git-transport :worktree k})))
                          [k f]))
                      worktrees))]
       (fn [{:keys [op worktree n]}]
         (if-let [^File dir (get dirs worktree)]
           (case op
             :status
             (let [br (run-git git-bin dir
                               ["rev-parse" "--abbrev-ref" "HEAD"]
                               timeout-ms max-out)
                   st (run-git git-bin dir
                               ["status" "--porcelain"]
                               timeout-ms max-out)]
               (if (or (= :error (:tag br)) (= :error (:tag st)))
                 (or (when (= :error (:tag br)) br) st)
                 (if (or (not (zero? (:exit br))) (not (zero? (:exit st))))
                   {:tag :error
                    :code :git/exit
                    :message (str "git status exit "
                                  (:exit br) "/" (:exit st))}
                   (let [branch (str/trim-newline (str (:stdout br)))
                         porcelain (str (:stdout st))]
                     {:tag :status
                      :branch branch
                      :clean? (str/blank? porcelain)
                      :porcelain porcelain}))))

             :log
             (let [n' (long (or n 10))
                   r (run-git git-bin dir
                              ["log" (str "-" n') "--format=%h %s"]
                              timeout-ms max-out)]
               (if (= :error (:tag r))
                 r
                 (if (not (zero? (:exit r)))
                   {:tag :error :code :git/exit :message (str "git log exit " (:exit r))}
                   {:tag :log
                    :lines (->> (str/split-lines (str (:stdout r)))
                                (remove str/blank?)
                                vec)})))

             {:tag :error :code :git/unknown-op :message "unknown op"})
           {:tag :error :code :git/not-found :message "unknown worktree"})))))

#?(:cljs
   (defn os-run
     "cljs gap — use process-transport os-spawn composition on nbb later."
     [_opts]
     (throw (ex-info "git-transport/os-run is JVM-only in ADR 0148"
                     {:phase :git-transport}))))
