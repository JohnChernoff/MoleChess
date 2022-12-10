package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.move.Move;
import kotlin.Pair;

import java.awt.*;
import java.util.List;

public class MolePlayer implements StockListener {

    enum ROLE {MOLE, PLAYER}
    MoleGame game;
    MoleUser user;
    boolean away = false;
    boolean kicked = false;
    boolean votedOff = false;
    boolean ai = false;
    boolean resigning = false;
    boolean suspecting = false;
    int rating;
    int score;
    int color;
    int skipped;
    Move move = null;
    MolePlayer vote = null;
    ROLE role = ROLE.PLAYER;
    Color guiColor;
    List<Pair<MolePlayer, Float>> evals;

    //TODO: fix color assignment bug when player rejoins
    public MolePlayer(MoleUser usr, MoleGame g, int c, Color c2) {
        user = usr;
        game = g;
        color = c;
        guiColor = c2;
        score = 0;
        skipped = 0;
    }

    public boolean isActive() {
        return (!away && !kicked);
    }

    public boolean isInteractive() {
        return isActive() && !ai;
    }

    public JsonNode toJSON() {
        ObjectNode obj = MoleServ.OBJ_MAPPER.createObjectNode();
        obj.put("score", score);
        obj.put("game_col", color);
        obj.put("play_col", rgbToHex(guiColor.getRed(), guiColor.getGreen(), guiColor.getBlue()));
        obj.put("away", away);
        obj.put("kickable", skipped >= game.getKickFlag());
        obj.put("votename",vote == null ? "undecided" : vote.user.name);
        obj.set("user", user.toJSON(true));
        return obj;
    }

    private String rgbToHex(int r, int g, int b) {
        return String.format("#%02x%02x%02x", r, g, b).toUpperCase();
    }

    public void analyzePosition(String fen, int t) {
        new StockThread(this, MoleServ.STOCK_PATH, fen, t,
                role == ROLE.PLAYER ? MoleServ.STOCK_STRENGTH : MoleServ.STOCK_MOLE_STRENGTH).start();
    }

    @Override
    public void newStockMove(String move) {
        StringBuilder actualMove = new StringBuilder(move); //if (actualMove.length() > 4) actualMove.insert(4,"="); //promotion
        game.handleMoveVote(this, actualMove.toString());
    }

    @Override
    public void updateEvaluations(final float currentEval, List<Pair<MolePlayer, Float>> evaluations) {
        this.evals = evaluations;
        evaluations.stream()
                .filter(it -> (color == MoleGame.COLOR_WHITE)? it.component2() < currentEval : it.component2() > currentEval)
                .map(eval -> new Pair<>(eval.component1(), Math.abs(currentEval - eval.component2())))
                .max((o1, o2) -> (int) (o1.component2() - o2.component2()))
                .ifPresent(it -> {
                    if (it.component2() > 1) {
                        game.castMoleVote(this.user, it.component1().user.name);
                    }
                });
        suspecting = false;
    }
}
