package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import org.chernovia.lib.zugserv.web.WebSockServ;

public class MoleGame implements Runnable {
	
	class MoveVote {
		MolePlayer player;
		Move move;
		public MoveVote(MolePlayer p, Move m) { player = p; move = m; }
		public String toString() {
			return (player.user.name + ": " + move);
		}
	}
	
	public static final int UNKNOWN_COLOR = -1, COLOR_BLACK = 0, COLOR_WHITE = 1;
	public enum GAME_PHASE { PREGAME, VOTING, POSTGAME };
	@SuppressWarnings("unchecked")
	private ArrayList<MolePlayer>[] team = (ArrayList<MolePlayer>[]) new ArrayList[2];
	private MoleListener listener;
	private boolean playing; //private boolean abandoned;
	private MoleUser creator;
	private String title;
	private int minPlayers = 3, maxPlayers = 6;
	private int turn;
	private int voteTime = 12;
	private Board board;
	private Thread gameThread;
	private int moveNum;
	ArrayList<MoveVote> currentVotes;
	ArrayList<ArrayList<MoveVote>> voteHistory;
	private GAME_PHASE phase = GAME_PHASE.PREGAME;
  
	public MoleGame(MoleUser c, String t, MoleListener l) {
		creator = c; title = t; playing = false; listener = l;
		for (int i = COLOR_BLACK; i <= COLOR_WHITE; i++) this.team[i] = new ArrayList<>();
		currentVotes = new ArrayList<MoveVote>();
		voteHistory = new ArrayList<ArrayList<MoveVote>>();
	}
	
	public String getTitle() { return title; }
	public int getMaxPlayers() { return maxPlayers; }
	
    public JsonNode toJSON() {
    	ObjectNode obj = MoleServ.mapper.createObjectNode();
    	ArrayNode playerArray = MoleServ.mapper.createArrayNode();
    	for (int c = COLOR_BLACK; c <= COLOR_WHITE; c++) {
    		for (MolePlayer player : team[c]) playerArray.add(player.toJSON()); 
    	} 
    	obj.set("players", (JsonNode)playerArray);
    	obj.put("title", title);
    	obj.put("creator", creator.name);
    	return obj;
    }
  
	public void addPlayer(MoleUser user, int color) {
		MolePlayer player = getPlayer(user);
		if (player != null) {
			if (player.away) {
				player.away = false;
				listener.handleAction(user, new MoleResult("Rejoining game: " + title));
			} 
			listener.handleAction(user, new MoleResult(false, "Error: already joined"));
		} 
		if (phase != GAME_PHASE.PREGAME) {
			listener.handleAction(user, new MoleResult(false, "Game already begun")); 
		}
		if (team[color].size() >= maxPlayers - 1) {
			listener.handleAction(user, new MoleResult(false, "Too many players")); 
		}
		team[color].add(new MolePlayer(user, this, color));
		listener.handleAction(user, new MoleResult("Joined game: " + title));
	}
	
	public void dropPlayer(MoleUser user) {
		MolePlayer player = getPlayer(user);
		if (player != null) {
			if (phase == GAME_PHASE.PREGAME) {
				team[player.color].remove(player);
			} 
			else {
				player.away = true;
			} 
			spam(player.user.name + " leaves.");
			listener.handleAction(user, new MoleResult("Left game: " + title));
			if (deserted()) {
				switch(phase) {
					case PREGAME: listener.finished(this); break;
					case VOTING: playing = false; break;
					case POSTGAME: gameThread.interrupt(); 
				}
			}
		} 
		else listener.handleAction(user, new MoleResult(false, "Player not found"));
	}
	
    public void startGame(MoleUser user) {
    	if (phase != GAME_PHASE.PREGAME) {
    		listener.handleAction(user, new MoleResult(false, "Game already begun")); 
    	}
    	else if (!creator.equals(user)) {
    		listener.handleAction(user, new MoleResult(false, "Error: permission denied"));
    	}
    	else {
        	aiFill(COLOR_BLACK); aiFill(COLOR_WHITE);
        	//if (team[0].size() != team[1].size()) return new MoleResult(false, "Error: unbalanced teams"); 
        	//if (team[0].size() < minPlayers)return new MoleResult(false, "Error: too few players"); 
      		gameThread = new Thread(this); gameThread.start();
      		listener.handleAction(user, new MoleResult("Starting Game"));
    	}
    }
    
    public void voteMove(MoleUser user, String movestr) {
    	MolePlayer player = getPlayer(user);
    	if (player == null) {
    		listener.handleAction(user, new MoleResult(false, "Player not found: " + user.name)); 
    	}
    	else if (phase != GAME_PHASE.VOTING) {
    		listener.handleAction(user, new MoleResult(false, "Bad phase: " + phase));
    	}
    	else if (player.color != turn) {
    		listener.handleAction(user, new MoleResult(false, "Wrong turn: " + player.color));
    	}
    	else {
    		MoveVote mv = addVote(player,getMove(movestr));
    		if (mv == null) {
    			listener.handleAction(user, new MoleResult(false,"Bad Move: " + movestr));
    		}
    		else {
    			listener.handleAction(user, new MoleResult(player.user.name + " votes: " + movestr));
    		}
    	}
    }
    
    public void castVote(MoleUser user, String suspectName) {
    	MolePlayer player = getPlayer(user);
    	if (player == null)	{
    		listener.handleAction(user, new MoleResult(false, "Player not found: " + user.name)); 
    	}
    	else if (!playing) {
    		listener.handleAction(user, new MoleResult(false, "Game not currently running")); 
    	}
    	else {
        	MolePlayer p = getPlayer(suspectName, player.color);
        	if (phase != GAME_PHASE.VOTING) {
        		listener.handleAction(user, new MoleResult(false, "Bad phase: " + phase));
        	}
        	else if (p != null) {
            	listener.handleAction(user, new MoleResult("Voting off: " + suspectName));
        		player.vote = p;
        		spam(player.user.name + " votes off: " + player.user.name);
        		MolePlayer suspect = checkVote(player.color);
        		if (suspect != null) {
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
        		listener.handleAction(user, new MoleResult(false, "Suspect not found"));
        	} 
    	}
    }
    
    public void run() {
    	playing = true;
    	setMole(COLOR_BLACK); setMole(COLOR_WHITE);
    	turn = COLOR_WHITE; board = new Board(); moveNum = 0;
    	listener.started(this);
    	while (playing) {
    		if (forfeit(turn)) winner(nextTurn()); 
    		else {
        		moveNum++;
      			spam("Turn #" + moveNum + ": " + colorString(turn));
       			boolean timeout = newPhase(GAME_PHASE.VOTING, voteTime);
       			Move move;
       			if (timeout && currentVotes.size() == 0) {
       				spam("No legal moves selected, picking randomly...");
       				move = pickMove(board.legalMoves());
       			}
       			else {
       				spam("Picking randomly from the following moves: \n" + listMoves());
       	 			move = pickMove(currentVotes);
       			}
       			spam("Selected Move: " + move);
       			if (makeMove(move).result) {
       				if (checkGameOver()) {
       					spam("Game over!");
       					playing = false;
       				}
       				else {
       					currentVotes.clear();
               			turn = nextTurn();
       				}
       			}
       			else { spam("WTF: " + move); return; } ////shouldn't occur
    		}
    	}
    	if (!deserted()) newPhase(GAME_PHASE.POSTGAME,300);
    	listener.finished(this);
    }
  
	private MolePlayer getPlayer(MoleUser user) {
		for (int color = 0; color <= 1; color++) {
			for (MolePlayer player : team[color]) if (player.user.equals(user)) return player; 
		} 
		return null;
	}
  
	private MolePlayer getPlayer(String name, int color) {
		for (MolePlayer player : team[color]) if (player.user.name.equalsIgnoreCase(name)) return player; 
		return null;
	}
  	
	private boolean deserted() {
		for (int color = 0; color <= 1; color++) {
			for (MolePlayer player : team[color]) if (!player.away && !player.ai) return false; 
		}
		return true;
	}
	
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
    
    private void aiFill(int color) {
    	int i = 0;
    	while (team[color].size() < minPlayers) {
        	MolePlayer player = new MolePlayer(MoleServ.DUMMIES[i++][color], this, color);
        	player.ai = true;
        	team[color].add(player);
    	}
    }
    
    private void setMole(int color) {
    	int p = (int)Math.floor(Math.random() * team[color].size());
    	MolePlayer player = team[color].get(p);
    	player.role = MolePlayer.ROLE.MOLE;
    	player.user.tell("You're the mole!");
    }
  
    private boolean forfeit(int color) {
    	for (MolePlayer player : team[color]) if (player.isActive()) return false; 
    	return true;
    }
  
    //TODO: awards, etc.
    private void winner(int color) {
    	spam(colorString(color) + " wins!");
    	playing = false;
    }
    
    private String colorString(int color) {
    	return (color == COLOR_BLACK) ? "Black" : "White";
    }
      
    private boolean checkGameOver() {
    	boolean gameover = false;
    	if (board.isStaleMate()) {
    		gameover = true;
    	}
    	else if (board.isMated()) {
    		gameover = true;
    	}
    	else if (board.isInsufficientMaterial()) {
    		gameover = true;
    	}
    	return gameover;
    }
  
    private int nextTurn() {
    	if (turn == COLOR_WHITE) return COLOR_BLACK; else return COLOR_WHITE;
    }
    
    private String listMoves() {
    	String list = ""; for (MoveVote mv : currentVotes) list += (mv + "\n");	return list;
    }
    
    private Move pickMove(List<Move> moves) {
    	int n = (int)(Math.random() * moves.size()); return moves.get(n);
    }
    private Move pickMove(ArrayList<MoveVote> moves) { //NOTE: purely random, no democracy, ergh
    	int n = (int)(Math.random() * moves.size()); return moves.get(n).move;
    }
  
    private MoveVote addVote(MolePlayer player, Move move) {
    	if (board.legalMoves().contains(move)) {
    		MoveVote preVote = null;
        	for (MoveVote vote : currentVotes) if (vote.player.equals(player)) preVote = vote;
        	if (preVote != null) currentVotes.remove(preVote);
        	MoveVote vote = new MoveVote(player,move);
        	currentVotes.add(vote);
        	if (currentVotes.size() == team[turn].size()) gameThread.interrupt();
        	return vote;
    	}
    	else return null;
    }
    
    private Move getMove(String movestr) {
    	return new Move(movestr,turn == COLOR_BLACK ? Side.BLACK : Side.WHITE);
    }
  
    private MoleResult makeMove(Move move) {
    	if (board.doMove(move)) {
    		ObjectNode node = MoleServ.mapper.createObjectNode();
    		node.put("lm",move.toString());
    		node.put("fen",board.getFen());
    		spam("game_update",node); 
    		return new MoleResult("Move: " + move);
    	}
    	return new MoleResult(false, "Invalid Move: " + move); //shouldn't occur
    }
  
    private MolePlayer checkVote(int color) {
    	MolePlayer suspect = null;
    	for (MolePlayer p : team[color]) {
    		if (p.isActive() && p.role != MolePlayer.ROLE.MOLE) {
    			if (suspect == null) suspect = p.vote; else if (suspect != p.vote) return null;
   			} 
    	} 
    	return suspect;
    }
  
    private MolePlayer getMole(int color) {
    	for (MolePlayer p : team[color]) if (p.role == MolePlayer.ROLE.MOLE) return p; 
     	return null;
    }
  
    private void award(int color, int bonus) {
    	for (MolePlayer p : team[color]) award(p, bonus); 
    }
  
    private void award(MolePlayer player, int bonus) {
    	if (player.isActive()) {
    		player.score += bonus;
    		spam(player.user.name + " gets " + player.user.name + " points");
    	} 
    }
  
    //private void spam(JsonNode node) { spam(WebSockServ.MSG_SERV, node); }
    private void spam(String msg) { spam(WebSockServ.MSG_SERV, msg); }
    private void spam(String type, String msg) {
    	ObjectNode node = MoleServ.mapper.createObjectNode();
    	node.put("msg", msg);
    	spam(type,node);
    }
    private void spam(String type, JsonNode node) {
    	for (int c = 0; c <= 1; c++) {
    		for (MolePlayer player : team[c]) {
    			if (!player.away) player.user.tell(type, node); 
    		} 
    	} 
    }
}
