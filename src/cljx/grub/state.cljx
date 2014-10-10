(ns grub.state
  (:require [grub.diff :as diff]
            [grub.message :as message]
            [grub.sync :as sync]
            #+clj [clojure.core.async :as a :refer [<! >! chan go]]
            #+cljs [cljs.core.async :as a :refer [<! >! chan]])
  #+cljs (:require-macros [grub.macros :refer [log logs]]
                          [cljs.core.async.macros :refer [go]]))

(defmulti handle-message (fn [event] (:type event)))

(defmethod handle-message :diff [{:keys [hash diff >remote states shadow client?] :as msg}]
  (let [states* @states
        shadow (sync/get-history-state states* hash)]
    (if shadow
      (let [new-states (sync/apply-diff states* diff)
            new-shadow (diff/patch-state shadow diff)
            {new-diff :diff new-hash :hash} (sync/diff-states (sync/get-current-state new-states) new-shadow)]
        (if client?
          (do (reset! states (sync/new-state (sync/get-current-state new-states)))
              new-shadow)
          (do (when-not (= states* new-states) (reset! states new-states))
              (when-not (sync/empty-diff? diff)
                (a/put! >remote (message/diff-msg new-diff new-hash)))
              (sync/get-current-state new-states))))
      (if client?
        (do (a/put! >remote message/full-sync-request)
            shadow)
        (let [state (sync/get-current-state states*)]
          (a/put! >remote (message/full-sync state))
          state))))),

(defmethod handle-message :full-sync-request [{:keys [states >remote]}]
  (let [state (sync/get-current-state @states)]
    (a/put! >remote (message/full-sync state))
    state))

(defmethod handle-message :full-sync [{:keys [state states]}]
  (reset! states (sync/new-state state))
  state)

(defmethod handle-message :new-state [{:keys [state states shadow >remote]}]
  (let [{:keys [diff hash]} (sync/diff-states state shadow)]
    (when-not (sync/empty-diff? diff)
      (a/put! >remote (message/diff-msg diff hash)))
    shadow))

(defn make-agent 
  ([client? <remote >remote states] (make-agent client? <remote >remote states sync/empty-state))
  ([client? <remote >remote states initial-shadow]
     (let [msg->event (fn [msg shadow] 
                        (assoc msg 
                          :>remote >remote :states states
                          :client? client? :shadow shadow))]
       (go (loop [shadow initial-shadow]
             (when-let [msg (<! <remote)]
               (let [event (msg->event msg shadow)]
                 (recur (handle-message event)))))))))

(defn make-server-agent
  ([in out states] (make-agent false in out states))
  ([in out states initial-shadow] (make-agent false in out states initial-shadow)))

(defn make-client-agent
  ([in out states] (make-agent true in out states))
  ([in out states initial-shadow] (make-agent true in out states initial-shadow)))

(def states (atom []))
(def empty-state sync/empty-state)

#+clj
(defn sync-new-client! [>client <client]
  (let [client-id (java.util.UUID/randomUUID)
        state-changes (chan)
        state-change-events (a/map< (fn [s] {:type :new-state :state s}) state-changes)
        client-events (chan)]
    (add-watch states client-id (fn [_ _ _ new-states] 
                                  (a/put! state-changes (sync/get-current-state new-states))))
    (a/go-loop []
               (let [[val _] (a/alts! [<client state-change-events])]
                 (if val
                   (do (>! client-events val)
                       (recur))
                   (do (remove-watch states client-id)
                       (a/close! <client)
                       (a/close! state-change-events)))))
    (make-server-agent client-events >client states)))

#+clj
(defn init-server [to-db initial-state]
  (reset! states (sync/new-state initial-state))
  (add-watch states :to-db (fn [_ _ old-states new-states] 
                             (a/put! to-db (sync/get-current-state new-states)))))

#+cljs
(defn init-client [<remote >remote <view >view]
  (let [states (atom (sync/initial-state {} {}))]
    (add-watch states :render (fn [_ _ _ new-states]
                                (let [new-state (sync/get-current-state new-states)]
                                  (a/put! >view new-state))))
    (a/pipe (a/map< (fn [s] 
                      (swap! states sync/add-history-state s)
                      {:type :new-state :state s}) <view) <remote)
    (make-client-agent <remote >remote states)
    (a/put! >remote message/full-sync-request)))
