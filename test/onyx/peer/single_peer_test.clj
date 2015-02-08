(ns onyx.peer.single-peer-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [midje.sweet :refer :all]
            [onyx.peer.task-lifecycle-extensions :as l-ext]
            [onyx.system :refer [onyx-development-env]]
            [onyx.plugin.core-async]
            [onyx.test-helpers :refer [with-env-peers]]
            [onyx.api]))

(def id (java.util.UUID/randomUUID))

(def config (read-string (slurp (clojure.java.io/resource "test-config.edn"))))

(def scheduler :onyx.job-scheduler/greedy)

(def messaging :http-kit)

(def env-config
  {:zookeeper/address (:address (:zookeeper config))
   :zookeeper/server? true
   :zookeeper.server/port (:spawn-port (:zookeeper config))
   :onyx/id id
   :onyx.peer/job-scheduler scheduler
   :onyx.messaging/impl messaging})

(def peer-config
  {:zookeeper/address (:address (:zookeeper config))
   :onyx/id id
   :onyx.peer/inbox-capacity (:inbox-capacity (:peer config))
   :onyx.peer/outbox-capacity (:outbox-capacity (:peer config))
   :onyx.peer/join-failure-back-off 500
   :onyx.peer/job-scheduler scheduler
   :onyx.messaging/impl messaging})

(def n-messages 50)

(def batch-size 10)

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def catalog
  [{:onyx/name :in
    :onyx/ident :core.async/read-from-chan
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :inc
    :onyx/fn :onyx.peer.single-peer-test/my-inc
    :onyx/type :function
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size}

   {:onyx/name :out
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Writes segments to a core.async channel"}])

(def workflow [[:in :inc] [:inc :out]])

(def in-chan (chan (inc n-messages)))

(def out-chan (chan (sliding-buffer (inc n-messages))))

(defmethod l-ext/inject-lifecycle-resources :in
  [_ _] {:core.async/in-chan in-chan})

(defmethod l-ext/inject-lifecycle-resources :out
  [_ _] {:core.async/out-chan out-chan})

(doseq [n (range n-messages)]
  (>!! in-chan {:n n}))

(>!! in-chan :done)

(with-env-peers env-config peer-config 3 
  (fn [] 
    (Thread/sleep 2000)
    (onyx.api/submit-job
      peer-config
      {:catalog catalog :workflow workflow
       :task-scheduler :onyx.task-scheduler/round-robin})
    (time (let [results (doall (repeatedly (inc n-messages) (fn [] (<!! out-chan))))
          expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
      (fact (set (butlast results)) => expected)
      (fact (last results) => :done)))))
