(ns anbf.bots.explorebot
  "a dumb level-exploring bot"
  (:require [clojure.tools.logging :as log]
            [anbf.handlers :refer :all]
            [anbf.player :refer :all]
            [anbf.pathing :refer :all]
            [anbf.monster :refer :all]
            [anbf.position :refer :all]
            [anbf.game :refer :all]
            [anbf.dungeon :refer :all]
            [anbf.tile :refer :all]
            [anbf.delegator :refer :all]
            [anbf.actions :refer :all]))

(defn- fight []
  (reify ActionHandler
    (choose-action [_ {:keys [player] :as game}]
      (loop [enemies (sort-by #(distance player %)
                              (filter #(and (hostile? %)
                                            (> 10 (- (:turn game) (:known %)))
                                            (> 10 (distance player %)))
                                      (-> game curlvl-monsters vals)))]
        (when-let [enemy (first enemies)]
          (log/debug "targetting enemy" enemy)
          (if (adjacent? player enemy)
            (->Move (towards player enemy))
            (or (choose-action (walk enemy) game)
                (do (log/debug "cannot path" enemy)
                    (recur (rest enemies))))))))))

(defn- explorable-tile? [level tile]
  (and (not (monster? (:glyph tile)))
       (or (walkable? tile) (door? tile)) ; XXX locked shops?
       (or (not (:feature tile))
           (some (complement :seen) (neighbors level tile)))))

(defn- dead-end? [level tile]
  (and (walkable? tile)
       (> 2 (count (remove #(or (#{:rock :wall} (:feature %))
                                (diagonal? tile %))
                           (neighbors level tile))))))

(defn- search-dead-end
  [game num-search]
  (let [tile (at-player game)]
    (when (and (= :main (branch-key game))
               (dead-end? (curlvl game) tile)
               (< (:searched tile) num-search))
      (log/debug "searching dead end")
      (->Search))))

(defn- explore []
  (let [current (ref nil)]
    (reify ActionHandler
      (choose-action [_ game]
        (dosync
          (or (search-dead-end game 15)
              (some-> @current (choose-action game))
              (when-let [t (peek (nearest-travelling
                                   game
                                   (partial explorable-tile?
                                            (curlvl game))))]
                (log/debug "chose exploration target" t)
                ;(Thread/sleep 10) ; XXX
                (some-> (ref-set current (travel t))
                        (choose-action game)))
              (ref-set current nil)
              (log/debug "nothing to explore")))))))

(defn- descend []
  (reify ActionHandler
    (choose-action [_ game]
      (if (some #(= :stairs-down (:feature %))
                (apply concat (:tiles (curlvl game))))
        (if-let [path (nearest-travelling game #(= :stairs-down (:feature %)))]
          (if-let [t (peek path)]
            (choose-action (travel t) game)
            (->Descend))
        (log/debug "cannot find stairs"))))))

(defn- random-travel []
  (let [current (ref nil)]
    (reify ActionHandler
      (choose-action [_ game]
        (dosync
          (or (some-> @current (choose-action game))
              (loop []
                (let [target (some->> game curlvl :tiles (apply concat)
                                      (filterv walkable?) rand-nth)]
                  (log/debug "chose random tile " target)
                  (or (some-> (ref-set current (travel target))
                              (choose-action game))
                      (recur))))))))))

; TODO mozna by se dalo zobecnit na nejaky travel-along behavior, parametrizovany funkci na vyber tilu a akci/chovanim k provedeni na nem

(defn- search []
  (reify ActionHandler
    (choose-action [_ game]
      (let [level (curlvl game)]
        (loop [mul 1]
          (or (log/debug "searching for stairs" mul)
              (when-let [t (nearest-travelling
                             game #(and (dead-end? level %)
                                        (< (searched level %) (* mul 30))))]
                (if-let [t (peek t)]
                  (choose-action (travel t) game)
                  (->Search)))
              (log/debug "searching walls" mul)
              (when-let [t (nearest-travelling
                             game #(some (fn [tile]
                                           (and (= :wall (:feature tile))
                                                (< 2 (:y tile) 21)
                                                (< 1 (:x tile) 79)
                                                (< (:searched tile) (* mul 10))
                                                (->> (neighbors level tile)
                                                     (remove :seen) count
                                                     (< 1))))
                                         (neighbors level %)))]
                (if-let [t (peek t)]
                  (choose-action (travel t) game)
                  (->Search)))
              ; TODO push boulders as last resort
              (recur (inc mul))))))))

(defn- pray-for-food []
  (reify ActionHandler
    (choose-action [_ game]
      (if (weak? (:player game))
        (->Pray)))))

(defn init [anbf]
  (-> anbf
      (register-handler (reify ChooseCharacterHandler
                          (choose-character [this]
                            (deregister-handler anbf this)
                            "nsm"))) ; choose samurai
      (register-handler (reify ReallyAttackHandler
                          (really-attack [_ _] false)))
      (register-handler -10 (pray-for-food))
      (register-handler -5 (fight))
      (register-handler 5 (explore))
      (register-handler 4 (descend)) ; XXX +++
      (register-handler 7 (search))
      #_ (register-handler 8 (random-travel))))