(ns provider.edn-codec
  "Host-side pure EDN codec wire for ops-kit W4 packages (ADR 0256).

  Loads content-addressed typed wasm packages from the classpath registry and
  invokes pure request/reply EDN exports via Node + kotoba browser-host.

  This does **not** perform host I/O (network, spawn, store, CSPRNG). It only
  runs pure guest codecs so hosts can audit kit request/reply shapes without
  reimplementing EDN encode in Clojure.

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
