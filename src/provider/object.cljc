(ns provider.object
  "Bounded object-store write path for stream-object-v1 (W5 dual-runtime first
  slice). Covers `:object/put-block` and `:object/compare-and-set-ref` only.

  Linear task/stream handles (`:object/get-stream`, `:http/get-stream`) remain
  on the Component v0.3 / Wasm path (kit marks `:component-core
  :task-stream-handle-slice`); this namespace is the reference-runtime semantic
  vector for the synchronous bool write ops.

  The kit field type `:bytes` is represented on the reference host as a
  `:string` field (opaque UTF-8 payload) because `kotoba.kir.value` does not
  yet admit `:bytes` as a runtime typed value. Effectful Component fixtures
  already lower block bodies as strings the same way. A dedicated binary
  bytes value type for the reference runtime is deferred. Payload length is
  bounded by `max-pull-bytes` (65536).

  Bindings are host-owned allowlist keywords. Digests, keys, etags, and next
  refs are bounded strings. No ambient object store or network."
  (:require [kotoba.kir.value :as value]))

(def put-block-capability-id 15)
(def cas-capability-id 16)
(def max-pull-bytes 65536)

(def expected-etag-type [:option :string])

(def put-block-request-type
  ;; Kit uses [:bytes :bytes]; dual-runtime binds the payload as :string
  ;; (see ns docstring). Field name remains :bytes for kit alignment.
  [:record :kotoba.object/put-block-request
   [[:binding :keyword] [:digest :string] [:bytes :string]]])

(def cas-request-type
  [:record :kotoba.object/compare-and-set-ref-request
   [[:binding :keyword] [:key :string]
    [:expected expected-etag-type] [:next :string]]])

(def put-block-result-type :bool)
(def cas-result-type :bool)

(def schemas
  {:kotoba.object/put-block-request put-block-request-type
   :kotoba.object/compare-and-set-ref-request cas-request-type})

(defn- bounded-payload!
  "Validate the reference-runtime host representation of kit `:bytes`
  (plain string, UTF-8 byte length ≤ max-pull-bytes)."
  [payload]
  (value/bounded-string! payload max-pull-bytes))

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
  "Build both write-path providers sharing one binding allowlist and transport.
  Returns `{:providers {15 put 16 cas}}` for reference-runtime install."
  [{:keys [allowed-bindings transport] :as opts}]
  {:providers
   {put-block-capability-id (put-block-provider opts)
    cas-capability-id (cas-provider opts)}})
