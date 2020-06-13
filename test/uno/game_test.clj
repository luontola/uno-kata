(ns uno.game-test
  (:require [clojure.test :refer :all]
            [uno.game :as game]
            [uno.schema :as schema]))

(def red-1 {:card/type 1, :card/color :red})
(def red-2 {:card/type 2, :card/color :red})
(def red-3 {:card/type 3, :card/color :red})
(def green-1 {:card/type 1, :card/color :green})
(def green-2 {:card/type 2, :card/color :green})
(def green-3 {:card/type 3, :card/color :green})
(def yellow-1 {:card/type 1, :card/color :yellow})
(def yellow-2 {:card/type 2, :card/color :yellow})
(def yellow-3 {:card/type 3, :card/color :yellow})
(def blue-1 {:card/type 1, :card/color :blue})
(def blue-2 {:card/type 2, :card/color :blue})
(def blue-3 {:card/type 3, :card/color :blue})

(defn- apply-events [events]
  (->> events
       (map schema/validate-event)
       (reduce game/projection nil)))

(defn- handle-command [command events]
  (->> (game/handle-command (schema/validate-command command)
                            (apply-events events))
       (map schema/validate-event)))

;; Rules for the Uno game
;; https://www.unorules.com/

(deftest all-cards-test
  (is (= 108 (count game/all-cards)))
  (doseq [card game/all-cards]
    (is (schema/validate-card card))))

(deftest remove-card-test
  (testing "removes from any position"
    (is (= [:b :c] (game/remove-card [:a :b :c] :a)))
    (is (= [:a :c] (game/remove-card [:a :b :c] :b)))
    (is (= [:a :b] (game/remove-card [:a :b :c] :c))))

  (testing "removes only one card"
    (is (= [:a] (game/remove-card [:a :a] :a)))
    (is (= [:a :a :b] (game/remove-card [:a :b :a :b] :b))
        "removes the first match"))

  (testing "fails if card is not in the deck"
    (is (thrown-with-msg?
         IllegalArgumentException #"^card not found: :b$"
         (game/remove-card [:a] :b)))))

;;;; Read model

(deftest projection-test
  (testing "game was started"
    (let [events [{:event/type :game.event/game-was-started
                   :game/players {:player1 {:player/hand [red-1 red-2 red-3]}
                                  :player2 {:player/hand [green-1 green-2 green-3]}
                                  :player3 {:player/hand [yellow-1 yellow-2 yellow-3]}}
                   :game/discard-pile [blue-1]
                   :game/draw-pile [blue-2 blue-3]
                   :game/current-player :player1
                   :game/next-players [:player2 :player3]}]
          expected {:game/players {:player1 {:player/hand [red-1 red-2 red-3]}
                                   :player2 {:player/hand [green-1 green-2 green-3]}
                                   :player3 {:player/hand [yellow-1 yellow-2 yellow-3]}}
                    :game/discard-pile [blue-1]
                    :game/draw-pile [blue-2 blue-3]
                    :game/current-player :player1
                    :game/next-players [:player2 :player3]}]
      (is (= expected (apply-events events)))

      (testing "> card was played"
        (let [events (conj events {:event/type :game.event/card-was-played
                                   :event/player :player1
                                   :card/type 1
                                   :card/color :red})
              expected (-> expected
                           (assoc-in [:game/players :player1 :player/hand] [red-2 red-3])
                           (assoc :game/discard-pile [red-1 blue-1]))]
          (is (= expected (apply-events events))))

        (testing "> player turn has ended"
          (let [events (conj events {:event/type :game.event/player-turn-has-ended
                                     :event/player :player1
                                     :game/next-players [:player2 :player3 :player1]})
                expected (-> expected
                             (assoc :game/current-player :player2)
                             (assoc :game/next-players [:player3 :player1]))]
            (is (= expected (apply-events events)))))))))


;;;; Commands

(deftest start-game-test
  (testing "the game is for 2-10 players"
    (let [players [:player1 :player2 :player3 :player4 :player5
                   :player6 :player7 :player8 :player9 :player10 :player11]]
      (is (thrown-with-msg?
           IllegalArgumentException #"^expected 2-10 players, but was 1$"
           (handle-command {:command/type :game.command/start-game
                            :game/players (take 1 players)}
                           [])))
      (is (not (empty? (handle-command {:command/type :game.command/start-game
                                        :game/players (take 2 players)}
                                       []))))
      (is (not (empty? (handle-command {:command/type :game.command/start-game
                                        :game/players (take 10 players)}
                                       []))))
      (is (thrown-with-msg?
           IllegalArgumentException #"^expected 2-10 players, but was 11$"
           (handle-command {:command/type :game.command/start-game
                            :game/players (take 11 players)}
                           [])))))

  (let [game (apply-events (handle-command {:command/type :game.command/start-game
                                            :game/players [:player1 :player2 :player3]}
                                           []))
        player1-hand (get-in game [:game/players :player1 :player/hand])
        player2-hand (get-in game [:game/players :player2 :player/hand])
        player3-hand (get-in game [:game/players :player3 :player/hand])
        draw-pile (:game/draw-pile game)
        discard-pile (:game/discard-pile game)]

    (testing "every player starts with 7 cards, face down"
      (is (= 7 (count player1-hand)))
      (is (= 7 (count player2-hand)))
      (is (= 7 (count player3-hand))))

    (testing "one card is placed in the discard pile, face up"
      (is (= 1 (count discard-pile))))

    (testing "the rest of the cards are placed in a draw pile, face down"
      (is (= (frequencies game/all-cards)
             (frequencies (concat player1-hand player2-hand player3-hand discard-pile draw-pile)))))

    (testing "first player and gameplay direction"
      (is (= :player1 (:game/current-player game)))
      (is (= [:player2 :player3] (:game/next-players game))))))

(deftest play-card-test
  (let [game-was-started {:event/type :game.event/game-was-started
                          :game/players {:player1 {:player/hand [blue-1]}
                                         :player2 {:player/hand [blue-2]}}
                          :game/discard-pile [red-2]
                          :game/draw-pile []
                          :game/current-player :player1
                          :game/next-players [:player2]}
        player-turn-has-ended {:event/type :game.event/player-turn-has-ended
                               :event/player :player1
                               :game/next-players [:player2 :player1]}]

    (testing "players cannot play out of turn"
      (is (thrown-with-msg?
           IllegalArgumentException #"^not current player; expected :player1, but was :player2$"
           (handle-command {:command/type :game.command/play-card
                            :command/player :player2
                            :card/type 2
                            :card/color :blue}
                           [game-was-started]))))

    (testing "players cannot play cards that are not in their hand"
      (is (thrown-with-msg?
           IllegalArgumentException #"^card not in hand; tried to play .*:card/type 2.*, but hand was .*:card/type 1.*$"
           (handle-command {:command/type :game.command/play-card
                            :command/player :player1
                            :card/type 2
                            :card/color :blue}
                           [game-was-started]))))

    (testing "players can match the card in discard pile by number"
      (is (= [{:event/type :game.event/card-was-played
               :event/player :player1
               :card/type 1
               :card/color :blue}
              player-turn-has-ended]
             (handle-command {:command/type :game.command/play-card
                              :command/player :player1
                              :card/type 1
                              :card/color :blue}
                             [(assoc game-was-started :game/discard-pile [red-1])]))))

    (testing "players can match the card in discard pile by color"
      (is (= [{:event/type :game.event/card-was-played
               :event/player :player1
               :card/type 1
               :card/color :blue}
              player-turn-has-ended]
             (handle-command {:command/type :game.command/play-card
                              :command/player :player1
                              :card/type 1
                              :card/color :blue}
                             [(assoc game-was-started :game/discard-pile [blue-2])]))))

    (testing "cards with different number and color will not match"
      (is (thrown-with-msg?
           IllegalArgumentException #"^card \{.*blue.*} does not match the card \{.*red.*} in discard pile$"
           (handle-command {:command/type :game.command/play-card
                            :command/player :player1
                            :card/type 1
                            :card/color :blue}
                           [(assoc game-was-started :game/discard-pile [red-2])]))))

    ;; TODO: extract to card-was-played-test?
    (testing "the card goes from the player's hand to the top of the discard pile"
      (let [game-was-started (-> game-was-started
                                 (assoc-in [:game/players :player1 :player/hand] [red-1 red-2 red-3])
                                 (assoc :game/discard-pile [blue-1]))
            card-was-played {:event/type :game.event/card-was-played
                             :event/player :player1
                             :card/type 1
                             :card/color :red}
            game-before (apply-events [game-was-started])
            game-after (apply-events [game-was-started card-was-played])]
        (is (= (-> game-before
                   (assoc-in [:game/players :player1 :player/hand] [red-2 red-3])
                   (assoc :game/discard-pile [red-1 blue-1]))
               game-after)))))

  ;; TODO
  (testing "if there are no matches or player chooses not to play;"
    (testing "player must draw a card from the discard pile")
    (testing "player may play the card they just drew if it matches")))
