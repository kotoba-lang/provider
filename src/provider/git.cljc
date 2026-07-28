(ns provider.git
  "Git kit (capability id 22) — first contract slice.

  Read-only **status** and **log** under host-declared worktree keys.
  No ambient git binary, no ambient CWD: the host injects a `run`
  transport and an allowlist of worktree keywords.

  Production OS git (process-transport os-spawn of `git`) is a later
  transport slice; this file is pure policy + injectable doubles."
  (:require [clojure.string :as str]
            [kotoba.kir.value :as value]))

(def capability-id 22)
(def max-log-n 100)
(def max-line-bytes 4096)
(def max-lines 256)

(def status-request-type
  [:record :kotoba.git/status-request
   [[:worktree :keyword]]])

(def log-request-type
  [:record :kotoba.git/log-request
   [[:worktree :keyword] [:n :i64]]])

(def request-type
  [:variant :kotoba.git/request
   [[:status status-request-type] [:log log-request-type]]])

(def error-type
  [:record :kotoba.git/error
   [[:code :keyword] [:message :string]]])

(def status-result-type
  [:record :kotoba.git/status-result
   [[:branch :string] [:clean? :bool] [:porcelain :string]]])

(def log-result-type
  [:record :kotoba.git/log-result
   [[:lines [:vector :string]]]])

(def reply-type
  [:variant :kotoba.git/reply
   [[:status status-result-type]
    [:log log-result-type]
    [:error error-type]]])

(def schemas
  {:kotoba.git/status-request status-request-type
   :kotoba.git/log-request log-request-type
   :kotoba.git/request request-type
   :kotoba.git/error error-type
   :kotoba.git/status-result status-result-type
   :kotoba.git/log-result log-result-type
   :kotoba.git/reply reply-type})

(defn validate-worktree
  "Pure worktree key policy. nil when ok, else error keyword."
  [worktree allowed]
  (cond
    (not (qualified-keyword? worktree)) :git/bad-worktree
    (and allowed (not (contains? allowed worktree))) :git/not-allowed
    :else nil))

(defn validate-log-n
  "Pure log length policy."
  [n]
  (cond
    (not (integer? n)) :git/bad-n
    (not (pos? n)) :git/bad-n
    (> n max-log-n) :git/n-too-large
    :else nil))

(defn- error [code message]
  (value/bounded-keyword! code value/keyword-value-byte-limit)
  (value/bounded-string! message value/string-value-byte-limit)
  [reply-type :error [error-type code message]])

(defn- ok-status [branch clean? porcelain]
  (value/bounded-string! branch value/string-value-byte-limit)
  (value/bounded-string! porcelain max-line-bytes)
  [reply-type :status [status-result-type branch (boolean clean?) porcelain]])

(defn- ok-log [lines]
  (when (> (count lines) max-lines)
    (throw (ex-info "git log too many lines" {:phase :git-provider})))
  (doseq [l lines]
    (value/bounded-string! l max-line-bytes))
  [reply-type :log [log-result-type (vec lines)]])

(defn- invoke-run [run op]
  (try
    (run op)
    (catch #?(:clj Throwable :cljs :default) _
      {:tag :error :code :git/run :message "git run failed"})))

(defn mem-run
  "Test double: fixed status/log tables keyed by worktree.

  `db` shape:
    {worktree-kw {:branch s :clean? bool :porcelain s :log [line ...]}}"
  [db]
  (when-not (map? db)
    (throw (ex-info "git mem-run requires a map" {:phase :git-provider})))
  (fn [{:keys [op worktree n]}]
    (if-let [row (get db worktree)]
      (case op
        :status
        {:tag :status
         :branch (str (:branch row "main"))
         :clean? (boolean (:clean? row true))
         :porcelain (str (:porcelain row ""))}

        :log
        {:tag :log
         :lines (vec (take (long (or n 10)) (or (:log row) [])))}

        {:tag :error :code :git/unknown-op :message "unknown op"})
      {:tag :error :code :git/not-found :message "unknown worktree"})))

(defn provider
  "Typed git provider.

  opts:
    :allowed-worktrees  set of qualified keywords
    :run                (fn [{:keys [op worktree n]}] -> reply map)"
  [{:keys [allowed-worktrees run]}]
  (when-not (and (set? allowed-worktrees)
                 (every? qualified-keyword? allowed-worktrees)
                 (fn? run))
    (throw (ex-info "git requires allowed-worktrees and run"
                    {:phase :git-provider})))
  (doseq [w allowed-worktrees]
    (value/bounded-keyword! w value/keyword-value-byte-limit))
  {:request-type request-type
   :result-type reply-type
   :invoke
   (fn [req]
     (let [[actual-type tag payload] req]
       (when-not (= actual-type request-type)
         (throw (ex-info "git contract mismatch"
                         {:phase :git-provider})))
       (case tag
         :status
         (let [[_rtype worktree] payload
               err (validate-worktree worktree allowed-worktrees)]
           (if err
             (error err (name err))
             (let [reply (invoke-run run {:op :status :worktree worktree})]
               (case (:tag reply)
                 :status (ok-status (:branch reply)
                                    (:clean? reply)
                                    (or (:porcelain reply) ""))
                 :error (error (:code reply) (or (:message reply) "status failed"))
                 (error :git/run "bad status reply")))))

         :log
         (let [[_rtype worktree n] payload
               n' #?(:clj (long n) :cljs (js/Number n))
               err (or (validate-worktree worktree allowed-worktrees)
                       (validate-log-n n'))]
           (if err
             (error err (name err))
             (let [reply (invoke-run run {:op :log :worktree worktree :n n'})]
               (case (:tag reply)
                 :log (ok-log (or (:lines reply) []))
                 :error (error (:code reply) (or (:message reply) "log failed"))
                 (error :git/run "bad log reply")))))

         (error :git/unknown-op "unknown request tag"))))})

(defn create-providers [opts]
  {:providers {capability-id (provider opts)}})
