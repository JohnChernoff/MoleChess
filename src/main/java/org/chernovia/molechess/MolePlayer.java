package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.move.Move;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedDeque;

public class MolePlayer implements StockListener {

    enum ROLE {MOLE, PLAYER}
    MoleGame game;
    MoleUser user;
    boolean away = false;
    boolean votedOff = false;
    boolean ai = false;
    boolean resigning = false;
    int rating;
    int score;
    int color;
    int skipped;
    Move move = null;
    Move prediction = null;
    PieceType piecePrediction = null;
    MolePlayer vote = null;
    ROLE role = ROLE.PLAYER;
    Color guiColor;

    Deque<Long> messageStack = new ConcurrentLinkedDeque<>();

    //TODO: fix color assignment bug when player rejoins
    public MolePlayer(MoleUser usr, MoleGame g, int c, Color c2) {
        user = usr;
        game = g;
        color = c;
        guiColor = c2;
        score = 0;
        skipped = 0;
    }

    public boolean newMessage(int max, long millis) {
        long currentTime = System.currentTimeMillis(); int n = 0; long t = currentTime - millis;
        for (long msg : messageStack) {
            if (msg > t) n++;
            else messageStack.remove(msg);
        }
        if (n > max) return false;
        messageStack.add(currentTime);
        return true;
    }

    public boolean isActive() {
        return (!away && !votedOff);
    }

    public boolean isInteractive() {
        return isActive() && !ai;
    }

    public boolean isRampaging() {
        return role == ROLE.MOLE && game.getPhase() == MoleGame.GAME_PHASE.VOTING && game.getTeams().get(color).voteCount > 0;
    }

    public ObjectNode toJSON() {
        ObjectNode obj = MoleServ.OBJ_MAPPER.createObjectNode();
        obj.put("score", score);
        obj.put("game_col", color);
        obj.put("play_col", rgbToHex(guiColor.getRed(), guiColor.getGreen(), guiColor.getBlue()));
        obj.put("away", away);
        obj.put("kicked", votedOff); //either from being the mole or inactivity
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

}
