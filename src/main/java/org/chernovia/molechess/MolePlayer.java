package org.chernovia.molechess;

import java.awt.Color;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.move.Move;

public class MolePlayer implements StockListener {
	
   	enum ROLE { MOLE, PLAYER };
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
    MolePlayer vote = null;
    ROLE role = ROLE.PLAYER;
    Color guiColor = Color.BLUE;
  
  //TODO: fix color assignment bug when player rejoins
  public MolePlayer(MoleUser usr, MoleGame g, int c, Color c2) {
    user = usr; game = g; color = c; guiColor = c2; score = 0; skipped = 0;
  }
  
  public boolean isActive() {
	  return (!away && !votedOff);
  }
  
  public boolean isInteractive() {
	  return isActive() && !ai;
  }
  
  public JsonNode toJSON() {
    ObjectNode obj = MoleServ.OBJ_MAPPER.createObjectNode();
    obj.put("score", score);
    obj.put("game_col", color);
    obj.put("play_col", rgbToHex(guiColor.getRed(),guiColor.getGreen(),guiColor.getBlue()));
    obj.put("away", away);
    obj.put("kickable", skipped >= game.getKickFlag());
    obj.set("user", user.toJSON(true));
    return obj;
  }
  
  private String rgbToHex(int r, int g, int b) {
	  return String.format("#%02x%02x%02x", r, g, b).toUpperCase();  
  }
  
  public void analyzePosition(String fen, int t) {
	  new StockThread(this,MoleServ.STOCK_PATH,fen,t,
		  role == ROLE.PLAYER ? MoleServ.STOCK_STRENGTH : MoleServ.STOCK_MOLE_STRENGTH).start();
  }

  @Override
  public void newStockMove(String move) {
  	game.voteMove(this, move);
  }

}
