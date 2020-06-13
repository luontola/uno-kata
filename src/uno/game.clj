(ns uno.game)

(def all-cards
  (let [color-cards-x1 [0]
        color-cards-x2 [1 2 3 4 5 6 7 8 9 :skip :reverse :draw-two]
        wild-cards [:wild :wild-draw-four]]
    (concat
     (for [color [:red :yellow :green :blue]
           type (concat color-cards-x1
                        color-cards-x2
                        color-cards-x2)]
       {:card/type type
        :card/color color})
     (for [type (flatten (repeat 4 wild-cards))]
       {:card/type type}))))

(def starting-hand-size 7)

(defn wild-card? [card]
  (contains? #{:wild :wild-draw-four} (:card/type card)))

(defn- draw-cards [game n]
  (let [draw-pile (:game/draw-pile game)
        [drawn remaining] (split-at n draw-pile)
        game (assoc game :game/draw-pile remaining)]
    [game drawn]))

(defn- deal-cards [game player n]
  (let [[game dealt-cards] (draw-cards game n)]
    (update-in game [:game/players player :player/hand] concat dealt-cards)))

(defn- initialize-discard-pile [game]
  (let [[game discard-pile] (draw-cards game 1)]
    (assoc game :game/discard-pile discard-pile)))


;;;; Read model

(defmulti projection (fn [_game event]
                       (:event/type event)))

(defmethod projection :default
  [game _event]
  game)

(defmethod projection :game.event/game-was-started
  [game event]
  (assert (nil? game))
  (select-keys event [:game/players :game/discard-pile :game/draw-pile
                      :game/current-player :game/next-players]))


;;;; Command handlers

(defmulti ^:private command-handler (fn [command _game _injections]
                                      (:command/type command)))

(defmethod command-handler :game.command/start-game
  [command game _injections]
  (assert (nil? game))
  (let [players (:game/players command)]
    (when-not (<= 2 (count players) 10)
      (throw (IllegalArgumentException. (str "expected 2-10 players, but was " (count players)))))
    (let [game {:game/draw-pile (shuffle all-cards)}
          game (reduce #(deal-cards %1 %2 starting-hand-size) game players)
          game (initialize-discard-pile game)]
      [(assoc game
              :game/current-player (first players)
              :game/next-players (rest players)
              :event/type :game.event/game-was-started)])))

(defmethod command-handler :game.command/play-card
  [command game _injections]
  (let [player (:command/player command)
        hand (get-in game [:game/players player :player/hand])
        card (select-keys command [:card/type :card/color])
        top-card (first (:game/discard-pile game))]
    (when-not (= (:game/current-player game)
                 player)
      (throw (IllegalArgumentException. (str "not current player; expected " (pr-str (:game/current-player game))
                                             ", but was " (pr-str player)))))
    (when-not (contains? (set hand) card)
      (throw (IllegalArgumentException. (str "card not in hand; tried to play " (pr-str card)
                                             ", but hand was " (pr-str hand)))))
    (when-not (or (= (:card/type top-card)
                     (:card/type card))
                  (= (:card/color top-card)
                     (:card/color card)))
      (throw (IllegalArgumentException. (str "card " (pr-str card)
                                             " does not match the card " (pr-str top-card)
                                             " in discard pile"))))
    [{:event/type :game.event/card-was-played
      :event/player player
      :card/type (:card/type card)
      :card/color (:card/color card)}]))

(defn handle-command [command game]
  (command-handler command game {}))
