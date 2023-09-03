package org.chernovia.molechess.test;

import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import org.chernovia.molechess.MoleGame;
import org.chernovia.molechess.MoleUser;

public class SanTest {

    static String TEST_FEN1 = "rnbqkbnr/pppp2pp/4p3/4Pp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3";
    static String TEST_FEN2 = "rnbqkbnr/pppp2pp/4pp2/4P3/8/8/PPPP1PPP/RNBQKBNR w KQkq - 0 3";

    public static void main(String[] args) {
        MoleGame game = new MoleGame(new MoleUser(null,"","User",1800), "TestGame", TEST_FEN1, null);
        System.out.println(game.getSan(new Move(Square.E5,Square.F6)));
        game = new MoleGame(new MoleUser(null,"","User",1800), "TestGame", TEST_FEN2, null);
        System.out.println(game.getSan(new Move(Square.E5,Square.F6)));
        game = new MoleGame(new MoleUser(null,"","User",1800), "TestGame", TEST_FEN1, null);
        System.out.println(game.getSan(new Move(Square.E5,Square.E6)));
        game = new MoleGame(new MoleUser(null,"","User",1800), "TestGame", TEST_FEN1, null);
        System.out.println(game.getSan(new Move(Square.E5,Square.D6)));
        game = new MoleGame(new MoleUser(null,"","User",1800), "TestGame", TEST_FEN1, null);
        System.out.println(game.getSan(new Move(Square.D2,Square.D4)));
        game = new MoleGame(new MoleUser(null,"","User",1800), "TestGame", TEST_FEN1, null);
        System.out.println(game.getSan(new Move(Square.G1,Square.F3)));
    }

}
