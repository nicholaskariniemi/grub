(ns grub.integration-test
  (:require [clj-webdriver.taxi :as taxi]
            [clj-webdriver.core :as webdriver]
            [clojure.test :as test]))

(def site-url "http://localhost:3000")

(defn add-grub [driver grub-text]
  (taxi/input-text driver "#add-grub-input" grub-text)
  (taxi/click driver {:text "Add"}))

(defn get-driver [url]
  (webdriver/start {:browser :chrome} url))

(defn get-rand-grub []
  (str "testgrub" (rand-int 10000)))

(defn test-adding-grubs [url driver1 driver2]
  (taxi/to driver1 url)
  (taxi/to driver2 url)
  (let [grubs (repeatedly 4 get-rand-grub)]
    (doseq [grub grubs]
      (add-grub driver1 grub))
    (doseq [grub grubs]
      (test/is (taxi/find-element driver2 {:text grub})
               "Added grubs should appear in other browser"))))

(defn test-grubs-are-stored-on-server [url driver]
  (taxi/to driver url)
  (let [grubs (repeatedly 4 get-rand-grub)]
    (doseq [grub grubs]
      (add-grub driver grub))
    (taxi/refresh driver)
    (doseq [grub grubs]
      (test/is (taxi/find-element driver {:text grub})
               "Previously added grubs should be loaded on refresh"))))
  

(defn run [port]
  (let [site-url (str "http://localhost:" port)]
    (println "Starting integration test")
    (let [driver1 (get-driver site-url)
          driver2 (get-driver site-url)]
      (test-adding-grubs site-url driver1 driver2)
      (test-grubs-are-stored-on-server site-url driver1)
      (taxi/quit driver1)
      (taxi/quit driver2))))
