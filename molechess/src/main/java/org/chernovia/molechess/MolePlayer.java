package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class MolePlayer {
	MoleGame game;
  	MoleUser user;
  
  	enum ROLE { MOLE, PLAYER; }
  
  boolean away = false;
  
  boolean votedOff = false;
  
  int score = 0;
  
  int color = 0;
  
  MolePlayer vote = null;
  
  ROLE role = ROLE.PLAYER;
  
  public MolePlayer(MoleUser usr, MoleGame g, int c) {
    this.user = usr;
    this.game = g;
    this.color = c;
  }
  
  public boolean isActive() {
    return (!this.away && !this.votedOff);
  }
  
  public JsonNode toJSON() {
    ObjectNode obj = MoleServ.mapper.createObjectNode();
    obj.put("score", this.score);
    obj.put("color", this.color);
    obj.put("away", this.away);
    obj.set("user", this.user.toJSON());
    return (JsonNode)obj;
  }
}
