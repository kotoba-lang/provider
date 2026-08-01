(ns provider.edn-codec
  "Host-side pure EDN codec wire for ops-kit W4 packages (ADR 0256–0258).

  Loads content-addressed typed wasm packages from the classpath registry and
  invokes pure request/reply EDN exports via Node + kotoba browser-host.

  This does **not** perform host I/O (network, spawn, store, CSPRNG). It only
  runs pure guest codecs so hosts can audit kit request/reply shapes without
  reimplementing EDN encode in Clojure.

  ADR 0257: secret/http audit wraps. ADR 0258: process/git/entropy/scoped-fs
  helpers + audit wraps; HTTP wrap also attaches reply EDN.

  Requires Node and a resolvable `browser-host.mjs` (sibling compiler checkout
  or `KOTOBA_BROWSER_HOST`). When unavailable, functions return
  `{:ok false :reason :browser-host-unavailable}`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [provider.kit-package :as kit])
  (:import (java.io File)))

(defn- browser-host-path
  "Absolute path to browser-host.mjs, or nil."
  []
  (or (System/getenv "KOTOBA_BROWSER_HOST")
      (let [cands [(io/file ".." "compiler" "runtime" "browser-host.mjs")
                   (io/file "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/compiler/runtime/browser-host.mjs")]]
        (some (fn [^File f]
                (when (.exists f) (.getAbsolutePath f)))
              cands))))

(defn- resource-file
  "Materialize a classpath wasm resource to a temp file (Node needs a path)."
  [resource]
  (let [url (io/resource resource)]
    (when-not url
      (throw (ex-info "wasm package resource missing"
                      {:phase :edn-codec :resource resource})))
    (let [tmp (File/createTempFile "kotoba-edn-codec-" ".wasm")]
      (.deleteOnExit tmp)
      (io/copy (io/input-stream url) tmp)
      (.getAbsolutePath tmp))))

(defn- js-quote [s]
  (str "'"
       (-> (str s)
           (str/replace "\\" "\\\\")
           (str/replace "'" "\\'"))
       "'"))

(defn- encode-arg [a]
  (cond
    (string? a) (js-quote a)
    (integer? a) (str a "n")
    (boolean? a) (if a "true" "false")
    :else (throw (ex-info "unsupported edn-codec arg type"
                          {:phase :edn-codec :arg a}))))

(defn invoke-export
  "Invoke `export` on registry package `:name` with args (strings/i64).

  Returns `{:ok true :value string-or-long}` or
  `{:ok false :reason keyword :detail string}`."
  [package-name export args]
  (let [host (browser-host-path)
        table (kit/load-wasm-packages-table)
        entry (kit/wasm-package-for table package-name)]
    (cond
      (nil? host)
      {:ok false :reason :browser-host-unavailable}

      (nil? entry)
      {:ok false :reason :unknown-package :detail (str package-name)}

      :else
      (try
        (let [wasm (resource-file (:resource entry))
              call (str "h.instance.exports[" (js-quote (name export)) "]("
                        (str/join "," (map encode-arg args)) ")")
              script (str "import { readFileSync } from 'fs';"
                          "import { instantiateKotoba } from "
                          (pr-str (str "file://" host))
                          ";"
                          "const h=await instantiateKotoba(readFileSync("
                          (pr-str wasm) "));"
                          "const v=" call ";"
                          "if(typeof v==='bigint'){console.log(JSON.stringify({t:'i64',v:v.toString()}));}"
                          "else if(typeof v==='string'){console.log(JSON.stringify({t:'s',v:v}));}"
                          "else {console.error('bad type',typeof v); process.exit(3);}")
              pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                   (.redirectErrorStream true))
              p (.start pb)
              out (slurp (.getInputStream p))
              code (.waitFor p)]
          (if (zero? code)
            (let [line (str/trim out)
                  parsed (json/read-str line)]
              (case (get parsed "t")
                "s" {:ok true :value (get parsed "v")}
                "i64" {:ok true :value (Long/parseLong (get parsed "v"))}
                {:ok false :reason :bad-result :detail line}))
            {:ok false :reason :node-exit :detail out :code code}))
        (catch Exception e
          {:ok false :reason :exception :detail (ex-message e)})))))

;; High-level pure codec helpers (no host I/O)

(defn secret-request-edn
  "W4 secret request EDN via :secret-record-kv-edn."
  [name]
  (invoke-export :secret-record-kv-edn :secret_request_rec_kv_edn [name]))

(defn secret-reply-value-edn
  [value]
  (invoke-export :secret-record-kv-edn :secret_reply_value_rec_kv_edn [value]))

(defn secret-reply-error-edn
  [code message]
  (invoke-export :secret-record-kv-edn :secret_reply_error_rec_kv_edn [code message]))

(defn entropy-request-edn
  [n]
  (invoke-export :entropy-record-kv-edn :entropy_req_rec_kv_edn [(long n)]))

(defn process-request-edn
  "argv-edn is a prebuilt EDN vector string e.g. [\"echo\" \"hi\"]."
  [argv-edn max-stdout timeout-ms]
  (invoke-export :process-record-kv-edn :process_req_rec_kv_edn
                 [argv-edn (long max-stdout) (long timeout-ms)]))

(defn codec-aot-complete?
  "ADR 0256: pure request/result EDN codec packages landed for ops kits
  through W4 0255. Host authority remains outside guest AOT."
  []
  true)

;; --- ADR 0257: audit-hook integration (pure EDN only; no extra host I/O) ---

(defn- keyword-code [code]
  (cond
    (keyword? code) (name code)
    (string? code) code
    :else (str code)))

(defn- codec-value [result]
  (when (and (map? result) (:ok result) (string? (:value result)))
    (:value result)))

(defn http-headers-list-edn
  "Build 2-header EDN list for recursive-record-kv (exactly two pairs)."
  [name1 value1 name2 value2]
  (invoke-export :recursive-record-kv-edn :headers_list_edn
                 [(str name1) (str value1) (str name2) (str value2)]))

(defn http-request-edn
  "W4 HTTP request EDN. headers-edn from `http-headers-list-edn`."
  [url body timeout-ms headers-edn]
  (invoke-export :recursive-record-kv-edn :request_rec_kv_edn
                 [(str url) (str body) (long timeout-ms) (str headers-edn)]))

(defn http-result-ok-edn
  [status body headers-edn]
  (invoke-export :recursive-record-kv-edn :result_ok_rec_kv_edn
                 [(long status) (str body) (str headers-edn)]))

(defn http-result-err-edn
  "retryable: 1 true, 0 false."
  [code message retryable]
  (invoke-export :recursive-record-kv-edn :result_err_rec_kv_edn
                 [(str code) (str message) (long retryable)]))

(defn wrap-secret-fetch
  "Wrap a secret `:fetch` transport so each call audits pure W4 request/reply EDN.

  `on-call` receives a map:
  `{:kit :secret :op :get :name n :request-edn s :reply-edn s :reply-tag t}`

  Does not change fetch semantics. Codec/on-call failures are swallowed.
  Underlying fetch is still the only secret authority exercised."
  ([fetch] (wrap-secret-fetch fetch (fn [_])))
  ([fetch on-call]
   (when-not (fn? fetch)
     (throw (ex-info "wrap-secret-fetch requires a fetch fn"
                     {:phase :edn-codec})))
   (when-not (fn? on-call)
     (throw (ex-info "wrap-secret-fetch requires an on-call fn"
                     {:phase :edn-codec})))
   (fn [{:keys [name] :as req}]
     (let [req-codec (secret-request-edn name)
           reply (fetch req)
           reply-codec (case (:tag reply)
                         :value (secret-reply-value-edn (str (:value reply)))
                         :error (secret-reply-error-edn
                                 (keyword-code (:code reply))
                                 (str (or (:message reply) "")))
                         {:ok false})]
       (try
         (on-call {:kit :secret
                   :op :get
                   :name name
                   :request-edn (codec-value req-codec)
                   :reply-edn (codec-value reply-codec)
                   :reply-tag (:tag reply)})
         (catch Exception _))
       reply))))

(defn- headers-edn-from-map
  "Pick two headers for W4 headers_list (package is 2-header fixed arity).
  Prefer Accept/Host; fall back to first entries or synthetic placeholders."
  [headers]
  (let [m (into {}
                (map (fn [[k v]]
                       [(if (keyword? k) (name k) (str k)) (str v)]))
                (or headers {}))
        accept (or (get m "Accept") (get m "accept") "*/*")
        host (or (get m "Host") (get m "host") "audit.local")
        other (first (remove (fn [[k _]] (#{"Accept" "accept" "Host" "host"} k)) m))
        n1 "Accept"
        v1 accept
        n2 (if other (key other) "Host")
        v2 (if other (val other) host)
        r (http-headers-list-edn n1 v1 n2 v2)]
    (codec-value r)))

(defn wrap-http-post-transport
  "Wrap an HTTP post transport fn so each call audits pure W4 request EDN.

  Transport remains `(fn [{:keys [url headers body timeout-ms]}] -> result)`.
  `on-call` receives:
  `{:kit :http :op :post :url u :request-edn s :status n :error? bool :latency-ms n}`

  Does not perform network itself — only wraps the host-supplied transport."
  ([transport] (wrap-http-post-transport transport (fn [_])))
  ([transport on-call]
   (when-not (fn? transport)
     (throw (ex-info "wrap-http-post-transport requires a transport fn"
                     {:phase :edn-codec})))
   (when-not (fn? on-call)
     (throw (ex-info "wrap-http-post-transport requires an on-call fn"
                     {:phase :edn-codec})))
   (fn [{:keys [url headers body timeout-ms] :as req}]
     (let [started (System/currentTimeMillis)
           hdrs (or (headers-edn-from-map headers) "[]")
           req-codec (http-request-edn (str url)
                                       (str (or body ""))
                                       (long (or timeout-ms 5000))
                                       hdrs)
           result (transport req)
           status (or (:status result) -1)
           err? (boolean (or (:error result) (:error? result)))
           reply-codec (if err?
                         (http-result-err-edn
                          (keyword-code (or (:code result) :transport-error))
                          (str (or (:message result) (:error result) "error"))
                          (if (:retryable result) 1 0))
                         (http-result-ok-edn
                          (long status)
                          (str (or (:body result) ""))
                          hdrs))]
       (try
         (on-call {:kit :http
                   :op :post
                   :url url
                   :request-edn (codec-value req-codec)
                   :reply-edn (codec-value reply-codec)
                   :status status
                   :error? err?
                   :latency-ms (- (System/currentTimeMillis) started)})
         (catch Exception _))
       result))))


;; --- ADR 0258: remaining ops-kit pure codecs + audit wraps ---

(defn process-reply-ok-edn
  [exit stdout stderr]
  (invoke-export :process-record-kv-edn :process_reply_ok_rec_kv_edn
                 [(long exit) (str stdout) (str stderr)]))

(defn process-reply-error-edn
  [code message]
  (invoke-export :process-record-kv-edn :process_reply_error_rec_kv_edn
                 [(str code) (str message)]))

(defn git-request-edn
  "args-edn is a prebuilt EDN vector string e.g. [\"status\"]."
  [args-edn max-stdout timeout-ms]
  (invoke-export :git-record-kv-edn :git_req_rec_kv_edn
                 [args-edn (long max-stdout) (long timeout-ms)]))

(defn git-reply-ok-edn
  [exit stdout stderr]
  (invoke-export :git-record-kv-edn :git_reply_ok_rec_kv_edn
                 [(long exit) (str stdout) (str stderr)]))

(defn git-reply-error-edn
  [code message]
  (invoke-export :git-record-kv-edn :git_reply_error_rec_kv_edn
                 [(str code) (str message)]))

(defn entropy-reply-hex-edn
  [hex]
  (invoke-export :entropy-record-kv-edn :entropy_reply_hex_rec_kv_edn [(str hex)]))

(defn entropy-reply-error-edn
  [code message]
  (invoke-export :entropy-record-kv-edn :entropy_reply_error_rec_kv_edn
                 [(str code) (str message)]))

(defn fs-req-read-edn
  [root path]
  (invoke-export :scoped-fs-record-kv-edn :fs_req_read_rec_kv_edn
                 [(str root) (str path)]))

(defn fs-req-write-edn
  [root path value]
  (invoke-export :scoped-fs-record-kv-edn :fs_req_write_rec_kv_edn
                 [(str root) (str path) (str value)]))

(defn fs-reply-content-edn
  [content]
  (invoke-export :scoped-fs-record-kv-edn :fs_reply_content_rec_kv_edn [(str content)]))

(defn fs-reply-written-edn
  "written: 1 true, 0 false."
  [written]
  (invoke-export :scoped-fs-record-kv-edn :fs_reply_written_rec_kv_edn [(long written)]))

(defn fs-reply-error-edn
  [code message]
  (invoke-export :scoped-fs-record-kv-edn :fs_reply_error_rec_kv_edn
                 [(str code) (str message)]))

(defn wrap-process-spawn
  "Wrap process spawn `(fn [{:keys [argv max-stdout-bytes timeout-ms]}] -> reply)`.
  Reply shapes: `{:tag :ok :exit n :stdout s :stderr s}` or
  `{:tag :error :code c :message m}`. Audits W4 request+reply EDN."
  ([spawn] (wrap-process-spawn spawn (fn [_])))
  ([spawn on-call]
   (when-not (fn? spawn)
     (throw (ex-info "wrap-process-spawn requires a spawn fn" {:phase :edn-codec})))
   (when-not (fn? on-call)
     (throw (ex-info "wrap-process-spawn requires an on-call fn" {:phase :edn-codec})))
   (fn [{:keys [argv max-stdout-bytes timeout-ms] :as req}]
     (let [argv-edn (cond
                      (string? argv) argv
                      (sequential? argv)
                      (str "[" (str/join " " (map #(str "\"" % "\"") argv)) "]")
                      :else "[]")
           req-codec (process-request-edn argv-edn
                                          (long (or max-stdout-bytes 4096))
                                          (long (or timeout-ms 5000)))
           reply (spawn req)
           reply-codec (case (:tag reply)
                         :ok (process-reply-ok-edn (long (or (:exit reply) 0))
                                                   (str (or (:stdout reply) ""))
                                                   (str (or (:stderr reply) "")))
                         :error (process-reply-error-edn
                                 (keyword-code (:code reply))
                                 (str (or (:message reply) "")))
                         {:ok false})]
       (try
         (on-call {:kit :process
                   :op :spawn
                   :request-edn (codec-value req-codec)
                   :reply-edn (codec-value reply-codec)
                   :reply-tag (:tag reply)})
         (catch Exception _))
       reply))))

(defn wrap-git-run
  "Wrap git run `(fn [{:keys [args max-stdout-bytes timeout-ms]}] -> reply)`.
  Same reply shape as process. Audits W4 request+reply EDN."
  ([run] (wrap-git-run run (fn [_])))
  ([run on-call]
   (when-not (fn? run)
     (throw (ex-info "wrap-git-run requires a run fn" {:phase :edn-codec})))
   (when-not (fn? on-call)
     (throw (ex-info "wrap-git-run requires an on-call fn" {:phase :edn-codec})))
   (fn [{:keys [args max-stdout-bytes timeout-ms] :as req}]
     (let [args-edn (cond
                      (string? args) args
                      (sequential? args)
                      (str "[" (str/join " " (map #(str "\"" % "\"") args)) "]")
                      :else "[]")
           req-codec (git-request-edn args-edn
                                      (long (or max-stdout-bytes 4096))
                                      (long (or timeout-ms 5000)))
           reply (run req)
           reply-codec (case (:tag reply)
                         :ok (git-reply-ok-edn (long (or (:exit reply) 0))
                                               (str (or (:stdout reply) ""))
                                               (str (or (:stderr reply) "")))
                         :error (git-reply-error-edn
                                 (keyword-code (:code reply))
                                 (str (or (:message reply) "")))
                         {:ok false})]
       (try
         (on-call {:kit :git
                   :op :run
                   :request-edn (codec-value req-codec)
                   :reply-edn (codec-value reply-codec)
                   :reply-tag (:tag reply)})
         (catch Exception _))
       reply))))

(defn wrap-entropy-draw
  "Wrap entropy draw `(fn [{:keys [n]}] -> reply)`.
  Reply: `{:tag :hex :hex s}` or `{:tag :error :code c :message m}`."
  ([draw] (wrap-entropy-draw draw (fn [_])))
  ([draw on-call]
   (when-not (fn? draw)
     (throw (ex-info "wrap-entropy-draw requires a draw fn" {:phase :edn-codec})))
   (when-not (fn? on-call)
     (throw (ex-info "wrap-entropy-draw requires an on-call fn" {:phase :edn-codec})))
   (fn [{:keys [n] :as req}]
     (let [req-codec (entropy-request-edn (long n))
           reply (draw req)
           reply-codec (case (:tag reply)
                         :hex (entropy-reply-hex-edn (str (:hex reply)))
                         :error (entropy-reply-error-edn
                                 (keyword-code (:code reply))
                                 (str (or (:message reply) "")))
                         {:ok false})]
       (try
         (on-call {:kit :entropy
                   :op :draw
                   :n n
                   :request-edn (codec-value req-codec)
                   :reply-edn (codec-value reply-codec)
                   :reply-tag (:tag reply)})
         (catch Exception _))
       reply))))

(defn wrap-scoped-fs-transact
  "Wrap scoped-fs op `(fn [{:keys [op root path value]}] -> reply)`.
  `op` is `:read` or `:write`. Reply tags: `:content`, `:written`, `:error`."
  ([tx] (wrap-scoped-fs-transact tx (fn [_])))
  ([tx on-call]
   (when-not (fn? tx)
     (throw (ex-info "wrap-scoped-fs-transact requires a tx fn" {:phase :edn-codec})))
   (when-not (fn? on-call)
     (throw (ex-info "wrap-scoped-fs-transact requires an on-call fn" {:phase :edn-codec})))
   (fn [{:keys [op root path value] :as req}]
     (let [req-codec (case op
                       :read (fs-req-read-edn root path)
                       :write (fs-req-write-edn root path (str (or value "")))
                       {:ok false})
           reply (tx req)
           reply-codec (case (:tag reply)
                         :content (fs-reply-content-edn (str (:content reply)))
                         :written (fs-reply-written-edn
                                   (if (or (true? (:written reply))
                                           (= 1 (:written reply)))
                                     1 0))
                         :error (fs-reply-error-edn
                                 (keyword-code (:code reply))
                                 (str (or (:message reply) "")))
                         {:ok false})]
       (try
         (on-call {:kit :scoped-fs
                   :op op
                   :root root
                   :path path
                   :request-edn (codec-value req-codec)
                   :reply-edn (codec-value reply-codec)
                   :reply-tag (:tag reply)})
         (catch Exception _))
       reply))))
