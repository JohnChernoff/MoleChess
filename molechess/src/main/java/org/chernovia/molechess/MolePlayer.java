package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class MolePlayer {
   	enum ROLE { MOLE, PLAYER };
	MoleGame game;
  	MoleUser user;
   	boolean away = false;
  	boolean votedOff = false;
  	boolean ai = false;
    int score;
    int color;
    MolePlayer vote = null;
    ROLE role = ROLE.PLAYER;
  
  public MolePlayer(MoleUser usr, MoleGame g, int c) {
    user = usr; game = g; color = c; score = 0;
  }
  
  public boolean isActive() {
    return (!away && !votedOff);
  }
  
  public JsonNode toJSON() {
    ObjectNode obj = MoleServ.mapper.createObjectNode();
    obj.put("score", score);
    obj.put("color", color);
    obj.put("away", away);
    obj.set("user", user.toJSON());
    return obj;
  }
}
