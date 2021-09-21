package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.util.*;
import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;

public class MoleGame implements Runnable {
	
	class MoveVote {
		MolePlayer player;
		Move move;
		boolean selected = false;
		public MoveVote(MolePlayer p, Move m) { player = p; move = m; }
		public String toString() {
			return (player.user.name + ": " + move);
		}
		public JsonNode toJSON() {
			ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
			node.set("player", player != null ? player.toJSON() : null);
			ObjectNode moveNode = MoleServ.OBJ_MAPPER.createObjectNode();
			moveNode.put("from", move.getFrom().value());
			moveNode.put("to", move.getTo().value());
			node.set("move", moveNode);
			return node;
		}
	}
	
	class MoveVotes {
		ArrayList<MoveVote> selected;
		ArrayList<MoveVote> alts;
		String fen;
		int color;
		public MoveVotes(ArrayList<MoveVote> votes, String fenString, int c) {
			fen = fenString; color = c;
			alts = new ArrayList<MoveVote>();
			selected = new ArrayList<MoveVote>();
			for (MoveVote mv : votes) {
				if (mv.selected) selected.add(mv); 
				else alts.add(mv);
			}
		}
		public JsonNode toJSON() {
			ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
			ArrayNode altsArray = MoleServ.OBJ_MAPPER.createArrayNode();
			for (MoveVote alt : alts) altsArray.add(alt.toJSON());
			node.set("alts",altsArray);
			ArrayNode selectedArray = MoleServ.OBJ_MAPPER.createArrayNode();
			for (MoveVote selectedMove : selected) selectedArray.add(selectedMove.toJSON());
			node.set("selected", selectedArray);
			node.put("fen", fen);
			node.put("turn", color);
			return node;
		}
	}
	
	class MoleTeam {
		ArrayList<MolePlayer> players;
		int voteCount; //int color;
		public MoleTeam(int c) {
			players = new ArrayList<MolePlayer>(); voteCount = 0; //color = c;
		}
		public JsonNode toJSON() {
			ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
	    	ArrayNode playerArray = MoleServ.OBJ_MAPPER.createArrayNode();
    		for (MolePlayer player : players) playerArray.add(player.toJSON());
    		node.set("players", playerArray);
    		node.put("vote_count", voteCount);
    		return node;
		}
	}
	
	public static List<String> MOLE_NAMES = MoleServ.loadRandomNames("resources/molenames.txt");
	//public static final String MSG_TYPE_MOVELIST = "movelist";
	public static final int COLOR_UNKNOWN = -1, COLOR_BLACK = 0, COLOR_WHITE = 1;
	public enum GAME_RESULT { ONGOING, DRAW, CHECKMATE, STALEMATE, ABANDONED };
	public enum GAME_PHASE { PREGAME, VOTING, POSTGAME };
	private MoleTeam[] teams = new MoleTeam[2];
	ArrayList<MoleUser> observers = new ArrayList<MoleUser>();
	private MoleListener listener;
	private boolean playing;
	private MoleUser creator;
	private String title;
	private long lastActivity;
	private int minPlayers = 3, maxPlayers = 6, kickFlag = 2;
	private int turn;
	private int moveTime = 12, postTime = 300, preTime = 999;
	private double calcFactor = .25;
	private Board board;
	private Thread gameThread;
	private int moveNum;
	private ArrayList<MoveVotes> moveHistory;
	private GAME_PHASE phase = GAME_PHASE.PREGAME;
	private int voteLimit = 1;
	private int moleBonus = 100, winBonus = 200;
	private boolean aiFilling = true;
	private boolean endOnMutualAccusation = false;
	private boolean endOnAccusation = false;
	private boolean defection = true;
	private float currentGUIHue = (float)Math.random();
	
	public MoleGame(MoleUser c, String t, MoleListener l) {
		creator = c; title = t; playing = false; listener = l;
		for (int color = COLOR_BLACK; color <= COLOR_WHITE; color++) teams[color] = new MoleTeam(color);
		moveHistory = new ArrayList<MoveVotes>();
		lastActivity = System.currentTimeMillis();
    	turn = COLOR_WHITE; board = new Board(); moveNum = 1;
	}
	
	public MoleUser getCreator() { return creator; }
	public String getTitle() { return title; }
	public int getMaxPlayers() { return maxPlayers; }
	public void setMoveTime(int t) { moveTime = t; }
	public int getKickFlag() { return kickFlag; }
	public boolean isDefunct() { return isDefunct(preTime * 1000); }
	public boolean isDefunct(int timeout) {
		return (!playing && ((System.currentTimeMillis() - timeout) > lastActivity));
	}
	
	private void update(MoleUser user, MoleResult action) { update(user,action,false); }
	private void update(MoleResult action) { update(null,action,false); }
	private void update(MoleResult action, boolean moves) { update(null,action,moves); }
	private void update(MoleUser user, MoleResult action, boolean moves) {
		if (user == null) listener.updateGame(this, action, moves);
		else listener.updateUser(user, this, action, moves);
	}
	
    public JsonNode toJSON(boolean history) {
    	ObjectNode obj = MoleServ.OBJ_MAPPER.createObjectNode();
    	ArrayNode teamArray = MoleServ.OBJ_MAPPER.createArrayNode();
    	for (int c = COLOR_BLACK; c <= COLOR_WHITE; c++) teamArray.add(teams[c].toJSON()); 
    	obj.set("teams", teamArray);
    	obj.put("title", title);
    	obj.put("creator", creator.name);
    	obj.put("currentFEN", board.getFen());
    	if (history) obj.set("history",historyToJSON());
    	return obj;
    }
    
    public void addObserver(MoleUser user) {
    	if (!observers.contains(user)) {
    		observers.add(user); //user.tell(MSG_TYPE_MOVELIST,historyToJSON());
    		update(user,new MoleResult("Observing: " + title),true);
    	}
    	else update(user,new MoleResult(false,"Error: already observing"));
    }
    
    public void removeObserver(MoleUser user) {
    	if (observers.remove(user)); update(user,new MoleResult("No longer observing: " + title));
    }
  
	public void addPlayer(MoleUser user, int color) {
		MolePlayer player = getPlayer(user);
		if (player != null) {
			if (player.away) {
				player.away = false;
				update(user, new MoleResult("Rejoining game: " + title),true);
				update(new MoleResult(user.name + " rejoins the game"));
				
			} 
			else update(user, new MoleResult(false, "Error: already joined"));
		} 
		else if (phase != GAME_PHASE.PREGAME) {
			update(user, new MoleResult(false, "Game already begun")); 
		}
		else if (teams[color].players.size() >= maxPlayers - 1) {
			update(user, new MoleResult(false, "Too many players")); 
		}
		else {
			MolePlayer newPlayer = new MolePlayer(user, this, color, nextGUIColor());
			teams[color].players.add(newPlayer);
			update(user,new MoleResult("Joined game: " + title),true);
			update(new MoleResult(user.name + " joins the game"));
			lastActivity = System.currentTimeMillis();
		}
	}
	
	public void dropPlayer(MoleUser user) {
		boolean observed = false; //kinda kludgy, but hey
		if (observers.contains(user)) { removeObserver(user); observed = true; }
		MolePlayer player = getPlayer(user);
		if (player != null) {
			if (phase == GAME_PHASE.PREGAME) {
				teams[player.color].players.remove(player);
			} 
			else {
				player.away = true;
			} 
			update(new MoleResult(user.name + " leaves"));
			if (isDeserted()) {
				switch(phase) {
					case PREGAME: listener.finished(this); break;
					case VOTING: endGame(COLOR_UNKNOWN,"deserted"); break;
					case POSTGAME: gameThread.interrupt(); 
				}
			}
		} 
		else if (!observed) update(user, new MoleResult(false, "Player not found"));
	}
	
	public void kickPlayer(MoleUser kicker, String username) {
		MolePlayer kickerPlayer = getPlayer(kicker); 
		if (kickerPlayer == null) {
			update(kicker,new MoleResult(false,"Not in this game: " + kicker.name));
		}
		else {
			MolePlayer player = getPlayer(username,kickerPlayer.color);
			if (player == null) {
				update(kicker,new MoleResult(false,"Not in this game: " + username));
			}
			else if (phase != GAME_PHASE.VOTING) {
				update(kicker,new MoleResult(false,"Bad phase: " + phase));
			}
			else if (player.skipped < kickFlag) {
				update(kicker,new MoleResult(false,
				username + " must be inactive for " + kickFlag + " turns (currently: " + 
				player.skipped + ")"));
			}
			else if (player.ai) {
				update(kicker,new MoleResult(false,"Cannot kick robots!"));
			}
			else {
				update(new MoleResult(kicker.name + " kicks " + username + " (reason: inactivity)"));
				player.votedOff = true;
			}
		}
	}
	
    public void startGame(MoleUser user) {
    	if (phase != GAME_PHASE.PREGAME) {
    		update(user, new MoleResult(false, "Game already begun")); 
    	}
    	else if (!creator.equals(user)) {
    		update(user, new MoleResult(false, "Error: permission denied"));
    	}
    	else {
    		if (!aiFilling && teams[COLOR_BLACK].players.size() != teams[COLOR_WHITE].players.size()) {
    			update(user, new MoleResult(false, "Error: unbalanced teams")); 
           	}
            else if (!aiFilling && teams[COLOR_BLACK].players.size() < minPlayers) {
            	update(user, new MoleResult(false, "Error: too few players"));
    		}
            else {
        		if (aiFilling) { aiFill(COLOR_BLACK); aiFill(COLOR_WHITE); }
           		gameThread = new Thread(this); gameThread.start();
            }
    	}
    }
        
    public void voteMove(MoleUser user, String movestr) {
    	MolePlayer player = getPlayer(user);
    	if (player == null) { update(user, new MoleResult(false, "Player not found: " + user.name)); }
    	else voteMove(player,movestr); 
    }
    public void voteMove(MolePlayer player, String movestr) {
    	if (phase != GAME_PHASE.VOTING) {
    		update(player.user, new MoleResult(false, "Bad phase: " + phase));
    	}
    	else if (player.color != turn) {
    		update(player.user, new MoleResult(false, "Current turn: " + colorString(turn)));
    	}
    	else if (player.votedOff) {
    		update(player.user, new MoleResult(false, "Sorry, you've been voted off")); 
    	}
    	else if (addMoveVote(player,getMove(movestr))) {
    		update(new MoleResult(player.user.name + " votes: " + movestr));
    	}
		else {
			update(player.user, new MoleResult(false,"Bad Move: " + movestr));
		}
    }
    
    //TODO: fix weird name voting bug
    public void castMoleVote(MoleUser user, String suspectName) {
    	MolePlayer player = getPlayer(user);
    	if (player == null)	{
    		update(user, new MoleResult(false, "Player not found: " + user.name)); 
    	}
    	else if (!playing) {
    		update(user, new MoleResult(false, "Game not currently running")); 
    	}
    	else if (teams[player.color].voteCount >= voteLimit) {
    		update(user, new MoleResult(false, "No more voting!")); 
    	}
    	else if (player.votedOff) {
    		update(user, new MoleResult(false, "Sorry, you've been voted off")); 
    	}
    	else {
        	MolePlayer p = getPlayer(suspectName, player.color);
        	if (phase != GAME_PHASE.VOTING) {
        		update(user, new MoleResult(false, "Cannot vote during: " + phase));
        	}
        	else if (p != null) {
        		 handleMoleVote(player,p);
        	} 
        	else {
        		update(user, new MoleResult(false, "Suspect not found"));
        	} 
    	}
    }
    
    public void resign(MoleUser user) {
    	MolePlayer player = getPlayer(user);
    	if (player == null) {
    		update(user, new MoleResult(false, "Player not found: " + user.name)); 
    	}
    	else if (phase != GAME_PHASE.VOTING) {
    		update(user, new MoleResult(false, "Bad phase: " + phase));
    	}
    	else if (player.color != turn) {
    		update(user, new MoleResult(false, "Wrong turn: " + colorString(turn)));
    	}
    	else {
    		player.resigning = true;
    		update(new MoleResult(player.user.name + " resigns"));
    		if (resigning(player.color)) endGame(getNextTurn(),"resignation"); 
    	}
    }
    
    public void run() {
    	playing = true;
    	setMole(COLOR_BLACK); setMole(COLOR_WHITE);
    	listener.started(this); //spamMove(null); //starting position
    	while (playing) {
  			spam("Turn #" + moveNum + ": " + colorString(turn));
  			autoPlay(turn);
   			//boolean timeout = 
   			newPhase(GAME_PHASE.VOTING, moveTime);
   			if (playing) {
       			Move move;
       			ArrayList<Move> moveList = getMoveVotes(turn);
       			if (moveList.size() == 0) {
       				spam("No legal moves selected, picking randomly...");
       				move = pickMove(board.legalMoves());
       			}
       			else {
       				spam("Picking randomly from the following moves: \n" + listMoves(turn));
       	 			move = pickMove(moveList);
       			}
       			if (makeMove(move).success) {
   					moveHistory.add(getMoveVotes(turn,board.getFen(),move));
   	       			update(new MoleResult("Selected Move: " + move),true);
       				if (playing) {
                		clearMoveVotes(turn);
       					turn = getNextTurn();
                		moveNum++;
       				}
       			}
       			else { spam("WTF: " + move); return; } ////shouldn't occur
   			}
		}
    	if (!isDeserted()) newPhase(GAME_PHASE.POSTGAME,postTime);
    	listener.finished(this);
    }
    
    private void autoPlay(int turn) {
		for (MolePlayer player : teams[turn].players) {
			if (player.ai) player.analyzePosition(board.getFen(),(int)(moveTime * calcFactor) * 1000);
		}
    }
    
    private void handleMoleVote(MolePlayer player, MolePlayer p) {
    	player.vote = p;
		spam(player.user.name + " votes off: " + p.user.name);
		MolePlayer suspect = checkVote(player.color);
		if (suspect != null) {
			spam(suspect.user.name + " is voted off!");
			if (suspect.role == MolePlayer.ROLE.MOLE) {
    			spam(suspect.user.name + " was " + "the Mole!"); //award(player.color, moleBonus);
			} 
			else {
				MolePlayer mole = getMole(player.color);
				spam(mole.user.name + " was " + "the Mole!"); //award(mole, moleBonus);
			}
			if (defection) {
				int newColor = getNextTurn(suspect.color);
				teams[suspect.color].players.remove(suspect);
				suspect.color = newColor;
				suspect.role = MolePlayer.ROLE.PLAYER;
				teams[suspect.color].players.add(suspect);
				update(new MoleResult(suspect.user.name + " defects to " + colorString(newColor) + "!"));
			}
			else suspect.votedOff = true;
			teams[player.color].voteCount++;
			if (endOnAccusation) {
				endGame(COLOR_UNKNOWN,"Mole vote");
			}
			else if (endOnMutualAccusation && 
				teams[COLOR_BLACK].voteCount > 0 && 
				teams[COLOR_WHITE].voteCount > 0) endGame(COLOR_UNKNOWN,"mutual mole vote");
		}
    }
    
    private JsonNode historyToJSON() {
    	//ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
    	ArrayNode historyNode = MoleServ.OBJ_MAPPER.createArrayNode();
    	for (MoveVotes votes : moveHistory) historyNode.add(votes.toJSON());    		
    	//node.set("history",historyNode);
    	//node.put("title",title); //log("Move History: " + node.toPrettyString());
    	return historyNode;
    }
    
	private MolePlayer getPlayer(MoleUser user) {
		for (int color = 0; color <= 1; color++) {
			for (MolePlayer player : teams[color].players) {
				if (player.user.equals(user)) return player; 
			}
		} 
		return null;
	}
  
	private MolePlayer getPlayer(String name, int color) {
		for (MolePlayer player : teams[color].players) {
			if (player.user.name.equalsIgnoreCase(name)) return player; 
		}
		return null;
	}
  	
	private boolean isDeserted() {
		for (int color = 0; color <= 1; color++) {
			for (MolePlayer player : teams[color].players) {
				if (!player.away && !player.ai) return false; 
			}
		}
		return true;
	}
	
    private boolean newPhase(GAME_PHASE p, int seconds) {
    	phase = p; spam("phase", phase.toString());
    	boolean timeout = true;
    	if (seconds > 0) {
    		ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
    		node.put("seconds",seconds);
    		node.put("turn",turn);
    		node.put("title",title);
    		spam("countdown",node);
    		try {
    			Thread.sleep((seconds * 1000));
    		} catch (InterruptedException e) {
    			timeout = false;
    		} 
    	} 
    	if (playing) endgameCheck();
    	return timeout;
    }
    
    private boolean endgameCheck() {
    	if (playing) {
    		if (activePlayers(turn,true) == 0) endGame(getNextTurn(),"forfeit");
    		else if (board.isStaleMate()) endGame(COLOR_UNKNOWN,"stalemate");
        	else if (board.isMated()) endGame(turn,"checkmate");
        	else if (board.isInsufficientMaterial()) endGame(COLOR_UNKNOWN,"insufficient material");
    	}
    	return !playing;
    }
    
    //handle aborts
    public void endGame(int winner, String reason) {
    	if (winner != COLOR_UNKNOWN) {
    		spam(colorString(winner) + " wins by " + reason + "!"); //award(winner,winBonus);
    		listener.updateUserData(teams[winner].players, teams[getNextTurn(winner)].players, false);
    		
    	}
    	else {
    		spam("Game Over! (" + reason + ")");
    		listener.updateUserData(teams[COLOR_WHITE].players, teams[COLOR_BLACK].players, true);
    	}
    	playing = false; 
    	if (gameThread != null && gameThread.getState()==Thread.State.TIMED_WAITING) gameThread.interrupt();
    }
    
    ////new MolePlayer(MoleServ.DUMMIES[i++][color], this, color, nextGUIColor());
    private void aiFill(int color) {
    	while (teams[color].players.size() < minPlayers) {
    		int n = (int)Math.floor(Math.random() * MOLE_NAMES.size());
        	MolePlayer player = new MolePlayer(
        			new MoleUser(null,null,MOLE_NAMES.get(n)),this,color,nextGUIColor());
        	player.ai = true;
        	teams[color].players.add(player);
    	}
    }
    
    private boolean onlyHuman(MolePlayer player) {
    	if (player.ai) return false;
    	for (MolePlayer p : teams[player.color].players) {
    		if (!p.ai && !p.equals(player)) return false;
    	}
    	return true;
    }
    
    private void setMole(int color) {
    	MolePlayer player;
    	do {
        	int p = (int)Math.floor(Math.random() * teams[color].players.size());
        	player = teams[color].players.get(p);
    	} while (onlyHuman(player));
    	player.role = MolePlayer.ROLE.MOLE;
    	player.user.tell("You're the mole!"); player.user.tell("mole","");
    }
  
    private int activePlayers(int color, boolean ignoreAI) {
    	int active = 0;	
    	for (MolePlayer player : teams[color].players) { //log(player.user.name + " is: ");
    		if (ignoreAI) {
    			if (player.isActive()) { 
    				active++; //log("active");
    			}
    		}
    		else if (player.isInteractive()) {
    			active++; //log("active");
    		}
    	}
    	return active;
    }
  
    private String colorString(int color) {
    	return (color == COLOR_BLACK) ? "Black" : "White";
    }
      
    private int getNextTurn() { return getNextTurn(turn); }
    private int getNextTurn(int color) {
    	if (color == COLOR_WHITE) return COLOR_BLACK; else return COLOR_WHITE;
    }
    
    private String listMoves(int color) {
    	String list = ""; 
    	for (MolePlayer player : teams[color].players) {
    		if (player.move != null) list += (player.user.name + ": " + player.move + "\n");	
    	}
    	return list;
    }
    
    private Move pickMove(List<Move> moves) {
    	int n = (int)(Math.random() * moves.size()); return moves.get(n);
    }
  
    private boolean addMoveVote(MolePlayer player, Move move) {
    	if (board.legalMoves().contains(move)) {
    		player.move = move;
    		if (countMoveVotes(player.color) >= activePlayers(turn,true)) gameThread.interrupt();
        	return true;
    	}
    	else return false;
    }
    
    private int countMoveVotes(int color) {
    	int count = 0; 
    	for (MolePlayer player : teams[color].players) if (player.move != null) count++;
    	return count;
    }
    
    private void clearMoveVotes(int color) {
    	for (MolePlayer player : teams[color].players) player.move = null;
    }
    
    private ArrayList<Move> getMoveVotes(int color) {
    	ArrayList<Move> moveList = new ArrayList<Move>();
    	for (MolePlayer player : teams[color].players) if (player.move != null) moveList.add(player.move);
    	return moveList;
    }
    
    private MoveVotes getMoveVotes(int color, String fen, Move selectedMove) {
    	ArrayList<MoveVote> voteList = new ArrayList<MoveVote>();
    	boolean selected = false;
    	for (MolePlayer player : teams[color].players) {
    		if (player.move != null) {
    			MoveVote mv = new MoveVote(player,player.move);
    			if (player.move == selectedMove) { selected = true; mv.selected = true;	}
    			voteList.add(mv); 
    			player.skipped = 0;
    		}
    		else player.skipped++;
    	}
    	if (!selected) {
    		MoveVote mv = new MoveVote(null,selectedMove); mv.selected = true; voteList.add(mv);
    	}
    	return new MoveVotes(voteList,fen,color);
    }
    
    private Move getMove(String movestr) {
        try {
            final String sanitizedMovestr = movestr.replace("\n", "").replace("\r", "");
            return new Move(sanitizedMovestr,turn == COLOR_BLACK ? Side.BLACK : Side.WHITE);
        }
        catch (IllegalArgumentException oops) {
            log(oops.getMessage()); return null;
        }
    }
  
    private MoleResult makeMove(Move move) {
    	if (board.doMove(move)) { //spamMove(move);
    		endgameCheck();
    		return new MoleResult("Move: " + move);
    	}
    	else return new MoleResult(false, "Invalid Move: " + move); //shouldn't occur
    }
    
    private void spamMovez(Move move) {
		ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
		node.put("lm",move == null ? "" : move.toString());
		node.put("fen",board.getFen());
		spam("game_update",node); 
    }
  
    private MolePlayer checkVote(int color) { //log("Checking vote...");
       	HashMap<MolePlayer,Integer> voteMap = new HashMap<MolePlayer,Integer>();
    	for (MolePlayer p : teams[color].players) {
    		if (p.vote != null) {
    			if (voteMap.containsKey(p.vote)) voteMap.put(p.vote,voteMap.get(p.vote)+1);
    			else voteMap.put(p.vote,1);
    		}
    	}
    	int quorum =  activePlayers(color,true)-1; //log("Quorum: " + quorum);
    	for (MolePlayer p : teams[color].players) {
    		if (voteMap.containsKey(p)) {
        		Integer votes = voteMap.get(p);	//log("Votes for " + p.user.name + ": " + votes.intValue());
        		if (votes.intValue() >= quorum) return p;
    		}
    	}
    	return null;
    }
    
    private boolean resigning(int color) {
    	for (MolePlayer p : teams[color].players) if (p.isInteractive() && !p.resigning) return false;
    	return true;
    }
  
    private MolePlayer getMole(int color) {
    	for (MolePlayer p : teams[color].players) if (p.role == MolePlayer.ROLE.MOLE) return p; 
     	return null;
    }
  
    private void award(int color, int bonus) {
    	for (MolePlayer p : teams[color].players) award(p, bonus); 
    }
  
    private void award(MolePlayer player, int bonus) {
    	if (player.isActive()) {
    		player.score += bonus;
    		spam(player.user.name + " gets " + bonus + " points");
    	} 
    }
  
    public void spam(String msg) { spam("chat", msg); }
    public void spam(String type, String msg) {
    	ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
    	node.put("msg", msg);
    	node.put("source",title);
    	node.put("player","");
    	spam(type,node);
    }
    public void spam(String type, JsonNode node) {
    	try {
    	   	for (int c = 0; c <= 1; c++) {
        		for (MolePlayer player : teams[c].players) {
        			if (!player.away) player.user.tell(type, node); 
        		} 
        	} 
    	}
    	catch (ConcurrentModificationException oops) { //dunno how exactly this happens...
    		log(oops.getMessage()); 
    	}
    	for (MoleUser user : observers) user.tell(type, node);
    }
    
    private Color nextGUIColor() {
    	currentGUIHue += .3; if (currentGUIHue > 1) currentGUIHue--; //log("Current Hue: " + currentGUIHue);
    	return Color.getHSBColor(currentGUIHue, 
  			(2.5f + ((float)Math.random() * 7.5f))/10, (5 + ((float)Math.random() * 5))/10);
    }
    
    private static void log(String msg) { MoleServ.log(msg); }
    
}
