package org.chernovia.molechess;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.move.Move;
import java.awt.*;


public class MolePlayer implements StockListener {

    enum ROLE {MOLE, PLAYER, INSPECTOR, BLOCKER}
    MoleGame game;
    MoleUser user;
    boolean away = false;
    boolean votedOff = false;
    boolean ai = false;
    boolean resigning = false;
    boolean inspecting = false;
    boolean bombing = false;
    int bombed = 0;
    int maxBomb = 1;
    boolean blocking = false;
    int blocked = 0;
    int maxBlock = 2;
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
