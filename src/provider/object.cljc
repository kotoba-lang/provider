(ns provider.object
  "Bounded object-store reference providers for stream-object-v1.

  Write path: `:object/put-block` + `:object/compare-and-set-ref` (bool).
  Read path (ADR 0121): `:object/get-stream` returns a host affine
  `[:task [:stream :bytes]]` (ready task with one payload chunk). Linear
  Component v0.3 handle ABI remains on the Wasm path; this namespace is the
  reference dual-runtime semantic vector.

  Kit `:bytes` is a first-class runtime leaf (JVM byte-array, cljs
  Uint8Array) via kotoba.kir.value (ADR 0120). Payload length is bounded by
  max-pull-bytes (65536).

  Bindings are host-owned allowlist keywords. No ambient object store or
  network."
  (:require [kotoba.kir.value :as value]))

(def get-stream-capability-id 14)
(def put-block-capability-id 15)
(def cas-capability-id 16)
(def max-pull-bytes 65536)

(def expected-etag-type [:option :string])

(def get-stream-request-type
  [:record :kotoba.object/get-stream-request
   [[:binding :keyword] [:key :string]]])

(def get-stream-result-type [:task [:stream :bytes]])

(def put-block-request-type
  [:record :kotoba.object/put-block-request
   [[:binding :keyword] [:digest :string] [:bytes :bytes]]])

(def cas-request-type
  [:record :kotoba.object/compare-and-set-ref-request
   [[:binding :keyword] [:key :string]
    [:expected expected-etag-type] [:next :string]]])

(def put-block-result-type :bool)
(def cas-result-type :bool)

(def schemas
  {:kotoba.object/get-stream-request get-stream-request-type
   :kotoba.object/put-block-request put-block-request-type
   :kotoba.object/compare-and-set-ref-request cas-request-type})

(defn- bounded-payload!
  "Validate the reference-runtime host representation of kit `:bytes`."
  [payload]
  (value/bounded-bytes! payload max-pull-bytes))

(defn- expected-etag
  "Decode `[:option :string]` into nil or a bounded string."
  [[actual-type present? etag]]
  (when-not (= actual-type expected-etag-type)
    (throw (ex-info "object expected-etag contract mismatch"
                    {:phase :object-provider})))
  (when present?
    (value/bounded-string! etag value/string-value-byte-limit)
    etag))

(defn- invoke-transport [transport request]
  (try
    (transport request)
    (catch #?(:clj Throwable :cljs :default) _
      (throw (ex-info "object provider failed"
                      {:phase :object-provider})))))

(defn- as-bool! [reply context]
  (when-not (boolean? reply)
    (throw (ex-info "object transport result must be a bool"
                    {:phase :object-provider :context context})))
  reply)

(defn- as-ready-bytes-task!
  "Transport returns `{:bytes <host-bytes>}` (or a raw bytes value). Wrap as
  a ready `[:task [:stream :bytes]]` host handle."
  [reply]
  (let [payload (cond
                  (value/bytes-value? reply) reply
                  (and (map? reply) (value/bytes-value? (:bytes reply))) (:bytes reply)
                  :else (throw (ex-info "object get-stream transport must return bytes"
                                        {:phase :object-provider})))]
    (value/make-ready-bytes-task (bounded-payload! payload))))

(defn get-stream-provider
  "Typed provider for `:object/get-stream` (id 14).
  Transport receives `{:operation :get-stream :binding :key}` and returns
  either a host `:bytes` payload or `{:bytes <bytes>}`. Result is always a
  ready bytes-task (pending/cancel timing is a later slice)."
  [{:keys [allowed-bindings transport]}]
  (when-not (and (set? allowed-bindings)
                 (every? qualified-keyword? allowed-bindings)
                 (fn? transport))
    (throw (ex-info "object get-stream requires allowed-bindings and transport"
                    {:phase :object-provider})))
  (doseq [b allowed-bindings]
    (value/bounded-keyword! b value/keyword-value-byte-limit))
  {:request-type get-stream-request-type
   :result-type get-stream-result-type
   :invoke
   (fn [[actual-type binding key]]
     (when-not (= actual-type get-stream-request-type)
       (throw (ex-info "object get-stream contract mismatch"
                       {:phase :object-provider})))
     (when-not (contains? allowed-bindings binding)
       (throw (ex-info "object binding is not allowed"
                       {:phase :object-provider :binding binding})))
     (value/bounded-string! key value/string-value-byte-limit)
     (when (zero? (value/utf8-byte-count! key))
       (throw (ex-info "object stream key must be non-empty"
                       {:phase :object-provider})))
     (as-ready-bytes-task!
      (invoke-transport transport
                        {:operation :get-stream
                         :binding binding
                         :key key})))})

(defn put-block-provider
  "Typed provider for `:object/put-block` (id 15).
  `allowed-bindings` is a closed set of qualified binding keywords.
  Transport receives `{:binding :digest :bytes}` and returns a bool."
  [{:keys [allowed-bindings transport]}]
  (when-not (and (set? allowed-bindings)
                 (every? qualified-keyword? allowed-bindings)
                 (fn? transport))
    (throw (ex-info "object put-block requires allowed-bindings and transport"
                    {:phase :object-provider})))
  (doseq [b allowed-bindings]
    (value/bounded-keyword! b value/keyword-value-byte-limit))
  {:request-type put-block-request-type
   :result-type put-block-result-type
   :invoke
   (fn [[actual-type binding digest payload]]
     (when-not (= actual-type put-block-request-type)
       (throw (ex-info "object put-block contract mismatch"
                       {:phase :object-provider})))
     (when-not (contains? allowed-bindings binding)
       (throw (ex-info "object binding is not allowed"
                       {:phase :object-provider :binding binding})))
     (value/bounded-string! digest value/string-value-byte-limit)
     (when (zero? (value/utf8-byte-count! digest))
       (throw (ex-info "object digest must be non-empty"
                       {:phase :object-provider})))
     (bounded-payload! payload)
     (as-bool!
      (invoke-transport transport
                        {:operation :put-block
                         :binding binding
                         :digest digest
                         :bytes payload})
      :put-block))})

(defn cas-provider
  "Typed provider for `:object/compare-and-set-ref` (id 16).
  Transport receives `{:binding :key :expected :next}` (`:expected` may be
  nil for unconditional set) and returns a bool (won?)."
  [{:keys [allowed-bindings transport]}]
  (when-not (and (set? allowed-bindings)
                 (every? qualified-keyword? allowed-bindings)
                 (fn? transport))
    (throw (ex-info "object CAS requires allowed-bindings and transport"
                    {:phase :object-provider})))
  (doseq [b allowed-bindings]
    (value/bounded-keyword! b value/keyword-value-byte-limit))
  {:request-type cas-request-type
   :result-type cas-result-type
   :invoke
   (fn [[actual-type binding key expected-option next-etag]]
     (when-not (= actual-type cas-request-type)
       (throw (ex-info "object CAS contract mismatch"
                       {:phase :object-provider})))
     (when-not (contains? allowed-bindings binding)
       (throw (ex-info "object binding is not allowed"
                       {:phase :object-provider :binding binding})))
     (value/bounded-string! key value/string-value-byte-limit)
     (when (zero? (value/utf8-byte-count! key))
       (throw (ex-info "object ref key must be non-empty"
                       {:phase :object-provider})))
     (value/bounded-string! next-etag value/string-value-byte-limit)
     (when (zero? (value/utf8-byte-count! next-etag))
       (throw (ex-info "object next etag must be non-empty"
                       {:phase :object-provider})))
     (let [expected (expected-etag expected-option)]
       (as-bool!
        (invoke-transport transport
                          {:operation :compare-and-set-ref
                           :binding binding
                           :key key
                           :expected expected
                           :next next-etag})
        :compare-and-set-ref)))})

(defn create-providers
  "Build write + get-stream providers sharing one binding allowlist and transport.
  Returns `{:providers {14 get-stream 15 put 16 cas}}` for reference-runtime."
  [{:keys [allowed-bindings transport] :as opts}]
  {:providers
   {get-stream-capability-id (get-stream-provider opts)
    put-block-capability-id (put-block-provider opts)
    cas-capability-id (cas-provider opts)}})
