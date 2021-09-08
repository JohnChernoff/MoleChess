package org.chernovia.molechess;

import java.awt.Color;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.move.Move;

public class MolePlayer implements StockListener {
	
    static final Color[] PLAY_COLS = {
    		new Color(200,255,255), new Color(255,255,200), new Color(255,200,255),
    		new Color(255,255,128), new Color(255,255,32), new Color(255,32,255),
    		new Color(255,128,255), new Color(128,255,200), new Color(128,200,255),
    		new Color(255,128,128), new Color(128,255,32), new Color(128,32,255)
    		//Color.WHITE, Color.BLUE, Color.RED, Color.GREEN, Color.MAGENTA, Color.ORANGE,
    		//Color.YELLOW, Color.CYAN, Color.PINK, 
    		//new Color(200,128,192), new Color(128,192,200), new Color(192,200,128) 
    };
   	enum ROLE { MOLE, PLAYER };
	MoleGame game;
  	MoleUser user;
   	boolean away = false;
  	boolean votedOff = false;
  	boolean ai = false;
  	boolean resigning = false;
    int score;
    int color;
    Move move = null;
    MolePlayer vote = null;
    ROLE role = ROLE.PLAYER;
    Color guiColor = Color.BLUE;
  
  //TODO: fix color assignment bug when player rejoins
  public MolePlayer(MoleUser usr, MoleGame g, int c, Color c2) {
    user = usr; game = g; color = c; guiColor = c2; score = 0;
  }
  
  public boolean isActive() {
	  return (!away && !votedOff);
  }
  
  public boolean isInteractive() {
	  return isActive() && !ai;
  }
  
  public JsonNode toJSON() {
    ObjectNode obj = MoleServ.mapper.createObjectNode();
    obj.put("score", score);
    obj.put("game_col", color);
    obj.put("play_col", rgbToHex(guiColor.getRed(),guiColor.getGreen(),guiColor.getBlue()));
    obj.put("away", away);
    obj.set("user", user.toJSON());
    return obj;
  }
  
  private String rgbToHex(int r, int g, int b) {
	  return String.format("#%02x%02x%02x", r, g, b).toUpperCase();  
  }
  
  public void analyzePosition(String fen, int t) {
	  new StockThread(this,fen,t,role == ROLE.PLAYER ? 2200 : 1600).start();
  }

  @Override
  public void newStockMove(String move) {
  	game.voteMove(this, move);
  }

}
