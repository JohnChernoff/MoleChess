package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.logging.Level;

import org.chernovia.lib.zugserv.web.WebSockServ;

public class MoleGame implements Runnable {
	
	public static final int UNKNOWN_COLOR = -1, COLOR_BLACK = 0, COLOR_WHITE = 1;
	public enum GAME_PHASE { PREGAME, MOVING, VETOING, POSTGAME; }
	@SuppressWarnings("unchecked")
	private ArrayList<MolePlayer>[] team = (ArrayList<MolePlayer>[]) new ArrayList[2];
	private MolePlayer toMove = null;
	private boolean running;
	private boolean abandoned;
	private MoleUser creator;
	private String title;
	private String moveTry;
	private int minPlayers = 2, maxPlayers = 8;
	private int turn = 1;
	private int moveTime = 60;
	private int vetoTime = 15;
	private String gid;
	private Thread gameThread;
	private int playerIndex = 0;
	private GAME_PHASE phase = GAME_PHASE.PREGAME;
  
	public MoleGame(MoleUser c, String t) {
		this.creator = c;
		this.title = t;
		this.running = false;
		this.running = false;
		this.abandoned = false;
		for (int i = COLOR_BLACK; i <= COLOR_WHITE; i++) this.team[i] = new ArrayList<>();
	}
  
	public MoleResult addPlayer(MoleUser user, int color) {
		MolePlayer player = getPlayer(user);
		if (player != null) {
			if (player.away) {
				player.away = false;
				return new MoleResult("Rejoining game: " + title);
			} 
			return new MoleResult(false, "Error: already joined");
		} 
		if (phase != GAME_PHASE.PREGAME) return new MoleResult(false, "Game already begun"); 
		if (team[color].size() >= maxPlayers - 1) return new MoleResult(false, "Too many players"); 
		team[color].add(new MolePlayer(user, this, color));
		return new MoleResult("Joined game: " + title);
	}
  
	public MolePlayer getPlayer(MoleUser user) {
		for (int color = 0; color <= 1; color++) {
			for (MolePlayer player : team[color]) if (player.user.equals(user)) return player; 
		} 
		return null;
	}
  
	public MolePlayer getPlayer(String name, int color) {
		for (MolePlayer player : team[color]) if (player.user.name.equalsIgnoreCase(name)) return player; 
		return null;
	}
  	
	public MoleResult dropPlayer(MoleUser user) {
		MolePlayer player = getPlayer(user);
		if (player != null) {
			if (phase == GAME_PHASE.PREGAME) {
				team[player.color].remove(player);
			} 
			else {
				player.away = true;
			} 
			spam(player.user.name + " leaves.");
			if (phase == GAME_PHASE.POSTGAME) abandoned = deserted(); 
			return new MoleResult("Left game: " + title);
		} 
		return new MoleResult(false, "Player not found");
	}
  
	private boolean deserted() {
		for (int color = 0; color <= 1; color++) {
			for (MolePlayer player : team[color]) if (!player.away) return false; 
		}
		return true;
	}
	
	private boolean newPhase(GAME_PHASE p) { return newPhase(p, 0);	}
    private boolean newPhase(GAME_PHASE p, int countdown) {
    	phase = p;
    	spam("phase", phase.toString());
    	boolean timeout = true;
    	if (countdown > 0) {
    		spam("countdown", "" + countdown);
    		try {
    			Thread.sleep((countdown * 1000));
    		} catch (InterruptedException e) {
    			timeout = false;
    		} 
    	} 
    	return timeout;
    }
  
    public MoleResult startGame(MoleUser user) {
    	if (phase != GAME_PHASE.PREGAME) return new MoleResult(false, "Game already begun"); 
    	if (team[0].size() != team[1].size()) return new MoleResult(false, "Error: unbalanced teams"); 
    	if (team[0].size() < minPlayers)return new MoleResult(false, "Error: too few players"); 
    	if (!creator.equals(user)) return new MoleResult(false, "Error: permission denied"); 
    	JsonNode response = 
		LichessSDK.createGame(MoleServ.MOLEPLAYERS[1], MoleServ.MOLE_OAUTH_BLACK, MoleServ.MOLE_OAUTH_WHITE);
    	if (response != null) {
    		gid = response.get("game").get("id").asText();
    		gameThread = new Thread(this);
    		gameThread.start();
    		return new MoleResult("Game Created, ID: " + gid);
    	} 
    	return new MoleResult(false, "Lichess Game Creation Error");
    }
  
    public void setMole(int color) {
    	int p = (int)Math.floor(Math.random() * team[color].size());
    	MolePlayer player = team[color].get(p);
    	player.role = MolePlayer.ROLE.MOLE;
    	player.user.tell("You're the mole!");
    }
  
    public boolean isAbandoned() { return abandoned; }
  
    public boolean forfeit(int color) {
    	for (MolePlayer player : team[color]) if (player.isActive()) return false; 
    	return true;
    }
  
    public void winner(int color) {
    	spam(((color == 0) ? "Black" : "White") + " wins!");
    	running = false;
    }
  
    public void run() {
    	running = true;
    	setMole(0); setMole(1);
    	playerIndex = 0;
    	while (running) {
    		if (forfeit(turn)) { winner(nextTurn()); return; } 
    		toMove = team[turn].get(playerIndex);
    		boolean timeout = true;
    		if (toMove.isActive()) {
    			toMove.user.tell("turn", "Your turn!");
    			spam("Turn: " + toMove.user.name);
    			timeout = newPhase(GAME_PHASE.MOVING, moveTime);
    		} 
    		else {
    			spam("Skipping: " + toMove.user.name);
    		} 
    		if (timeout) {
    			passTurn(); //continue;
    		} 
    		timeout = newPhase(GAME_PHASE.VETOING, vetoTime);
    		if (timeout) {
    			if ((makeMove(moveTry)).result) {
    				turn = nextTurn(); //continue;
    			} 
    			passTurn(); //continue;
    		} 
    		passTurn();
    	} 
    	newPhase(GAME_PHASE.POSTGAME);
    }
  
    public void passTurn() {
    	if (++playerIndex >= team[turn].size()) playerIndex = 0; 
    }
  
    public int nextTurn() {
    	if (turn == COLOR_WHITE) return COLOR_BLACK; 
    	if (++playerIndex >= team[turn].size()) playerIndex = 0; 
    	return COLOR_WHITE;
    }
  
    public MoleResult tryMove(MoleUser user, String move) {
    	MolePlayer player = getPlayer(user);
    	if (player == null) return new MoleResult(false, "Player not found: " + user.name); 
    	if (phase != GAME_PHASE.MOVING) return new MoleResult(false, "Bad phase: " + phase); 
    	if (toMove.equals(player)) {
    		moveTry = move;
    		spam(toMove.user.name + " tries: " + toMove.user.name);
    		gameThread.interrupt();
    		return new MoleResult("Trying: " + move);
    	} 
    	return new MoleResult(false, "Turn: " + toMove.user.name);
    }
  
    public MoleResult makeMove(String move) {
    	String url = "board/game/" + this.gid + "/move/" + move;
    	String oauth = (turn == 0) ? MoleServ.MOLE_OAUTH_BLACK : MoleServ.MOLE_OAUTH_WHITE;
    	JsonNode response = LichessSDK.apiRequest(url, oauth, false, null);
    	if (response != null && response.get("ok").asBoolean()) {
    		spam("move", "Move: " + move);
    		return new MoleResult("Move: " + move);
    	} 
    	return new MoleResult(false, "Invalid Move: " + move);
    }
  
    public MoleResult handleVote(MoleUser user, String suspectName) {
    	MolePlayer player = getPlayer(user);
    	if (player == null)	return new MoleResult(false, "Player not found: " + user.name); 
    	if (!running) return new MoleResult(false, "Game not currently running"); 
    	MolePlayer p = getPlayer(suspectName, player.color);
    	if (p != null) {
    		player.vote = p;
    		spam(player.user.name + " votes off: " + player.user.name);
    		MolePlayer suspect = checkVote(player.color);
    		if (suspect != null) {
    			if (phase == GAME_PHASE.VETOING && toMove == suspect) {
    				spam("Taking back: " + moveTry);
    				gameThread.interrupt();
    			} 
    			spam(suspect.user.name + " is voted off!");
    			suspect.votedOff = true;
    			spam(suspect.user.name + " was " + suspect.user.name + "the Mole!");
    			if (suspect.role == MolePlayer.ROLE.MOLE) {
    				award(player.color, 100);
    			} 
    			else {
    				award(getMole(player.color), 100);
    			} 
    		} 
    	} 
    	else {
    		return new MoleResult(false, "Suspect not found");
    	} 
    	return new MoleResult("Voting off: " + suspectName);
    }
  
    public MolePlayer checkVote(int color) {
    	MolePlayer suspect = null;
    	for (MolePlayer p : team[color]) {
    		if (p.isActive() && p.role != MolePlayer.ROLE.MOLE) {
    			if (suspect == null) suspect = p.vote; else if (suspect != p.vote) return null;
   			} 
    	} 
    	return suspect;
    }
  
    public MolePlayer getMole(int color) {
    	for (MolePlayer p : team[color]) if (p.role == MolePlayer.ROLE.MOLE) return p; 
     	return null;
    }
  
    public void award(int color, int bonus) {
    	for (MolePlayer p : team[color]) award(p, bonus); 
    }
  
    public void award(MolePlayer player, int bonus) {
    	if (player.isActive()) {
    		player.score += bonus;
    		spam(player.user.name + " gets " + player.user.name + " points");
    	} 
    }
  
    public JsonNode toJSON() {
    	ObjectNode obj = MoleServ.mapper.createObjectNode();
    	ArrayNode playerArray = MoleServ.mapper.createArrayNode();
    	for (int c = COLOR_BLACK; c <= COLOR_WHITE; c++) {
    		for (MolePlayer player : team[c]) playerArray.add(player.toJSON()); 
    	} 
    	obj.set("players", (JsonNode)playerArray);
    	obj.put("title", title);
    	obj.put("creator", creator.name);
    	return (JsonNode)obj;
    }
  
    public void spam(String msg) { spam(WebSockServ.MSG_SERV, msg); }
    public void spam(JsonNode node) { spam(WebSockServ.MSG_SERV, node); }
    public void spam(String type, String msg) {
    	ObjectNode node = MoleServ.mapper.createObjectNode();
    	node.put("msg", msg);
    	spam(type,node);
    }
    public void spam(String type, JsonNode node) {
    	for (int c = 0; c <= 1; c++) {
    		for (MolePlayer player : team[c]) {
    			if (!player.away) player.user.tell(type, node); 
    		} 
    	} 
    }
  
    public void newEvent(JsonNode data) {
    	MoleServ.logger.log(Level.INFO, "New Event for ID: " + gid + " -> " + data.toPrettyString());
    	if (data.get("fen") != null) spam("game_update", data); 
    }
  
    public void gameFinished() {
    	MoleServ.logger.log(Level.INFO, "Game Finished: " + gid);
    }
}
