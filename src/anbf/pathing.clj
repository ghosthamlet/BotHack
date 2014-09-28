(ns anbf.pathing
  (:require [clojure.data.priority-map :refer [priority-map-keyfn]]
            [clojure.tools.logging :as log]
            [clojure.set :refer [intersection]]
            [anbf.action :refer :all]
            [anbf.actions :refer :all]
            [anbf.position :refer :all]
            [anbf.monster :refer :all]
            [anbf.delegator :refer :all]
            [anbf.dungeon :refer :all]
            [anbf.itemid :refer :all]
            [anbf.level :refer :all]
            [anbf.player :refer :all]
            [anbf.util :refer :all]
            [anbf.tile :refer :all]))

(defn base-cost [level dir tile opts]
  {:pre [(and (some? level) (some? dir) (some? tile))]}
  (cond-> 1
    (and (:prefer-items opts) (not (:new-items tile))) (+ 0.5)
    (and (:prefer-items opts) (:pick opts) (boulder? tile)
         (:new-items tile)) (- 5) ; partially negate digging penalization
    (trap? tile) (+ 15) ; TODO trap types
    (diagonal dir) (+ 0.1)
    (and (unknown? tile) (not (:seen tile))) (+ 6)
    (not (stairs? tile)) (+ 0.1)
    (not (or (:dug tile) (:walked tile))) (+ 0.2)
    (and (not (:walked tile)) (floor? tile)) (+ 0.5)))

(defn diagonal-walkable? [game tile]
  (not (door-open? tile)))

(defn- a*
  "Move-fn must always return non-negative cost values, target tile may not be passable, but will always be included in the path"
  ([from to move-fn] (a* from to move-fn nil))
  ([from to move-fn max-steps]
   (log/debug "a*")
   (loop [closed {}
          open (priority-map-keyfn first (position from) [0 0])]
     (if-not (empty? open)
       (let [[node [total dist prev]] (peek open)
             path (conj (closed prev []) node)
             delta (distance node to)]
         (cond
           (zero? delta) (subvec path 1)
           (and (= 1 delta)
                (not-any? #(move-fn % to)
                          (neighbors to))) (conj (subvec path 1) to)
           (and max-steps (< max-steps (+ delta (count path)))) nil
           :else (recur (assoc closed node path)
                        (merge-with
                          (partial min-key first)
                          (pop open)
                          (into {} (for [nbr (remove closed (neighbors node))
                                         :let [[cost action] (move-fn node nbr)]
                                         :when (some? action)]
                                     (let [new-dist (int (+ dist cost))]
                                       [nbr [(+ new-dist (distance nbr to))
                                             new-dist node]])))))))))))

(defn- dijkstra
  ([from goal? move-fn] (dijkstra from goal? move-fn nil))
  ([from goal? move-fn max-steps]
   (log/debug "dijkstra")
   (loop [closed {}
          open (priority-map-keyfn first (position from) [0])]
     (if-let [[node [dist prev]] (peek open)]
       (let [path (conj (closed prev []) node)]
         (cond
           (goal? node) (subvec path 1)
           (and max-steps (< max-steps (count path))) nil
           :else (recur
                   (assoc closed node path)
                   (merge-with (partial min-key first)
                               (pop open)
                               (into {}
                                   (for [nbr (remove closed (neighbors node))
                                         :let [[cost action] (move-fn node nbr)]
                                         :when (some? action)]
                                     [nbr [(+ dist cost) node]]))))))))))

(defn- unexplored? [tile]
  (and (not (boulder? tile)) (unknown? tile)))

(defn- narrow?
  "Only works for adjacent diagonals"
  ([level from to]
   (narrow? level from to false))
  ([level from to soko?]
   (every? #(or ((some-fn rock? wall?) %)
                (and soko? (boulder? %)))
           (intersection (set (straight-neighbors level from))
                         (set (straight-neighbors level to))))))

(defn- edge-passable-walking? [game level from-tile to-tile]
  (or (straight (towards from-tile to-tile))
      (and (diagonal-walkable? game from-tile)
           (diagonal-walkable? game to-tile)
           (or (not= :sokoban (branch-key game level))
               (not (narrow? level from-tile to-tile true)))
           (or (not (narrow? level from-tile to-tile))
               (not (:thick (:player game)))))))

(defn passable-walking?
  "Only needs Move action, no door opening etc., will path through monsters and unexplored tiles"
  [game level from-tile to-tile]
  (and (walkable? to-tile)
       (edge-passable-walking? game level from-tile to-tile)))

(defn needs-levi? [tile]
  (#{:water :lava :ice :hole :trapdoor} (:feature tile)))

(defn- random-move [{:keys [player] :as game} level]
  (some->> (neighbors level player)
           (remove (partial monster-at level))
           (filterv #(or (passable-walking? game level (at level player) %)
                         (unexplored? %)))
           random-nth
           (towards player)
           ->Move
           (with-reason "random direction")))

(defn fidget
  "Move around randomly or wait to make a peaceful move out of the way"
  ([game] (fidget game (curlvl game)))
  ([game level] (fidget game level nil))
  ([{:keys [player] :as game} level target]
   (with-handler priority-top
     (fn [anbf]
       (if target
         (reify AboutToChooseActionHandler
           (about-to-choose [_ new-game]
             (if-let [m (curlvl-monster-at new-game target)]
               (when (:peaceful m) ; target did not move
                 (log/debug "blocked by peaceful" target)
                 (if-not (and (shopkeeper? m) ; shks may need more patience
                              (shop? (at-player new-game)))
                   (swap! (:game anbf) update-curlvl-at target
                          update-in [:blocked] (fnil inc 0)))))))))
     (with-reason "fidgeting to make peacefuls move"
       (or (random-move game level)
           (->Search))))))

(defn- safe-from-guards
  "Much more pessimistic than this could be, but just enough to handle the most usual corner-case where the only door in the first room is locked."
  [level]
  (not-any? #(-> % :type :tags :guard) (vals (:monsters level))))

(defn dare-destroy? [level tile]
  (or (boulder? tile)
      (and (or (not ((:tags level) :minetown))
               (safe-from-guards level))
           (not (shop? tile)))))

(defn- blocked-door
  "If this is a kickable door blocked from one side, return direction from which to kick it"
  [level tile]
  (if-let [ws (seq (filter walkable? (straight-neighbors level tile)))]
    (let [w (first ws)
          dir (towards tile w)
          o (in-direction level tile (opposite dir))]
      (if (and (empty? (:items tile)) ; don't try to break blocked doors
               (= 1 (count ws))
               (some (every-pred walkable?
                                 (complement blank?))
                     (intersection
                       (set (diagonal-neighbors level tile))
                       (set (straight-neighbors level o)))))
        dir))))

(defn- kickable-door? [level tile opts]
  (and (door? tile)
       (not (:walking opts))
       (dare-destroy? level tile)
       (not (item? tile)))) ; don't try to break blocked doors

(defn- without-levitation [game action]
  ; XXX doesn't work for intrinsic levitation
  (if-let [[slot _] (have-levi-on game)]
    (with-reason "action" (typekw action) "forbids levitation"
      (remove-use game slot))
    action))

(defn- kick-door [{:keys [player] :as game} level tile dir]
  (if (door-open? tile)
    [8 (with-reason "closing door to kick it" (->Close dir))]
    (if (:leg-hurt player)
      [30 (with-reason "waiting out :leg-hurt" ->Search)]
      [6 (without-levitation game (->Kick dir))])))

(defn can-unlock? [game]
  true) ; not poly'd...

(defn diggable-walls?
  "Are the walls diggable on this level?"
  [game level]
  (and ;(not= "Home 1" (:dlvl game))
       (not ((:tags level) :rogue))
       (not (:undiggable (:blueprint level)))
       (not (#{:vlad :astral :sokoban :quest} (branch-key game)))))

(defn- enter-shop [game]
  ; TODO stash rather than drop pick if we have a bag
  (or (if-let [[slot _] (have game #(and (#{"pick-axe" "dwarvish mattock"}
                                                       (item-name game %))
                                         (or (not= :cursed (:buc %))
                                             (not (:in-use %)))))]
        [2 (with-reason "dropping pick to enter shop" (->Drop slot))])
      (if-let [[slot _] (have game #(and (= "ring of invisibility"
                                            (item-name game %))
                                         (:in-use %)
                                         (can-remove? game %)))]
        [2 (with-reason "removing invis to enter shop" (->Remove slot))])
      (if-let [[slot _] (have game #(and (= "cloak of invisibility"
                                            (item-name game %))
                                         (:in-use %)
                                         (can-remove? game %)))]
        [2 (with-reason "taking off invis to enter shop" (->TakeOff slot))])))

(defn move
  "Returns [cost Action] for a move, if it is possible"
  ([game level from to]
   (move game level from to {}))
  ([game level from to opts]
   (let [to-tile (at level to)
         from-tile (at level from)
         dir (towards from to-tile)
         monster (monster-at level to)]
     (some-> (or (if (and (passable-walking? game level from-tile to-tile)
                          (or (:feature to-tile) (not (:explored opts)))
                          (not (and (kickable-door? level to-tile opts)
                                    (blocked-door level to-tile))))
                   (if monster
                     (if (:peaceful monster)
                       (if ((fnil <= 0) (:blocked to-tile) 25)
                         [50 (with-reason "peaceful blocker" monster
                               (fidget game level to-tile))]) ; hopefully will move
                       [6 (with-reason "pathing through" monster
                            (->Move dir))])
                     (or (and (shop? to-tile)
                              (not (shop? from-tile))
                              (enter-shop game))
                         (if-not (or (and (:levi opts) (needs-levi? to-tile))
                                     (and (:no-traps opts) (trap? to-tile)))
                           [0 (->Move dir)])))) ; trapdoors/holes are escapable
                 (if (kickable-door? level from-tile opts)
                   (if-let [odir (blocked-door level from-tile)]
                     (if (monster-at level (in-direction from-tile odir))
                       [3 (with-reason
                            "waiting for monster to move to kick door at my position"
                            (->Search))]

                       [1 (with-reason "moving to kick blocked door at my position"
                            (->Move odir))])))
                 (if (and (edge-passable-walking? game level from-tile to-tile)
                          (needs-levi? to-tile)
                          (not monster) (not (boulder? to-tile)))
                   (if-let [[slot item] (:levi opts)]
                     (if (:in-use item)
                       [1 (with-reason "assuming levitation" (->Move dir))]
                       [4 (with-reason "need levitation for next move"
                            (make-use game slot))])))
                 (if (and (door? to-tile) (not monster) (not (:walking opts)))
                   (or (if (door-secret? to-tile)
                         [10 (->Search)]) ; TODO stethoscope
                       (and (kickable-door? level to-tile opts)
                            (blocked-door level to-tile)
                            (->> (partial with-reason
                                          "the door is blocked from one side")
                                 (update-in (kick-door game level to-tile dir)
                                            [1])))
                       (if (diagonal dir)
                         (if (kickable-door? level to-tile opts)
                           (kick-door game level to-tile dir))
                         (if (door-closed? to-tile)
                           [3 (->Open dir)]
                           (if-let [[slot _] (and (door-locked? to-tile)
                                                  (can-unlock? game)
                                                  (have-key game))]
                             [4 (->Unlock slot dir)]
                             (if (kickable-door? level to-tile opts)
                               (kick-door game level to-tile dir)))))))
                 (if (and (:pick opts) (diggable? to-tile) (not monster)
                          (or (boulder? to-tile) (diggable-walls? game level))
                          (or (not= (:branch-id level) :wiztower)
                              (some (some-fn water? ice?)
                                    (neighbors level to-tile)))
                          (dare-destroy? level to-tile))
                   [8 (dig (:pick opts) dir)]))
             (update-in [0] + (base-cost level dir to-tile opts))))))

(defrecord Path
  [step ; next Action to perform to move along path
   path ; vector of remaining positions
   target]) ; position of target

(defn autonavigable? [game level opts [from to]]
  (and (not (shop? from))
       (not ((some-fn shop? trap? unknown?) to))
       (edge-passable-walking? game level from to)
       (or (safely-walkable? to)
           (and ((some-fn water? ice?) to)
                (-> opts :levi (nth 1) :in-use)))
       (not (monster-at level to))))

(defn- autonav-target [game from level path opts]
  (if (and (not (:no-autonav opts))
           (not (shop? (at level from)))
           (not= :autotravel (some-> game :last-action typekw)))
    (let [path-tiles (map (partial at level) path)
          steps (->> path-tiles
                     (interleave (conj path-tiles (at level from)))
                     (partition 2))
          autonavigable (->> steps (take-while
                                     (partial autonavigable? game level opts))
                             (map second) (into []))
          target (some-> (peek autonavigable) position)]
      (if (and target
               (not (and (:autonav-stuck game) (= (:last-autonav game) target)))
               (more-than? 3 autonavigable)
               (not-any? (partial monster-at level) (neighbors from)))
        target))))

(defn- path-step [game level from move-fn path opts]
  (if-let [start (first path)]
    (some-> (or (if-let [target (and (not (:no-autonav opts))
                                     (autonav-target game from level path opts))]
                  (->Autotravel target))
                (if (:trapped (:player game))
                  (with-reason "untrapping self"
                    (random-move game level)))
                (nth (move-fn from start) 1))
            (assoc :path path))))

(defn- get-a*-path [game level from to move-fn opts max-steps]
  (if-let [path (a* from to move-fn max-steps)]
    (if (seq path)
      (if (:adjacent opts)
        (->Path (path-step game level from move-fn path opts) (pop path) to)
        (and (or (= 1 (count path)) (move-fn (-> path pop peek) to))
             (->Path (path-step game level from move-fn path opts) path to)))
      (->Path nil [] to))))

(defn navigate
  "Return shortest Path for given target position or predicate, will use A* or Dijkstra's algorithm as appropriate.
  Supported options:
    :walking - don't use actions except Move (no door opening etc.)
    :adjacent - path to closest adjacent tile instead of the target directly
    :explored - don't path through unknown tiles
    :max-steps <num> - don't navigate further than given number of steps
    :no-traps - more trap avoidance
    :no-dig - don't use the pickaxe or mattock
    :no-levitation - when navigating deliberately into a hole/trapdoor
    :prefer-items - walk over unknown items preferably (useful for exploration but possibly dangerous when low on health - items could be corpses on a dangerous trap)
    :no-autonav - don't use _ autotravel (when fighting monsters)"
  ([game pos-or-goal-fn]
   (navigate game pos-or-goal-fn {}))
  ([{:keys [player] :as game} pos-or-goal-fn
    {:keys [max-steps walking adjacent no-dig no-levitation] :as opts}]
   [{:pre [(or (fn? pos-or-goal-fn)
               (set? pos-or-goal-fn)
               (position pos-or-goal-fn))]}]
   (log/debug "navigating" pos-or-goal-fn opts)
   (let [level (curlvl game)
         levi (and (not no-levitation)
                   (not walking)
                   (or (have-levi-on game)
                       (have-levi game)))
         pick (and (not walking)
                   (not no-dig)
                   (have-pick game))
         opts (cond-> opts
                levi (assoc :levi levi)
                pick (assoc :pick pick))
         move-fn #(move game level %1 %2 opts)]
     (if-not (or (set? pos-or-goal-fn) (fn? pos-or-goal-fn))
       (get-a*-path game level player pos-or-goal-fn move-fn opts max-steps)
       (let [goal-fn (if (set? pos-or-goal-fn)
                       (comp (set (map position pos-or-goal-fn)) position)
                       #(pos-or-goal-fn (at level %)))]
         (if adjacent
           (let [goal-set (if (set? pos-or-goal-fn)
                            (->> pos-or-goal-fn (mapcat neighbors) set)
                            (->> (tile-seq level) (filter goal-fn)
                                 (mapcat neighbors) set))]
             (case (count goal-set)
               0 nil
               1 (get-a*-path game level player (first goal-set)
                              move-fn opts max-steps)
               (if-let [path (dijkstra player goal-set move-fn max-steps)]
                 (->Path (path-step game level player move-fn path opts) path
                         (->> (or (peek path) player)
                              neighbors
                              (find-first goal-fn))))))
           ; not searching for adjacent
           (if-let [goal-seq (seq (if (set? pos-or-goal-fn)
                                    pos-or-goal-fn
                                    (filter goal-fn (tile-seq level))))]
             (if (more-than? 1 goal-seq)
               (if-let [path (dijkstra player goal-fn move-fn max-steps)]
                 (->Path (path-step game level player move-fn path opts) path
                         (or (peek path) (position player))))
               (get-a*-path game level player (first goal-seq) move-fn opts
                            max-steps)))))))))

(defn- explorable-tile? [level tile]
  (and (not (and (dug? tile) (boulder? tile)))
       (or (and (unknown? tile) (not (blank? tile)))
           (:new-items tile)
           (and (or (likely-walkable? tile) (door? tile) (needs-levi? tile))
                (some (complement (some-fn :seen boulder? monster?))
                      (neighbors level tile))))
       (some (some-fn (complement blank?) :feature) (neighbors level tile))))

(defn dead-end? [level tile]
  (and (likely-walkable? tile)
       (not (trap? tile))
       (not (:dug tile))
       ; isolated diagonal corridors - probably dug:
       (not (and (some #(and (or (and (= \* (:glyph %)) (nil? (:color %)))
                                 (corridor? %) (boulder? %))
                             (not-any? likely-walkable?
                                       (straight-neighbors level %)))
                       (diagonal-neighbors level tile))))
       (not (in-maze-corridor? level tile))
       (let [snbr (straight-neighbors level tile)]
         (and (or (some walkable? snbr)
                  (not-any? walkable? (neighbors level tile)))
              (> 2 (count (remove (some-fn rock? wall?) snbr)))))))

(defn- has-dead-ends? [game level]
  (and (not-any? #{:bigroom :juiblex} (:tags level))
       (or (not (subbranches (branch-key game level)))
           (:minetown (:tags level)))))

(defn- search-dead-end
  [game num-search]
  (let [level (curlvl game)
        tile (at level (:player game))]
    (when (and (has-dead-ends? game level)
               (< (:searched tile) num-search)
               (dead-end? level tile))
      (with-reason "searching dead end" ->Search))))

(defn- pushable-through [game level from to]
  (and (or ((some-fn walkable? water? lava?) to)
           (and (not (boulder? to))
                (unknown? to))) ; try to push via unexplored tiles
       (or (straight (towards from to))
           (and (not= :sokoban (branch-key game))
                (not (door-open? from))
                (not (door-open? to))))))

(defn- pushable-from [game level pos]
  (seq (filter #(if (boulder? %)
                  (let [dir (towards pos %)
                        dest (in-direction level % dir)]
                    (and dest
                         (or (straight dir)
                             (not= :sokoban (branch-key game level)))
                         (not (monster-at level dest))
                         (edge-passable-walking? game level pos %)
                         (pushable-through game level % dest))))
               (neighbors level pos))))

(defn- unblocked-boulder? [game level tile]
  (and (boulder? tile)
       (some #(and (safely-walkable? (in-direction level tile (towards % tile)))
                   (pushable-through game level tile %))
             (neighbors level tile))))

; TODO check if it makes sense, the boulder might not block
(defn- push-boulders [{:keys [player] :as game} level]
  (if (some #(unblocked-boulder? game level %) (tile-seq level))
    (if-let [path (navigate game #(pushable-from game level %))]
      (with-reason "going to push a boulder"
        (or (:step path)
            (->> (pushable-from game level player) first
                 (towards player) ->Move (without-levitation game)))))
    (log/debug "no boulders to push")))

(defn- recheck-dead-ends [{:keys [player] :as game} level howmuch]
  (if (has-dead-ends? game level)
    (if-let [p (navigate game #(and (< (searched level %) howmuch)
                                    (dead-end? level %)))]
      (with-reason "re-checking dead ends" (or (:step p) (->Search))))))

(defn- searchable-position? [pos]
  (and (< 2 (:y pos) 20)
       (< 1 (:x pos) 78)))

(defn- wall-end? [level tile]
  (and (wall? tile)
       (< 0 (:x tile) 80)
       (< 0 (:y tile) 22)
       (not
         (or (and (wall? (at level (dec (:x tile)) (:y tile)))
                  (wall? (at level (inc (:x tile)) (:y tile))))
             (and (wall? (at level (:x tile) (dec (:y tile))))
                  (wall? (at level (:x tile) (inc (:y tile)))))))))

(defn- searchable-wall? [level howmuch tile]
  (and (searchable-position? tile)
       (wall? tile)
       (< (:searched tile) howmuch)
       (not (wall-end? level tile))
       (->> (straight-neighbors level tile) (remove :seen) seq)))

(defn- search-walls [game level howmuch]
  (let [searchable? (every-pred (partial searchable-wall? level howmuch)
                                (if (= :wiztower (branch-key game))
                                  (comp not wiztower-inner-rect position)
                                  (complement shop?)))]
    (if-let [p (navigate game searchable? {:adjacent true})]
      (with-reason "searching walls"
        (or (:step p) (->Search))))))

(defn- search-corridors [game level howmuch]
  (if-let [p (navigate game (fn searchable? [tile]
                              (and (corridor? tile)
                                   (searchable-position? tile)
                                   (< (searched level tile) howmuch))))]
    (with-reason "searching corridors"
      (or (:step p) (->Search)))))

(defn- searchable-extremity [level y xs howmuch]
  (if-let [tile (->> xs (map #(at level % y)) (find-first likely-walkable?))]
    (if (and (floor? tile)
             (< (:searched tile) howmuch)
             (not= \- (:glyph (at level (update-in tile [:x] dec))))
             (not= \- (:glyph (at level (update-in tile [:x] inc))))
             (not (shop? tile)))
      tile)))

(defn unexplored-column
  "Look for a column of unexplored tiles on segments of the screen."
  [game level]
  (if (and (#{:mines :main} (branch-key game level))
           (not (:castle (:tags level))))
    (first (remove (fn column-explored? [x]
                     (some :feature (for [y (range 2 19)]
                                      (at level x y))))
                   [17 40 63]))))


(defn- unsearched-extremities
  "Returns a set of tiles that are facing a large blank vertical space on the map – good candidates for searching."
  [game level howmuch]
  (if-let [col (unexplored-column game level)]
    (as-> #{} res
      (into res (for [y (range 1 21)]
                  (searchable-extremity level y (range col 80) howmuch)))
      (into res (for [y (range 1 21)]
                  (searchable-extremity level y (range col -1 -1) howmuch)))
      (disj res nil))))

(defn at-level? [game level]
  (and (= (:dlvl game) (:dlvl level))
       (= (branch-key game) (branch-key game level))))

(defn- curlvl-exploration-index
  [game]
  (let [level (curlvl game)]
    (log/debug "calc exploration index")
    (cond
      (unexplored-column game level) 0
      (navigate game (partial explorable-tile? level)) 0
      :else (->> (tile-seq level) (map :searched) (reduce + 1)))))

(defn reset-exploration-index
  "Performance optimization - curlvl-exploration-index is useful but expensive so is wrapped in a future"
  [anbf]
  (reify AboutToChooseActionHandler
    (about-to-choose [_ game]
      (swap! (:game anbf) update-in [:explored-cache]
             #(do (if % (future-cancel %))
                  (future (curlvl-exploration-index game)))))))

(defn exploration-index
  "Measures how much the level was explored/searched.  Zero means obviously not fully explored, larger number means more searching was done."
  ([game] @(:explored-cache game))
  ([game branch tag-or-dlvl]
   (if-let [level (get-level game branch tag-or-dlvl)]
     (if (at-level? game level)
       (exploration-index game)
       (get level :explored 0))
     0)))

(defn- search-extremities
  "Search near where there are large blank spaces on the map"
  [game level howmuch]
  (if (has-dead-ends? game level)
    (if-let [goals (unsearched-extremities game level howmuch)]
      (if-let [p (navigate game goals)]
        (with-reason "searching extremity" (or (pr-str (:target p)) "here")
          (or (:step p)
              (->Search)))))))

(defn- descend [game]
  (without-levitation game (->Descend)))

(defn go-down
  "Go through a hole or trapdoor or dig down if possible"
  [game level]
  (if-let [{:keys [step]} (navigate game #(#{:trapdoor :hole} (:feature %)))]
    (with-reason "going to a trapdoor/hole"
      (or step (descend game)))
    (if-let [pick (and (diggable-floor? game level)
                       (have-pick game))]
      (if-let [{:keys [step]}
               (or (navigate game #(and (#{:pit :floor} (:feature %))
                                        (not-any? water? (neighbors level %))
                                        (some wall?
                                              (diagonal-neighbors level %))
                                        (->> (straight-neighbors level %)
                                             (filter wall?)
                                             (more-than? 1))))
                   (navigate game #(and (#{:pit :floor :corridor} (:feature %))
                                        (not-any? water? (neighbors level %))
                                        (->> (straight-neighbors level %)
                                             (filter safely-walkable?)
                                             (more-than? 2)))))]
        (or (with-reason "finding somewhere to dig down" step)
            (with-reason "digging down" (dig pick \>)))))))

(defn search
  ([game] (or (search game 10) (throw (IllegalStateException. "stuck :-("))))
  ([game max-iter]
   (with-reason "searching - max-iter =" max-iter
     (let [level (curlvl game)]
       (loop [mul 1]
         (or (log/debug "search iteration" mul)
             (if (= 1 mul) (push-boulders game level))
             (if (< 43 (dlvl game))
               (with-reason "no stairs, possibly :main :end"
                 (:step (navigate game #(and (< 5 (:x %) 75) (< 6 (:y %) 17)
                                             (not (:walked %))
                                             (not (wall? %)))))))
             (recheck-dead-ends game level (* mul 30))
             (search-extremities game level (* mul 20))
             ; TODO dig towards unexplored-column
             (if (> mul 1) (search-corridors game level (* mul 5)))
             (search-walls game level (* mul 15))
             (if-not (> mul (dec max-iter))
               (recur (inc mul)))))))))

(declare explore)

(defn seek-portal [game]
  (with-reason "seeking portal"
    (or (:step (navigate game portal?))
        (if (> (dlvl game) 35)
          (if (unknown? (at-curlvl game fake-wiztower-portal))
            (with-reason "seeking fake wiztower portal"
              (:step (navigate game fake-wiztower-portal))))
          (with-reason "stepping everywhere to find portal"
            (:step (navigate game (complement (some-fn :walked door?))))))
        (explore game)
        (search game))))

(defn seek
  ([game smth]
   (seek game smth {}))
  ([game smth opts]
   (if-let [{:keys [step]} (navigate game smth opts)]
     (with-reason "seek going directly" step)
     (with-reason "seeking"
       (or (explore game)
           (and (:go-down opts) (with-reason "can't find downstairs"
                                  (go-down game (curlvl game))))
           (search game))))))

(defn- switch-dlvl [game new-dlvl]
  (with-reason "switching within branch to" new-dlvl
    (if-not (= (:dlvl game) new-dlvl)
      (or (if (= "Home 1" (:dlvl game)) ; may need to chat with quest leader first
            (let [level (curlvl game)
                  leader (-> level :blueprint :leader)]
              (when (and leader (not-any? :walked (neighbors level leader)))
                (with-reason
                  "trying to seek out quest leader at" leader "before descending"
                  (seek game leader {:adjacent true :explored true})))))
          (let [branch (branch-key game)
                [stairs action] (if (pos? (dlvl-compare (:dlvl game) new-dlvl))
                                  [:stairs-up (->Ascend)]
                                  [(if (:castle (curlvl-tags game))
                                     :trapdoor
                                     :stairs-down) (descend game)])
                step (with-reason "looking for the" stairs
                       (seek game #(and (has-feature? % stairs)
                                        (if-let [b (branch-key game %)]
                                          (= b branch)
                                          true))
                             (if (= :stairs-down stairs) {:go-down true})))]
            (or step action))))))

(defn- escape-branch [game]
  (let [levels (get-branch game)
        branch (branch-key game)
        dlvl (:dlvl game)
        [stairs stair-action] (if (upwards? branch)
                                [:stairs-down (descend game)]
                                [:stairs-up (->Ascend)])]
    (with-reason "escaping subbranch" branch
      (if (and (= dlvl (first (keys levels))) (portal-branches branch))
        (seek-portal game)
        (or (seek game #(has-feature? % stairs))
            stair-action)))))

(defn- least-explored [game branch dlvls]
  {:pre [(#{:main :mines} branch)]}
  (let [curdlvl (if (= branch (branch-key game))
                  (:dlvl (curlvl game)))]
    (first-min-by #(let [res (exploration-index game branch %)]
                     (if (and (= % curdlvl) (pos? res))
                       (max 1 (- res 1200)) ; add threshold not to switch levels too often
                       res))
                  dlvls)))

(defn- possibly-oracle? [game dlvl]
  (if-let [level (get-level game :main dlvl)]
    (and (not-any? corridor?
                   (for [y [7 8 14 15]
                         x (range 34 45)]
                     (at level x y))))
    true))

(defn- possibly-wiztower? [game dlvl]
  (if-let [level (get-level game :main dlvl)]
    (if (not-any? #{:wiztower-level :orcus :asmodeus :juiblex :baalzebub}
                  (:tags level))
      (->> fake-wiztower-water (map (partial at level))
           (remove (some-fn unknown? water?)) (less-than? 5)))
    true))

(defn visited?
  ([game branch]
   (get-branch game branch))
  ([game branch dlvl-or-tag]
   (get-level game branch dlvl-or-tag)))

(defn- first-unvisited
  "First unvisited dlvl from given range in :main"
  [game dlvls]
  (->> dlvls (remove (partial visited? game :main)) first))

(defn- dlvl-candidate
  "Return good Dlvl to search for given branch/tagged level"
  ([game branch]
   (or (branch-entry game branch)
       (case branch
         :wiztower (or (->> (get-branch game :main) vals
                            (filter (fn unexplored-tower? [level]
                                      (and (:fake-wiztower (:tags level))
                                           (->> (at level fake-wiztower-portal)
                                                portal?))))
                            (map :dlvl) first)
                       (some->>
                         (or (if-let [end (:dlvl (get-level game :main :end))]
                               (as-> end res
                                 (change-dlvl #(- % 4) res)
                                 (dlvl-range :main res 4)))
                             (if-let [geh (:dlvl (get-level game :main
                                                            :gehennom))]
                               (as-> geh res
                                 (change-dlvl (partial + 15) res)
                                 (dlvl-range :main res 8)))
                             (->> (dlvl-range :main "Dlvl:40" 12)))
                         (filter (partial possibly-wiztower? game))
                         (least-explored game :main)))
         :vlad (least-explored game :main
                               ; TODO check for double upstairs in vlad range
                               (if-let [vlad (dlvl-from-tag game :main :votd 9)]
                                 (dlvl-range :main vlad 5)
                                 (dlvl-range :main "Dlvl:34" 9)))
         :sokoban (or (if-let [oracle (:dlvl (get-level game :main :oracle))]
                        (next-dlvl :main oracle))
                      ; TODO check for double upstairs in soko range
                      (dlvl-candidate game :main :oracle))
         :quest (first-unvisited game (dlvl-range :main "Dlvl:11" 8))
         :mines nil ; TODO check for double downstairs in mines range
         nil)
       (least-explored game :main
                       (case branch
                         :mines (dlvl-range :main "Dlvl:2" 3)
                         (dlvl-range :main)))))
  ([game branch tag]
   (or (if (subbranches tag)
         (dlvl-candidate game tag))
       (:dlvl (get-level game branch tag))
       (if-not (get-branch game branch)
         (dlvl-candidate game branch))
       (if (#{:end :votd :gehennom :castle} tag)
         (->> (get-branch game branch) keys last (next-dlvl branch)))
       (case tag
         :rogue (->> (first-unvisited game (dlvl-range :main "Dlvl:15" 4)))
         :medusa (->> (first-unvisited game (dlvl-range :main "Dlvl:21" 8)))
         (least-explored game branch
                         (case tag
                           :oracle (filter #(possibly-oracle? game %)
                                           (dlvl-range :main "Dlvl:5" 5))
                           :minetown (dlvl-range :mines
                                       (dlvl-from-entrance game :mines 3) 2)
                           (dlvl-range branch)))))))

(defn- enter-branch [game branch]
  ;(log/debug "entering branch" branch)
  (if (not= :main (branch-key game))
    (or (explore game) (search game)) ; unknown subbranch
    (let [branch (branch-key game branch)
          [stairs stair-action] (if (upwards? branch)
                                  [:stairs-up (->Ascend)]
                                  [:stairs-down (descend game)])
          new-dlvl (dlvl-candidate game branch)]
      (with-reason "trying to enter" branch "from" new-dlvl
        (or (switch-dlvl game new-dlvl)
            (if (portal-branches branch)
              (seek-portal game))
            (seek game #(and (has-feature? % stairs)
                             (not= :main (branch-key game %))))
            stair-action)))))

(defn seek-branch
  [game new-branch-id]
  (let [new-branch (branch-key game new-branch-id)
        level (curlvl game)
        branch (branch-key game level)
        dlvl (:dlvl level)]
    (with-reason "seeking branch" new-branch-id "(" new-branch ")"
      (if (not= branch new-branch)
        (if (subbranches branch)
          (escape-branch game)
          (enter-branch game new-branch))))))

(defn seek-level
  "Navigate to a branch or dlvl/tagged level within one, neither needs to be found previously."
  [game new-branch-id tag-or-dlvl]
  (with-reason "seeking level" new-branch-id tag-or-dlvl
    (let [level (curlvl game)
          branch (branch-key game level)
          new-branch (branch-key game new-branch-id)
          new-level (get-level game new-branch tag-or-dlvl)
          dlvl (:dlvl level)]
      (if (= branch new-branch)
        (if new-level
          (switch-dlvl game (:dlvl new-level))
          (if (keyword? tag-or-dlvl)
            (switch-dlvl game (dlvl-candidate game new-branch tag-or-dlvl))
            (switch-dlvl game tag-or-dlvl)))
        (seek-branch game new-branch)))))

(defn explored?
  ([game]
   (pos? (exploration-index game)))
  ([game branch]
   (explored? game branch :end))
  ([game branch tag-or-dlvl]
   (pos? (exploration-index game branch tag-or-dlvl))))

(defn- shallower-unexplored
  ([game branch]
   (shallower-unexplored game :main (or (some->> (get-level game :main branch)
                                                 :dlvl (next-dlvl :main))
                                        branch)))
  ([game branch tag-or-dlvl]
   (let [branch (branch-key game branch)
         dlvl (or (:dlvl (get-level game branch tag-or-dlvl))
                  (next-dlvl branch (:dlvl game)))]
     (->> (get-branch game branch) keys first
          (iterate (partial next-dlvl branch))
          (take-while (partial not= dlvl))
          (remove (partial explored? game branch))
          first))))

(defn explore-level [game branch tag-or-dlvl]
  (if-not (explored? game branch tag-or-dlvl)
    (or (seek-level game branch tag-or-dlvl)
        (explore game))))

(defn explore
  ([game]
   (let [level (curlvl game)
         player-tile (at-player game)]
     (or (search-dead-end game 20)
         (if-not (pos? (exploration-index game))
           (or (if-let [[slot _] (and (:new-items player-tile)
                                      (walkable? player-tile)
                                      (have-levi-on game))]
                 (with-reason "removing levitation to examine items"
                   (remove-use game slot)))
               (when-let [path (navigate game (partial explorable-tile? level)
                                         {:prefer-items true})]
                 (with-reason "exploring" (pr-str (at level (:target path)))
                   (:step path)))
               ; TODO search for shops if heard but not found
               (when (unexplored-column game level)
                 (with-reason "level not explored enough, searching"
                   (search game (if (= :mines (branch-key game)) 10 2))))
               (log/debug "nothing to explore"))
           (log/debug "positive exploration index")))))
  ([game branch]
   (explore game branch :end))
  ([game branch tag-or-dlvl]
   (with-reason "exploring" branch "until" tag-or-dlvl
     (or (if-let [l (and (not= :main (branch-key game branch))
                         (shallower-unexplored game :main branch))]
           (with-reason "first exploring main until branch entrance"
             (explore-level game :main l)))
         (if-let [l (shallower-unexplored game branch tag-or-dlvl)]
           (with-reason "first exploring previous levels of branch"
             (explore-level game branch l)))
         (if-not (explored? game branch tag-or-dlvl)
           (with-reason "reaching exploration target"
             (explore-level game branch tag-or-dlvl)))
         (log/debug "all explored")))))

(defn visit
  ([game branch]
   (if-not (visited? game branch)
     (seek-branch game branch)))
  ([game branch tag-or-level]
   (if-not (visited? game branch tag-or-level)
     (seek-level game branch tag-or-level))))
