package org.chernovia.molechess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.chernovia.lib.lichess.LichessSDK;
import org.chernovia.lib.zugserv.*;
import org.chernovia.lib.zugserv.web.*;

//TODO: how do I export to pgn?
//game specific chat
//limit number of games a user may create
//stockplug M1 blindness
//~how do I spectate that game?
//~empty/pregame board timeouts
//~handle logins with same token
//
public class MoleServ extends Thread implements ConnListener, MoleListener {
	static final ObjectMapper mapper = new ObjectMapper();
	static final Logger logger = Logger.getLogger("MoleLog");
	static Pattern alphanumericPattern = Pattern.compile("^[a-zA-Z0-9]*$");
	static int MAX_STR_LEN = 30, DEF_VOTE_TIME = 12;
	static boolean TESTING = false;
	static MoleUser[][] DUMMIES;
	static String STOCK_PATH;
	private ArrayList<MoleUser> users = new ArrayList<>();
	private HashMap<String, MoleGame> games = new HashMap<>();
	private ZugServ serv;
	private int purgeFreq = 30;
	boolean running = false;
	
	public static void log(String msg) { log(Level.INFO,msg);	}
	public static void log(Level level, String msg) { 
		logger.log(level,msg + " (" + LocalDateTime.now() + ")"); 
	}
	
	public static void main(String[] args) {
		DEF_VOTE_TIME = Integer.parseInt(args[0]);
		STOCK_PATH = args[1];
		DUMMIES = new MoleUser[12][2];
		for (int c=0;c<=1;c++) for (int i=0;i<12;i++) {
			DUMMIES[i][c] = new MoleUser(null,null,"Dummy" + (c==0 ? "B" : "W") + i);
		}
		new MoleServ(5555).start();;
	}
	
	public MoleServ(int port) {
		log("Constructing MoleServ on port: " + port);
		serv = (ZugServ)new WebSockServ(port, this);
		serv.startSrv();
	}
	
	private MoleUser getUserByToken(String token) {
		for (MoleUser user : users) if (user.oauth.equals(token)) return user; 
		return null;
	}
	
	private MoleUser getUser(Connection conn) {
		for (MoleUser user : users) if (user.sameConnection(conn)) return user; 
		return null;
	}
	
	private ArrayNode getAllGames() {
		try {
			ArrayNode gameObj = mapper.createArrayNode();
			for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
				gameObj.add(((MoleGame)entry.getValue()).toJSON()); 
			}
			return gameObj;
		}
		catch (ConcurrentModificationException fuck) { 
			log(Level.SEVERE,fuck.getMessage()); return null; 
		}
	}
	
  	private MoleUser switchCheck(Connection conn, String token) {
			MoleUser mu = getUserByToken(token); 
			if (mu != null) {
				mu.tell("Multiple login detected, closing");
				mu.getConn().close();
				mu.setConn(conn); //users.remove(mu); users.add(newUser);
				return mu;
			}
			else return null;
  	}
  	
  	private void testLogin(Connection conn, String nameToken) {
  		if (validString(nameToken)) {
  			if (switchCheck(conn,nameToken) == null) {
  				conn.tell(WebSockServ.MSG_LOG_SUCCESS, "Relog Successful: Welcome back!");
  			}
  			else {
  				MoleUser newUser = new MoleUser(conn, nameToken, nameToken);
  				users.add(newUser);	
  				newUser.tell(WebSockServ.MSG_LOG_SUCCESS, "Login Successful: Welcome!");
  			}
  			conn.tell("games_update", getAllGames());
  		}
  		else conn.tell(WebSockServ.MSG_ERR, "Ruhoh: Invalid Data!");
  	}
  
	private boolean validString(String str) {
		boolean valid = false;
		if (str.length() > 0 && str.length() < MAX_STR_LEN) {
			if (alphanumericPattern.matcher(str.trim()).find()) valid = true;
		}
		return valid; 
	}
  
	private void newGame(MoleUser creator, String title) {
		if (validString(title) && !this.games.containsKey(title)) {
			MoleGame game = new MoleGame(creator, title, this); game.setVoteTime(DEF_VOTE_TIME);
			games.put(title, game);
			updateAll();
		} 
		else {
			creator.tell(WebSockServ.MSG_ERR, "Failed to create game");
		} 
	}
    
	public void newMsg(Connection conn, int channel, String msg) { //log("NewMsg: " + msg);
		try {
			MoleUser user = getUser(conn);
			JsonNode msgNode = mapper.readTree(msg);
			JsonNode typeNode = msgNode.get("type"), dataNode = msgNode.get("data");
			if (typeNode == null || dataNode == null) {
				conn.tell(WebSockServ.MSG_ERR, "Error: Bad Data(null)"); return;
			}
			String typeTxt = typeNode.asText(), dataTxt = dataNode.asText();
			if (typeTxt.equals("oauth")) {
				String token = dataTxt; 
				if (switchCheck(conn,token) != null) {
					conn.tell(WebSockServ.MSG_LOG_SUCCESS, "Relog Successful: Welcome back!");
				}
				else if (token == null) {
					conn.tell(WebSockServ.MSG_ERR, "Login Error: Missing Oauth Token"); 
				}
				else {
					JsonNode accountData = LichessSDK.apiRequest("account", token);
					if (accountData == null) conn.tell(WebSockServ.MSG_ERR, "Login Error: Bad Oauth Token");
					else {
						JsonNode username = accountData.get("username");
						if (username != null) {
							users.add(new MoleUser(conn, token, username.asText()));
							conn.tell(WebSockServ.MSG_LOG_SUCCESS, "Login Successful: Welcome!");
						}
						else conn.tell(WebSockServ.MSG_ERR, "Login Error: weird Lichess API result");
					}
				}
			} 
			else if (MoleServ.TESTING && typeTxt.equals("login")) {
				testLogin(conn,dataTxt);
			} 
			else if (user == null) {
				conn.tell(WebSockServ.MSG_ERR, "Please log in");
			} 
			else if (typeTxt.equals("newgame")) {
				if (validString(dataTxt)) newGame(user, dataTxt);
				else user.tell(WebSockServ.MSG_ERR, "Ruhoh: Invalid Data!");
			} 
			else if (typeTxt.equals("obsgame")) {
				MoleGame game = games.get(dataTxt);
				if (game == null) { user.tell(WebSockServ.MSG_ERR, "Game does not exist"); } 
				else { game.addObserver(user); } 
			}
			else if (typeTxt.equals("joingame")) {
				String title = dataNode.get("title").asText();
				int color = dataNode.get("color").asInt();
				MoleGame game = games.get(title);
				if (game == null) { user.tell(WebSockServ.MSG_ERR, "Game does not exist");	} 
				else { game.addPlayer(user, color);	} 
			} 
			else if (typeTxt.equals("partgame")) {
				MoleGame game = games.get(dataTxt);
				if (game == null) { user.tell(WebSockServ.MSG_ERR, "Game not joined: " + dataTxt); } 
				else { game.dropPlayer(user); }
			} 
			else if (typeTxt.equals("startgame")) {
				MoleGame game = this.games.get(dataTxt);
				if (game == null) { user.tell(WebSockServ.MSG_ERR, "You're not in a game");	} 
				else { game.startGame(user); } 
			}
			else if (typeTxt.equals("move")) {
				JsonNode title = dataNode.get("board");
				JsonNode move = dataNode.get("move");
				JsonNode prom = dataNode.get("promotion");
				if (title != null && move != null) {
					MoleGame game = games.get(title.asText());
					if (game == null) { //unlikely but possible?
						user.tell(WebSockServ.MSG_ERR, "Game not found: " + title);
					} 
					else {
						game.voteMove(user, move.asText() + (prom.isNull() ? "" : prom.asText()));
					} 
				} 
				else {
					user.tell(WebSockServ.MSG_ERR, "WTF: " + dataTxt);
				} 
			} 
			else if (typeTxt.equals("voteoff")) {
				JsonNode title = dataNode.get("board");
				JsonNode suspect = dataNode.get("suspect");
				if (title != null && suspect != null) {
					MoleGame game = games.get(title.asText());
					if (game == null) {
						user.tell(WebSockServ.MSG_ERR, "Game not found: " + title);
					} 
					else {
						game.castVote(user, suspect.asText());
					} 
				} 
				else {
					user.tell(WebSockServ.MSG_ERR, "WTF: " + dataTxt);
				} 
			} 
			else if (typeTxt.equals("resign")) {
				MoleGame game = games.get(dataTxt);
				if (game == null) {
					user.tell(WebSockServ.MSG_ERR, "Game not found: " + dataTxt);
				} 
				else {
					game.resign(user);
				}
			}
			else if (typeTxt.equals("chat")) {
				ObjectNode node = mapper.createObjectNode();
				node.put("player", user.name);
				node.put("msg", dataTxt);
				spam("chat", node);
			} 
			else {
				user.tell(WebSockServ.MSG_ERR, "Unknown command");
			} 
		} 
		catch (JsonMappingException e) { log("JSON Mapping goof: " + e.getMessage()); } 
		catch (JsonProcessingException e) { log("JSON Processing error: " + e.getMessage()); } 
		catch (NullPointerException e) { e.printStackTrace(); }
	}
  	
  	public void spam(String type, String msg) {
  		ObjectNode node = mapper.createObjectNode();
  		node.put("msg", msg);
  		spam(type, node);
  	}
  
  	public void spam(String type, JsonNode node) {
  		for (MoleUser user : this.users) user.tell(type, node); 
  	}

	@Override
	public void started(MoleGame game) {		
	}
	
	@Override
	public void handleAction(MoleUser user, MoleResult action) {
		if (user != null) {
			if (action.result) {
				user.tell(action.message);
				updateAll();
			} 
			else {
				user.tell(WebSockServ.MSG_ERR, action.message);
			} 
		}
	}

	@Override
	public void finished(MoleGame game) {
		games.remove(game.getTitle());
		updateAll();
	}
	
	@Override
  	public void connected(Connection conn) {}
    
	@Override
  	public void disconnected(Connection conn) { //TODO: concurrency argh
  		MoleUser user = getUser(conn);
  		if (user != null) {
  			for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
  				MoleGame game = (MoleGame)entry.getValue();
  				game.dropPlayer(user); 				
  			}
  		}
  		updateAll();
  	}

	@Override
	public void updateAll() {
		spam("games_update", getAllGames());
	}
	
	public void run() {
		log("Starting main MoleServ loop");
		running = true;
		while (running) {
			boolean purged = false;
			try { 
				Thread.sleep(purgeFreq * 1000); 
	  			for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
	  				MoleGame game = (MoleGame)entry.getValue();
	  				if (game.isDefunct(99999)) games.remove(entry.getKey()); purged = true;
	  			}
	  			if (purged) updateAll();
			}
			catch (InterruptedException e) { running = false; }
		}
		serv.stopSrv();
		log("Finished main MoleServ loop");
	}
}

