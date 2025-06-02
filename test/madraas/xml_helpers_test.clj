(ns madraas.xml-helpers-test
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [madraas.xml-helpers :as xh]))

(xml/alias-uri 'example "http://example.com/"
               'xsi "http://www.w3.org/2001/XMLSchema-instance")

(deftest get-in-xml-test
  (testing "XML lookup path"
    (is (= "Test 123"
           (-> (xml/sexp-as-element
                [::example/an-element
                 [::example/another-element
                  "Test 123"]])
               (xh/get-in-xml [::example/an-element ::example/another-element]))))

    (is (= ["Test 123"]
           (-> (xml/sexp-as-element
                [::example/an-element
                 [::example/an-interloper
                  "Test 321"]
                 [::example/another-element
                  "Test 123"]])
               (xh/get-in-xml [::example/an-element ::example/another-element]))))

    (is (= ["Test 123" "Test 456"]
           (-> (xml/sexp-as-element
                [::example/an-element
                 [::example/another-element
                  "Test 123"]
                 [::example/an-interloper
                  "Test 321"]
                 [::example/another-element
                  "Test 456"]])
               (xh/get-in-xml [::example/an-element ::example/another-element]))))))

(deftest xsi-type-test
  (testing "Extracting XSI type from an element"
    (is (= "ns1:MyType"
           (-> (xml/sexp-as-element
                [::example/an-element
                 {::xsi/type "ns1:MyType"}])
               xh/xsi-type))))
  (testing "Extracting XSI type and re-mapping to a local alias"
    (is (= "my-alias:MyType"
           (-> (xml/sexp-as-element
                [::example/an-element
                 {"xmlns:ns1" "http://example.com/"
                  ::xsi/type "ns1:MyType"}])
               ;; Hacker navnerommet ns1 inn i XMLen
               xml/emit-str
               xml/parse-str
               (xh/xsi-type {"http://example.com/" "my-alias"}))))))

(deftest get-in-xml-test
  (testing "XML select tags"
    (is (= {::example/eldstemann "Test 123"
            ::example/attpåklatten "Test 789"}
           (-> (xml/sexp-as-element
                [::example/barn
                 [::example/eldstemann "Test 123"]
                 [::example/dritten-i-midten "Test 456"]
                 [::example/attpåklatten "Test 789"]])
               (xh/get-in-xml [::example/barn])
               (xh/select-tags [::example/eldstemann
                                ::example/attpåklatten]))))))
