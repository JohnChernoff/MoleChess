package org.chernovia.molechess.test;

import org.chernovia.molechess.MoleGame;
import org.chernovia.molechess.MoleUser;

public class BucketTest {
        static String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        //static int[] ratings = { 1260, 790, 1088, 910, 943, 1462, 1454, 1501, 638, 1092 };
        static int[] ratings = { 1260 };
        public static void main(String[] args) {
            MoleUser[] users = new MoleUser[ratings.length];
            for (int i=0;i<ratings.length;i++) {
                users[i] = new MoleUser(null,"","User" + i);
                users[i].setData(0,0,ratings[i],"");
            }
            MoleGame game = new MoleGame(users[0], "TestGame", START_FEN, null);
            for (int i=0;i<users.length;i++) game.addPlayer(users[i],MoleGame.COLOR_UNKNOWN);
            game.startGame(users[0]);
        }

}
