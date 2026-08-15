(ns provider.dataspace
  "In-memory reference host for :dataspace/transact v1 (root ADR-2608154100).

  One provider instance is one dataspace. Guest code observes it only through
  the typed request/result contract after the runtime has admitted capability
  id 24. Assertions are inert EDN; copying them does not grant observe.
  Facet leave retracts assertions published in that facet and drops its
  observations.

  kgraph is not the backing store this slice: kgraph-assert! is an i64 EAV
  triple host op, not an EDN tuple space. Incidence (kotoba-lang) is the
  CID-addressed fact projection. This provider is the Syndicate tuple space.

  Kit EDN stays in kotoba-lang/amu (`dataspace-v1.edn`). This ns is the host
  other runtimes inject. It is NOT a member of the provider-conformance 9-kit
  closed set (`:capability-count` 9)."
  (:require [clojure.edn :as edn]
            [provider.dataspace-match :as match]
            [kotoba.kir.value :as value]
            #?@(:cljs [[kotoba.kir.cljs-i64 :as i64]])))

(def capability-id 24)
(def max-assertions 256)
(def max-observers 64)
(def max-facets 32)
(def max-edn-bytes 4096)

(def assert-type
  [:record :kotoba.dataspace/assert [[:assertion :string] [:facet :i64]]])
(def retract-type
  [:record :kotoba.dataspace/retract [[:assertion :string] [:facet :i64]]])
(def observe-type
  [:record :kotoba.dataspace/observe [[:pattern :string] [:facet :i64]]])
(def request-type
  [:variant :kotoba.dataspace/request
   [[:assert assert-type]
    [:retract retract-type]
    [:observe observe-type]
    [:facet-enter :bool]
    [:facet-leave :i64]]])

(def asserted-type
  [:record :kotoba.dataspace/asserted [[:count :i64] [:notices :string]]])
(def retracted-type
  [:record :kotoba.dataspace/retracted [[:count :i64]]])
(def matches-type
  [:record :kotoba.dataspace/matches [[:bindings :string]]])
(def facet-type
  [:record :kotoba.dataspace/facet [[:id :i64]]])
(def error-type
  [:record :kotoba.dataspace/error [[:code :keyword] [:message :string]]])
(def result-type
  [:variant :kotoba.dataspace/result
   [[:asserted asserted-type]
    [:retracted retracted-type]
    [:matches matches-type]
    [:facet facet-type]
    [:error error-type]]])

(def schemas
  {:kotoba.dataspace/assert assert-type
   :kotoba.dataspace/retract retract-type
   :kotoba.dataspace/observe observe-type
   :kotoba.dataspace/request request-type
   :kotoba.dataspace/asserted asserted-type
   :kotoba.dataspace/retracted retracted-type
   :kotoba.dataspace/matches matches-type
   :kotoba.dataspace/facet facet-type
   :kotoba.dataspace/error error-type
   :kotoba.dataspace/result result-type})

(defn- ->i64 [n] #?(:clj n :cljs (i64/->bigint n)))
(defn- i64->long [n] #?(:clj n :cljs (js/Number (i64/->bigint n))))

(defn- result [tag payload]
  [result-type tag payload])

(defn- err [code message]
  (result :error [error-type code message]))

(defn- parse-edn
  "Read one EDN value. Unknown tagged literals (#cap, #cap-ref, …) fail closed
  and do not mint authority."
  [s]
  (when-not (string? s)
    (throw (ex-info "dataspace edn must be a string" {:phase :dataspace-provider})))
  (value/bounded-string! s max-edn-bytes)
  (try
    (edn/read-string
     {:eof ::eof
      :readers {}
      :default (fn [tag _value]
                 (throw (ex-info "tagged literal rejected"
                                 {:tag tag :phase :dataspace-provider})))}
     s)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      (throw e))
    (catch #?(:clj Throwable :cljs :default) e
      (throw (ex-info "dataspace edn is invalid"
                      {:phase :dataspace-provider
                       :message (ex-message e)})))))

(defn- encode-bindings [bindings]
  (pr-str (mapv #(into {} %) bindings)))

(defn- live-assertions [state]
  (map :value (:assertions state)))

(defn- match-observers [state assertion]
  (into []
        (keep (fn [observer]
                (match/match (:pattern observer) assertion)))
        (:observers state)))

(defn- retract-owned-value
  "Remove VALUE only from the exact FACET-ID that published it. Return the
  retained cells and the provider-local assertion IDs removed. Equal EDN
  asserted by another facet is a distinct publication and remains live."
  [assertions facet-id value]
  (reduce (fn [[kept removed] cell]
            (if (and (= facet-id (:facet cell))
                     (= value (:value cell)))
              [kept (conj removed (:id cell))]
              [(conj kept cell) removed]))
          [[] #{}]
          assertions))

(defn- facet-id-or-root [facet-id]
  (if (nil? facet-id) 0 (i64->long facet-id)))

(defn provider
  "Creates one isolated in-memory dataspace. No ambient process dataspace."
  []
  (let [state (atom {:assertions []
                     :observers []
                     :facets {}
                     :next-assertion 1
                     :next-facet 1
                     :next-observer 1})]
    {:request-type request-type
     :result-type result-type
     :invoke
     (fn [request]
       (when-not (and (vector? request) (= request-type (first request)))
         (throw (ex-info "dataspace request contract mismatch"
                         {:phase :dataspace-provider})))
       (let [operation (second request)
             payload (nth request 2 nil)]
         (case operation
           :assert
           (let [[_ assertion-edn raw-facet] payload
                 assertion (try (parse-edn assertion-edn)
                                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                                  (if (= "tagged literal rejected" (ex-message e))
                                    ::tagged
                                    ::invalid)))
                 facet-id (facet-id-or-root raw-facet)]
             (cond
               (= assertion ::tagged)
               (err :dataspace/tagged-rejected "tagged literal cannot mint a dataspace cap")

               (= assertion ::invalid)
               (err :dataspace/edn-invalid "assertion is not valid EDN")

               (and (pos? facet-id) (not (contains? (:facets @state) facet-id)))
               (err :dataspace/unknown-facet "facet handle is not live")

               (>= (count (:assertions @state)) max-assertions)
               (err :dataspace/capacity "assertion limit reached")

               :else
               (let [notices (match-observers @state assertion)]
                 (swap! state
                        (fn [s]
                          (let [assertion-id (:next-assertion s)]
                            (-> s
                                (update :assertions conj
                                        {:id assertion-id
                                         :value assertion
                                         :facet facet-id})
                                (update :next-assertion inc)
                                (cond-> (pos? facet-id)
                                  (update-in [:facets facet-id :assertions]
                                             (fnil conj #{}) assertion-id))))))
                 (result :asserted
                         [asserted-type (->i64 1) (encode-bindings notices)]))))

           :retract
           (let [[_ assertion-edn raw-facet] payload
                 assertion (try (parse-edn assertion-edn)
                                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _
                                  ::invalid))
                 facet-id (facet-id-or-root raw-facet)]
             (cond
               (= assertion ::invalid)
               (err :dataspace/edn-invalid "assertion is not valid EDN")

               (and (pos? facet-id) (not (contains? (:facets @state) facet-id)))
               (err :dataspace/unknown-facet "facet handle is not live")

               :else
               (let [removed (atom 0)]
                 (swap! state
                        (fn [s]
                          (let [[kept removed-ids]
                                (retract-owned-value (:assertions s)
                                                     facet-id assertion)]
                            (reset! removed (count removed-ids))
                            (cond-> (assoc s :assertions kept)
                              (pos? facet-id)
                              (update-in [:facets facet-id :assertions]
                                         #(apply disj (or % #{}) removed-ids))))))
                 (result :retracted [retracted-type (->i64 @removed)]))))

           :observe
           (let [[_ pattern-edn raw-facet] payload
                 pattern (try (parse-edn pattern-edn)
                              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                                (if (= "tagged literal rejected" (ex-message e))
                                  ::tagged
                                  ::invalid)))
                 facet-id (facet-id-or-root raw-facet)]
             (cond
               (= pattern ::tagged)
               (err :dataspace/tagged-rejected "tagged literal cannot mint a dataspace cap")

               (= pattern ::invalid)
               (err :dataspace/edn-invalid "pattern is not valid EDN")

               (and (pos? facet-id) (not (contains? (:facets @state) facet-id)))
               (err :dataspace/unknown-facet "facet handle is not live")

               (>= (count (:observers @state)) max-observers)
               (err :dataspace/capacity "observer limit reached")

               :else
               (let [bindings (into []
                                    (keep #(match/match pattern %))
                                    (live-assertions @state))]
                 (swap! state
                        (fn [s]
                          (let [oid (:next-observer s)
                                observer {:id oid :pattern pattern :facet facet-id}]
                            (-> s
                                (update :observers conj observer)
                                (update :next-observer inc)
                                (cond-> (pos? facet-id)
                                  (update-in [:facets facet-id :observers]
                                             (fnil conj []) oid))))))
                 (result :matches [matches-type (encode-bindings bindings)]))))

           :facet-enter
           (let [n (count (:facets @state))]
             (if (>= n max-facets)
               (err :dataspace/capacity "facet limit reached")
               (let [id (atom 0)]
                 (swap! state
                        (fn [s]
                          (let [fid (:next-facet s)]
                            (reset! id fid)
                            (-> s
                                (assoc-in [:facets fid]
                                          {:assertions #{} :observers []})
                                (update :next-facet inc)))))
                 (result :facet [facet-type (->i64 @id)]))))

           :facet-leave
           (let [facet-id (i64->long payload)]
             (if-not (contains? (:facets @state) facet-id)
               (err :dataspace/unknown-facet "facet handle is not live")
               (let [removed (atom 0)]
                 (swap! state
                        (fn [s]
                          (let [facet (get-in s [:facets facet-id])
                                owned (:assertions facet)
                                obs-ids (set (:observers facet))
                                [kept n]
                                (reduce (fn [[acc n] cell]
                                          (if (contains? owned (:id cell))
                                            [acc (inc n)]
                                            [(conj acc cell) n]))
                                        [[] 0]
                                        (:assertions s))]
                            (reset! removed n)
                            (-> s
                                (assoc :assertions kept)
                                (update :observers
                                        (fn [obs]
                                          (into [] (remove #(contains? obs-ids (:id %)))
                                                obs)))
                                (update :facets dissoc facet-id)))))
                 (result :retracted [retracted-type (->i64 @removed)]))))

           (err :dataspace/unknown-op "unknown dataspace operation"))))}))
