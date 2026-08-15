(ns provider.dataspace-test
  "Direct host tests for the dataspace reference provider.

  KIR grant-deny stays in amu (compiler injects this host). This suite proves
  the host contract itself: isolation, facet retract, #cap fail-closed, and
  that dataspace qualifies *alone* without joining the 9-kit closed set."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [provider.conformance :as conformance]
            [provider.dataspace :as dataspace]))

(defn- invoke [p request]
  ((:invoke p) request))

(defn- assert-req [edn facet]
  [dataspace/request-type :assert [dataspace/assert-type edn facet]])

(defn- observe-req [edn facet]
  [dataspace/request-type :observe [dataspace/observe-type edn facet]])

(defn- retract-req [edn facet]
  [dataspace/request-type :retract [dataspace/retract-type edn facet]])

(deftest observe-pattern-binds-and-fires-on-matching-assert
  (let [p (dataspace/provider)
        observed (invoke p (observe-req "[:temperature :room/a ?t]" 0))
        asserted (invoke p (assert-req "[:temperature :room/a 21]" 0))]
    (is (= :matches (second observed)))
    (is (= "[]" (last (nth observed 2))))
    (is (= :asserted (second asserted)))
    (is (= [{'?t 21}] (edn/read-string (last (nth asserted 2)))))
    (let [again (invoke p (observe-req "[:temperature :room/a ?t]" 0))]
      (is (= [{'?t 21}] (edn/read-string (last (nth again 2))))))))

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
      (is (= [] (edn/read-string (last (nth remaining 2))))))))

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
             (edn/read-string (last (nth before 2)))))
      (is (= 1 (last (nth left 2))))
      (is (= [{'?t 21}]
             (edn/read-string (last (nth after 2)))))
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
           (edn/read-string
            (last (nth (invoke p (observe-req
                                  "[:temperature :room/a ?t]" 0)) 2)))))
    (is (= 1 (last (nth (invoke p (retract-req assertion owner-id)) 2))))
    (is (= 0 (last (nth (invoke p [dataspace/request-type
                                    :facet-leave owner-id]) 2))))))

(deftest copied-assertion-edn-does-not-grant-observe
  (let [left (dataspace/provider)
        right (dataspace/provider)
        assertion "[:temperature :room/a 21]"]
    (invoke left (assert-req assertion 0))
    (let [stolen (vec (edn/read-string assertion))
          other (invoke right (observe-req (pr-str stolen) 0))]
      (is (= [:temperature :room/a 21] stolen))
      (is (= [] (edn/read-string (last (nth other 2))))))))

(deftest tagged-cap-literal-cannot-mint-authority
  (let [p (dataspace/provider)
        tagged (invoke p (assert-req "#cap \"dataspace\"" 0))
        observed (invoke p (observe-req "#cap-ref \"dataspace\"" 0))]
    (is (= :dataspace/tagged-rejected (second (nth tagged 2))))
    (is (= :dataspace/tagged-rejected (second (nth observed 2))))))

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
    (is (= [{}] (edn/read-string (last (nth seen 2)))))))

(deftest instances-are-isolated
  (let [left (dataspace/provider)
        right (dataspace/provider)]
    (invoke left (assert-req "[:temperature :room/a 21]" 0))
    (is (= [{'?t 21}]
           (edn/read-string
            (last (nth (invoke left (observe-req "[:temperature :room/a ?t]" 0)) 2)))))
    (is (= []
           (edn/read-string
            (last (nth (invoke right (observe-req "[:temperature :room/a ?t]" 0)) 2)))))))

(deftest contract-mismatch-is-refused
  (let [p (dataspace/provider)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"dataspace request contract mismatch"
         (invoke p [[:record :wrong/type [[:x :i64]]] 0])))))

(deftest dataspace-qualifies-alone-without-joining-the-9-kit-set
  (let [p (dataspace/provider)
        receipt (conformance/validate-suite!
                 {:dataspace/transact dataspace/capability-id}
                 [{:name :dataspace/transact :id dataspace/capability-id
                   :provider p}])
        inventory (edn/read-string
                   (slurp (io/resource
                           "kotoba/lang/provider-conformance-v1.edn")))
        names (set (map :name (:kits inventory)))]
    (is (= 24 dataspace/capability-id))
    (is (= 1 (:capability-count receipt)))
    (is (= [{:name :dataspace/transact :id 24}] (:capabilities receipt)))
    (is (not (contains? names :dataspace))
        "dataspace must not fold into provider-conformance inventory this slice")))
