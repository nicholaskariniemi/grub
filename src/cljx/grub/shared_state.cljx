(ns grub.shared-state
  (:require [grub.diff :as diff]
            [grub.message :as message]
            [grub.sync :as sync]
            #+clj [clojure.core.async :as a :refer [<! >! chan go]]
            #+cljs [cljs.core.async :as a :refer [<! >! chan]])
  #+cljs (:require-macros [cljs.core.async.macros :refer [go]]))

;; Server state
(def states (atom []))

(defn make-server-agent 
  ([in out states] (make-server-agent in out states sync/empty-state))
  ([in out states initial-client-state]
     (go (loop [client-state initial-client-state]
           (when-let [msg (<! in)]
             (condp = (:type msg)
               :diff
               (let [states* @states
                     shadow (sync/get-history-state states* (:hash msg))]
                 (if shadow
                   (let [new-states (sync/apply-diff states* (:diff msg))
                         new-shadow (diff/patch-state shadow (:diff msg))
                         {:keys [diff hash]} (sync/diff-states new-states new-shadow)]
                     (reset! states new-states)
                     (>! out (message/diff-msg diff hash))
                     (recur new-shadow))
                   (let [state (sync/get-current-state @states)]
                     (>! out (message/full-sync state))
                     (recur state))))

               :full-sync
               (let [state (sync/get-current-state @states)]
                 (>! out (message/full-sync state))
                 (recur state))

               :new-state
               (let [{:keys [diff hash]} (sync/diff-states (:new-states msg) client-state)]
                 (>! out (message/diff-msg diff hash))
                 (recur client-state))
               (recur client-state)))))))

;; (defn make-client-agent 
;;   ([in out states] (make-client-agent in out states sync/empty-state))
;;   ([in out states initial-server-state]
;;      (a/go-loop [server-state initial-server-state]
;;                 (when-let [msg (<! in)]
;;                   (condp = (:type msg)
;;                     :diff
;;                     (let [states* @states
;;                           shadow (sync/get-history-state states* (:hash msg))]
;;                       (if shadow
;;                         (let [new-states (sync/apply-diff states* (:diff msg))
;;                               new-shadow (diff/patch-state shadow (:diff msg))
;;                               {:keys [diff hash]} (sync/diff-states new-states new-shadow)]
;;                           (reset! states new-states)
;;                           (recur new-shadow))
;;                         (let [state (sync/get-current-state @states)]
;;                           (>! out (message/full-sync state))
;;                           (recur state))))

;;                     :full-sync
;;                     (let [state (:state msg)]
;;                       (reset! states [state])
;;                       (recur state))

;;                     :new-state
;;                     (let [{:keys [diff hash]} (sync/diff-states (:new-states msg) server-state)]
;;                       (>! out (message/diff-msg diff hash)))
;;                     (recur server-state))))))

;; TODO: Remove watch, close up channels properly
(defn sync-new-client! [>client <client]
  (let [client-id (java.util.UUID/randomUUID)
        state-changes (chan)
        state-change-events (a/map< (fn [s] {:type :new-state :new-states s}) state-changes)
        client-events (chan)]
    (add-watch states client-id (fn [_ _ _ new-states] (a/put! state-changes new-states)))
    (a/pipe (a/merge [<client state-change-events]) client-events)
    (make-server-agent client-events >client states)))

(defn init [to-db grubs recipes]
  (reset! states (sync/initial-state grubs recipes))
  (add-watch states :to-db (fn [_ _ old-states new-states] 
                             (a/put! to-db (sync/get-current-state new-states)))))
