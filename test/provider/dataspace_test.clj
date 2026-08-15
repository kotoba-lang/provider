(ns provider.dataspace-test
  "Direct host tests for the dataspace reference provider.

  KIR grant-deny stays in amu (compiler injects this host). This suite proves
  the host contract itself: isolation, facet retract, non-document fail-closed,
  and that dataspace is in the provider-conformance inventory (closed set of
  10 application capabilities in amu; this inventory is the larger host set)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba.kir.value :as value]
            [provider.conformance :as conformance]
            [provider.dataspace :as dataspace]
            [provider.dataspace-kgraph :as kgraph-store]
            [provider.dataspace-store :as store]))

(defn- invoke [p request]
  ((:invoke p) request))

(defn- edn-doc [form]
  (value/document-edn-read (if (string? form) form (pr-str form))))

(defn- doc-edn [doc]
  (edn/read-string (value/document-edn-print doc)))

(defn- assert-req [edn facet]
  [dataspace/request-type :assert [dataspace/assert-type (edn-doc edn) facet]])

(defn- observe-req [edn facet]
  [dataspace/request-type :observe [dataspace/observe-type (edn-doc edn) facet]])

(defn- retract-req [edn facet]
  [dataspace/request-type :retract [dataspace/retract-type (edn-doc edn) facet]])

(deftest abi-assertions-are-documents-not-edn-strings
  (is (= :document (second (first (nth dataspace/assert-type 2)))))
  (is (= :document (second (first (nth dataspace/observe-type 2)))))
  (is (= :document (second (second (nth dataspace/asserted-type 2)))))
  (is (= :document (second (first (nth dataspace/matches-type 2))))))

(deftest observe-pattern-binds-and-fires-on-matching-assert
  (let [p (dataspace/provider)
        observed (invoke p (observe-req "[:temperature :room/a ?t]" 0))
        asserted (invoke p (assert-req "[:temperature :room/a 21]" 0))]
    (is (= :matches (second observed)))
    (is (= [] (doc-edn (last (nth observed 2)))))
    (is (= :asserted (second asserted)))
    (is (= [{'?t 21}] (doc-edn (last (nth asserted 2)))))
    (let [again (invoke p (observe-req "[:temperature :room/a ?t]" 0))]
      (is (= [{'?t 21}] (doc-edn (last (nth again 2))))))))

(deftest facet-exit-retracts-owned-assertions-and-drops-observations
  (let [p (dataspace/provider)
        entered (invoke p [dataspace/request-type :facet-enter true])
        fid (last (nth entered 2))]
    (is (= :facet (second entered)))
    (invoke p (observe-req "[:temperature :room/a ?t]" fid))
    (invoke p (assert-req "[:temperature :room/a 21]" fid))
    (let [left (invoke p [dataspace/request-type :facet-leave fid])
          remaining (invoke p (observe-req "[:temperature :room/a ?t]" 0))]
      (is (= :retracted (second left)))
      (is (= 1 (last (nth left 2))))
      (is (= [] (doc-edn (last (nth remaining 2))))))))

(deftest equal-assertions-have-distinct-facet-ownership
  (let [p (dataspace/provider)
        left-id (last (nth (invoke p [dataspace/request-type :facet-enter true]) 2))
        right-id (last (nth (invoke p [dataspace/request-type :facet-enter true]) 2))
        assertion "[:temperature :room/a 21]"]
    (invoke p (assert-req assertion left-id))
    (invoke p (assert-req assertion right-id))
    (let [before (invoke p (observe-req "[:temperature :room/a ?t]" 0))
          left (invoke p [dataspace/request-type :facet-leave left-id])
          after (invoke p (observe-req "[:temperature :room/a ?t]" 0))]
      (is (= [{'?t 21} {'?t 21}]
             (doc-edn (last (nth before 2)))))
      (is (= 1 (last (nth left 2))))
      (is (= [{'?t 21}]
             (doc-edn (last (nth after 2)))))
      (is (= 1 (last (nth (invoke p [dataspace/request-type
                                      :facet-leave right-id]) 2)))))))

(deftest retract-is-confined-to-the-requested-facet
  (let [p (dataspace/provider)
        owner-id (last (nth (invoke p [dataspace/request-type :facet-enter true]) 2))
        other-id (last (nth (invoke p [dataspace/request-type :facet-enter true]) 2))
        assertion "[:temperature :room/a 21]"]
    (invoke p (assert-req assertion owner-id))
    (is (= 0 (last (nth (invoke p (retract-req assertion other-id)) 2))))
    (is (= 0 (last (nth (invoke p (retract-req assertion 0)) 2))))
    (is (= [{'?t 21}]
           (doc-edn
            (last (nth (invoke p (observe-req
                                  "[:temperature :room/a ?t]" 0)) 2)))))
    (is (= 1 (last (nth (invoke p (retract-req assertion owner-id)) 2))))
    (is (= 0 (last (nth (invoke p [dataspace/request-type
                                    :facet-leave owner-id]) 2))))))

(deftest copied-assertion-document-does-not-grant-observe
  (let [left (dataspace/provider)
        right (dataspace/provider)
        assertion (edn-doc "[:temperature :room/a 21]")]
    (invoke left (assert-req "[:temperature :room/a 21]" 0))
    (let [other (invoke right (observe-req assertion 0))]
      (is (= [:temperature :room/a 21] (doc-edn assertion)))
      (is (= [] (doc-edn (last (nth other 2))))))))

(deftest non-document-assertion-is-rejected
  (let [p (dataspace/provider)
        tagged (invoke p [dataspace/request-type :assert
                          [dataspace/assert-type "#cap \"dataspace\"" 0]])
        observed (invoke p [dataspace/request-type :observe
                            [dataspace/observe-type "#cap-ref \"dataspace\"" 0]])]
    (is (= :dataspace/document-invalid (second (nth tagged 2))))
    (is (= :dataspace/document-invalid (second (nth observed 2))))))

(deftest document-edn-read-rejects-tagged-cap
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"document-edn-read"
       (value/document-edn-read "#cap \"dataspace\"")))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"document-edn-read"
       (value/document-edn-read "#cap-ref \"dataspace\""))))

(deftest forged-facet-handle-is-rejected
  (let [p (dataspace/provider)
        forged (invoke p [dataspace/request-type :facet-leave 99])
        asserted (invoke p (assert-req "[:temperature :room/a 21]" 7))]
    (is (= :dataspace/unknown-facet (second (nth forged 2))))
    (is (= :dataspace/unknown-facet (second (nth asserted 2))))))

(deftest cap-shaped-map-is-inert-data-not-a-grant
  (let [p (dataspace/provider)
        forged-map "{:cap/kind :dataspace/transact :cap/resource \"ds\" :cap/provenance []}"
        stored (invoke p (assert-req forged-map 0))
        seen (invoke p (observe-req "{:cap/kind :dataspace/transact}" 0))]
    (is (= :asserted (second stored)))
    (is (= [{}] (doc-edn (last (nth seen 2)))))))

(deftest instances-are-isolated
  (let [left (dataspace/provider)
        right (dataspace/provider)]
    (invoke left (assert-req "[:temperature :room/a 21]" 0))
    (is (= [{'?t 21}]
           (doc-edn
            (last (nth (invoke left (observe-req "[:temperature :room/a ?t]" 0)) 2)))))
    (is (= []
           (doc-edn
            (last (nth (invoke right (observe-req "[:temperature :room/a ?t]" 0)) 2)))))))

(deftest contract-mismatch-is-refused
  (let [p (dataspace/provider)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"dataspace request contract mismatch"
         (invoke p [[:record :wrong/type [[:x :i64]]] 0])))))

(deftest dataspace-is-in-provider-conformance-inventory
  (let [p (dataspace/provider)
        receipt (conformance/validate-suite!
                 {:dataspace/transact dataspace/capability-id}
                 [{:name :dataspace/transact :id dataspace/capability-id
                   :provider p}])
        inventory (edn/read-string
                   (slurp (io/resource
                           "kotoba/lang/provider-conformance-v1.edn")))
        names (set (map :name (:kits inventory)))
        kit (edn/read-string
             (slurp (io/resource
                     "kotoba/lang/capability-kits/dataspace-v1.edn")))]
    (is (= 24 dataspace/capability-id))
    (is (= 1 (:capability-count receipt)))
    (is (= [{:name :dataspace/transact :id 24}] (:capabilities receipt)))
    (is (contains? names :dataspace)
        "dataspace is in provider-conformance inventory")
    (is (= :document (get-in kit [:limits :assertion])))
    (is (= :document
           (second (first (nth (second (first (nth (:request kit) 2))) 2)))))))

(defn- dropping-facet-leave-store
  "Broken store: facet-leave drops observers and the facet, not assertions."
  []
  (let [inner (store/memory-store)
        q (:q inner)
        tx! (:transact! inner)]
    {:q q
     :transact!
     (fn [tx]
       (if (not= :facet-leave (:op tx))
         (tx! tx)
         (let [owned (filterv #(= (:id tx) (:facet %)) (q {:op :assertions}))
               result (tx! tx)]
           (doseq [cell owned]
             (tx! {:op :assert :value (:value cell) :facet 0}))
           result)))}))

(defn- facet-leave-retracts?
  [p]
  (let [entered (invoke p [dataspace/request-type :facet-enter true])
        fid (last (nth entered 2))]
    (invoke p (observe-req "[:temperature :room/a ?t]" fid))
    (invoke p (assert-req "[:temperature :room/a 21]" fid))
    (let [left (invoke p [dataspace/request-type :facet-leave fid])
          remaining (invoke p (observe-req "[:temperature :room/a ?t]" 0))]
      (and (= :retracted (second left))
           (= 1 (last (nth left 2)))
           (= [] (doc-edn (last (nth remaining 2))))))))

(defn- assert-retract-observe-hold?
  [p]
  (invoke p (assert-req "[:temperature :room/a 21]" 0))
  (let [seen (invoke p (observe-req "[:temperature :room/a ?t]" 0))
        retracted (invoke p (retract-req "[:temperature :room/a 21]" 0))
        after (invoke p (observe-req "[:temperature :room/a ?t]" 0))]
    (and (= [{'?t 21}] (doc-edn (last (nth seen 2))))
         (= 1 (last (nth retracted 2)))
         (= [] (doc-edn (last (nth after 2)))))))

(deftest default-memory-store-is-the-inject-boundary
  (is (store/store? (store/memory-store)))
  (is (store/store? (kgraph-store/store)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"dataspace store requires :q and :transact!"
       (dataspace/provider {:store {:q identity}}))))

(deftest injected-kgraph-store-honors-assert-retract-observe-facet-leave
  (let [p (dataspace/provider {:store (kgraph-store/store)})]
    (is (assert-retract-observe-hold? p))
    (is (facet-leave-retracts? p))))

(deftest default-memory-store-still-honors-facet-leave
  (is (facet-leave-retracts? (dataspace/provider)))
  (is (assert-retract-observe-hold? (dataspace/provider))))

(deftest store-that-drops-facet-leave-retracts-fails-closed
  (is (not (facet-leave-retracts?
            (dataspace/provider {:store (dropping-facet-leave-store)})))))
