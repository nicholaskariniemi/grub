(ns grub.view
  (:require [dommy.core :as dommy]
            [cljs.core.async :as a :refer [<! >! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]])
  (:require-macros [grub.macros :refer [log logs]]
                   [dommy.macros :refer [sel1]]
                   [cljs.core.async.macros :refer [go]]))

(defn listen
  ([el type] (listen el type nil))
  ([el type f] (listen el type f (chan)))
  ([el type f out]
     (let [push-fn (fn [e] (when f (f e)) (go (>! out e)))
           unlisten #(do (dommy/unlisten! el type push-fn)
                         (a/close! out))]
         (dommy/listen! el type push-fn)
         {:chan out :unlisten unlisten})))

(def ENTER-KEYCODE 13)

(defn listen-once
  ([el type] (listen el type nil))
  ([el type f] (listen el type f (chan)))
  ([el type f out]
     (let [push-fn (fn [e] (when f (f e)) (go (>! out e)))
           unlisten #(do (dommy/unlisten! el type push-fn)
                         (a/close! out))]
         (dommy/listen-once! el type push-fn)
         {:chan out :unlisten unlisten})))

(defn get-away-clicks [elem]
  (let [{c :chan unlisten :unlisten} (listen (sel1 :body) :click)
        filtered-chan (a/filter< #(not (dommy/descendant? (.-target %) elem)) c)]
    {:unlisten unlisten :chan filtered-chan}))

(defn get-clicks [elem]
  (listen elem :click))

(defn get-enters [elem]
  (let [{c :chan unlisten :unlisten} (listen elem :keyup)
        filtered-chan (a/filter< #(= (.-which %) ENTER-KEYCODE) c)]
    {:unlisten unlisten
     :chan filtered-chan}))

(defn get-ctrl-enters []
  (let [{c :chan unlisten :unlisten} (listen (sel1 :body) :keyup)
        filtered-chan (a/filter< #(and (= (.-which %) ENTER-KEYCODE)
                                       (.-ctrlKey %))
                                 c)]
    {:chan filtered-chan :unlisten unlisten}))

(defn get-body-enters []
  (get-enters (sel1 :body)))

(def add-grub-btn 
  [:button.btn.btn-primary {:id "add-grub-btn" :type "button"} "Add"])

(def clear-all-btn
  [:button.btn.hidden.pull-right 
   {:id "clear-all-btn" :type "button"}
   "Clear all"])

(defn clear-grubs! []
  (dommy/set-html! (sel1 :#grub-list) ""))

(defn get-grub-completed-glyph [completed]
  (if completed
    [:span.glyphicon.glyphicon-check]
    [:span.glyphicon.glyphicon-unchecked]))

(defn make-grub-node [id grub completed]
  [:li.list-group-item.grub-item 
   {:id id
    :class (when completed "completed")} 
   [:span.grub-static
    (get-grub-completed-glyph completed)
    [:span.grub-text grub]]
   [:input.grub-input {:type "text" :value grub}]])

(defn grubs-selector []
  [(sel1 :#grub-list) :.grub-item])

(defn make-recipe-node 
  ([id name grubs] (make-recipe-node id name grubs false))
  ([id name grubs new-recipe]
     [:div.panel.panel-default.recipe-panel
      {:id id}
      [:div.panel-heading.recipe-header
       [:input.form-control.recipe-header-input 
        {:id "recipe-name"
         :type "text" 
         :placeholder "Grub pie"
         :value name}]
       (when-not new-recipe 
         [:button.btn.btn-primary.btn-sm.recipe-add-grubs-btn {:type "button"} "Add Grubs"])]
      [:div.panel-body.recipe-grubs.hidden
       [:textarea.form-control.recipe-grubs-input
        {:id "recipe-grubs"
         :rows 3 
         :placeholder "2 grubs"}
        grubs]
       [:button.btn.btn-primary.hidden.pull-right.recipe-btn.recipe-done-btn
        {:type "button"} "Done"]]]))

(def new-recipe (make-recipe-node "new-recipe" "" "" true))

(def new-recipe-done-btn 
  (sel1 new-recipe ".recipe-done-btn"))

(defn recipes-selector []
  [(sel1 :#recipe-list) :.recipe-panel])

(defn recipe-done-btns-selector []
  [(sel1 :body) :.recipe-done-btn])

(defn recipe-done-btn-selector [recipe-elem]
  (sel1 recipe-elem :.recipe-done-btn))

(defn recipe-add-grubs-btns-selector []
  [(sel1 :body) :.recipe-add-grubs-btn])

(defn grub-list-template [grubs]
  (for [grub grubs] 
    (make-grub-node (:id grub) (:grub grub) (:completed grub))))

(defn render-grub-list [grubs]
  (let [grub-list (sel1 :#grub-list)]
    (aset grub-list "innerHTML" "")
    (dommy/replace-contents! grub-list (grub-list-template grubs))))

(defn get-add-grub-text []
  (dommy/value (sel1 :#add-grub-input)))

(defn clear-add-grub-text []
  (dommy/set-value! (sel1 :#add-grub-input) ""))

(defn get-recipe-add-grubs-clicks []
  (->> (:chan (listen (recipe-add-grubs-btns-selector) :click))
       (a/map< #(dommy/closest (.-selectedTarget %) :.recipe-panel))))

(defn get-edit-recipe-input-click []
  (->> (:chan (listen-once (recipes-selector) :click))
       (a/filter< #(not (dommy/has-class? (.-selectedTarget %) :btn)))
       (a/map< #(.-selectedTarget %))))

(defprotocol IHideable
  (-hide! [this])
  (-show! [this]))

(defprotocol IGrub
  (-activate! [this])
  (-deactivate! [this])

  (-id [this])
  (-grub-text [this])

  (-complete! [this])
  (-uncomplete! [this])
  (-completed? [this])

  (-set-editing! [this])
  (-unset-editing! [this])
  (-editing? [this])
  (-update-grub! [this grub]))

(defprotocol IRecipe
  (-expand! [this])
  (-unexpand! [this])
  
  (-update-recipe! [this])
  
  (-get-name [this])
  (-get-grubs-str [this])
  (-get-grubs [this]))

(defprotocol IClearable
  (-clear! [this]))

(extend-type js/HTMLElement
  IHideable
  (-hide! [this]
    (dommy/add-class! this :hidden))
  (-show! [this]
    (dommy/remove-class! this :hidden)))


(extend-type js/HTMLElement
  IGrub
  (-activate! [this]
    (dommy/add-class! this :grub-active))
  (-deactivate! [this]
    (dommy/remove-class! this :grub-active))

  (-id [this]
    (.-id this))
  (-grub-text [this]
    (.-value (sel1 this :.grub-input)))

  (-complete! [this]
    (dommy/add-class! this :completed)
    (dommy/replace! (sel1 this ".glyphicon") 
                     (get-grub-completed-glyph true)))
  (-uncomplete! [this]
    (dommy/remove-class! this :completed)
    (dommy/replace! (sel1 this ".glyphicon") 
                     (get-grub-completed-glyph false)))
  (-completed? [this]
    (dommy/has-class? this :completed))

  (-set-editing! [this]
    (-deactivate! this)
    (dommy/add-class! this :edit)
    (.focus (sel1 this :input)))
  (-unset-editing! [this]
    (dommy/remove-class! this :edit))
  (-editing? [this]
    (dommy/has-class? this :edit)))

(defrecord Grub [elem id grub completed]
  dommy.template/PElement
  (-elem [this] elem)

  IGrub
  (-set-editing! [this] (-set-editing! elem))
  (-unset-editing! [this] (-unset-editing! elem))
  (-editing? [this] (-editing? elem))

  (-complete! [this] (-complete! elem))
  (-uncomplete! [this] (-uncomplete! elem))
  (-completed? [this] (-completed? elem))
  
  (-set-editing! [this] (-set-editing! elem))
  (-unset-editing! [this] (-unset-editing! elem))
  (-editing? [this] (-editing? elem))
  
  (-update-grub! [this grub]
    (dommy/set-text! (sel1 elem ".grub-text") grub)
    (dommy/set-value! (sel1 elem ".grub-input") grub)))

(defn make-new-grub [id grub completed]
  (let [node (make-grub-node id grub completed)
        grub (Grub. node id grub completed)
        grub-list (sel1 :#grub-list)]
    grub))

(defn clear-new-grub-input! []
  (dommy/set-value! (sel1 :#add-grub-input) ""))

(defn focus-new-grub-input! []
  (.focus (sel1 :#add-grub-input)))

(extend-type js/HTMLDivElement
  IRecipe
  (-expand! [this]
    (dommy/remove-class! (sel1 this ".recipe-grubs") :hidden)
    (dommy/remove-class! (sel1 this ".recipe-done-btn") :hidden))
  (-unexpand! [this]
    (dommy/add-class! (sel1 this ".recipe-grubs") :hidden)
    (dommy/add-class! (sel1 this ".recipe-done-btn") :hidden))

  (-get-name [this]
    (dommy/value (sel1 this :#recipe-name)))
  (-get-grubs-str [this]
    (dommy/value (sel1 this :#recipe-grubs)))
  (-get-grubs [this]
    (let [split-grubs (clojure.string/split-lines (-get-grubs-str this))]
      (when split-grubs (into [] split-grubs)))))


(extend-type js/HTMLDivElement
  IClearable
  (-clear! [this]
    (dommy/set-value! (sel1 this "#recipe-name") "")
    (dommy/set-value! (sel1 this "#recipe-grubs") "")))

(defrecord Recipe [elem id name grubs]
  dommy.template/PElement
  (-elem [this] elem)

  IRecipe
  (-expand! [this] (-expand! elem))
  (-unexpand! [this] (-unexpand! elem))

  (-clear! [this] (-clear! elem))

  (-update-recipe! [this]
    (dommy/set-value! (sel1 this :#recipe-name) name)
    (dommy/set-text! (sel1 this :#recipe-grubs) grubs)))

(defn add-new-recipe! [id name grubs]
  (let [node (make-recipe-node id name grubs)
        recipe (Recipe. node id name grubs)
        recipe-list (sel1 :#recipe-list)]
    (dommy/append! recipe-list recipe)
    recipe))

(defn grub-view [grub-state owner]
  (reify
    om/IRender
    (render [this]
      (let [{:keys [id grub completed]} grub-state]
        (html
         [:li.list-group-item.grub-item 
          {:id id
           :class (when completed "completed")} 
          [:span.grub-static
           (get-grub-completed-glyph completed)
           [:span.grub-text grub]]
          [:input.grub-input {:type "text" :value grub}]])))))

(defn get-grub-ingredient [grub]
  (let [text (clojure.string/lower-case (:grub grub))
        match (re-find #"[a-z]{3}.*$" text)]
    match))

(defn sort-grubs [grubs]
  (sort-by (juxt :completed get-grub-ingredient) (vals grubs)))

(defn app-view [state owner]
  (reify
    om/IRender
    (render [this]
      (let [sorted-grubs (sort-grubs (:grubs state))]
        (logs "render app-view state:" state)
        (logs "render app-view owner:" owner)
        (logs "render grubs:" sorted-grubs)
        (html 
         [:div.container
          [:div.row
           [:div.col-sm-6.leftmost-column
            [:h3 "Grub List"]
            [:div.input-group.add-grub-input-form
             [:span.input-group-btn
              [:input.form-control#add-grub-input {:type "text" :placeholder "2 grubs"}]]
             [:button.btn.btn-primary {:id "add-grub-btn" :type "button"} "Add"]]
            [:ul#grub-list.list-group
             (for [grub (vals (:grubs state))]
               (om/build grub-view grub))]
            [:button.btn.hidden.pull-right 
             {:id "clear-all-btn" :type "button"}
             "Clear all"]]
           [:div.col-sm-6
            [:h3.recipes-title "Recipes"]
            [:div.panel.panel-default.recipe-panel
             [:div.panel-heading.recipe-header
              [:input.form-control.recipe-header-input 
               {:id "recipe-name"
                :type "text" 
                :placeholder "Grub pie"}]
              [:button.btn.btn-primary.btn-sm.recipe-add-grubs-btn {:type "button"} "Add Grubs"]]
             [:div.panel-body.recipe-grubs.hidden
              [:textarea.form-control.recipe-grubs-input
               {:id "recipe-grubs"
                :rows 3 
                :placeholder "2 grubs"}]
              [:button.btn.btn-primary.hidden.pull-right.recipe-btn.recipe-done-btn
               {:type "button"} "Done"]]]
            [:ul#recipe-list.list-group.recipe-list]]]])))))
    
(defn render-body [state]
  (logs state)
  (om/root app-view state {:target (.getElementById js/document "container")}))
