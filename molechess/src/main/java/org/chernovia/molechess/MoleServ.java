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
import org.chernovia.utils.CommandLineParser;

//TODO: how do I export to pgn?
//stockplug M1 blindness
//~molevote bug
//~game specific chat
//~limit number of games a user may create
//~how do I spectate that game?
//~empty/pregame board timeouts
//~handle logins with same token
//
public class MoleServ extends Thread implements ConnListener, MoleListener {
	static final ObjectMapper mapper = new ObjectMapper();
	static final Logger logger = Logger.getLogger("MoleLog");
	static Pattern alphanumericPattern = Pattern.compile("^[a-zA-Z0-9]*$");
	static int MAX_STR_LEN = 30, MAX_USER_GAMES = 3, DEF_MOVE_TIME = 12;
	static boolean TESTING = false;
	static String STOCK_PATH = "stockfish/stockfish";
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
		CommandLineParser parser = new CommandLineParser(args);
		String[] path = parser.getArgumentValue("stockpath");  
		if (path != null) STOCK_PATH = path[0]; 
		log("Stock Path: " + STOCK_PATH);
		String[] movetime = parser.getArgumentValue("movetime"); 
		if (movetime != null) DEF_MOVE_TIME = Integer.parseInt(movetime[0]); 
		log("Move Time: " + DEF_MOVE_TIME);
		TESTING = parser.getFlag("testing"); 
		log("Testing: " + TESTING);
		new MoleServ(5555).start();
	}
	
	static public String getStringArg(String arg, String def) {
		String prop = System.getProperty(arg);
		if (prop == null) return def; else return prop;
	}
	
	static public int getIntArg(String arg, int def) {
		String prop = System.getProperty(arg);
		if (prop == null) return def; 
		else try { return Integer.parseInt(prop); } 
		catch (NumberFormatException oops) { return def; }
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
				gameObj.add(((MoleGame)entry.getValue()).toJSON(false)); 
			}
			return gameObj;
		}
		catch (ConcurrentModificationException fuck) { 
			log(Level.SEVERE,fuck.getMessage()); return null; 
		}
	}
	  
	private boolean validString(String str) {
		boolean valid = false;
		if (str.length() > 0 && str.length() < MAX_STR_LEN) {
			if (alphanumericPattern.matcher(str.trim()).find()) valid = true;
		}
		return valid; 
	}
	
	private int countGames(MoleUser creator) {
		int count = 0;
		for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
			if (((MoleGame)entry.getValue()).getCreator().equals(creator)) count++;
		}
		return count;
	}
  
	private void newGame(MoleUser creator, String title) {
		if (validString(title)) {
			if (games.containsKey(title)) {
				creator.tell(WebSockServ.MSG_ERR, "Failed to create game: title already exists");
			}
			else if (countGames(creator) > MoleServ.MAX_USER_GAMES) {
				creator.tell(WebSockServ.MSG_ERR, 
						"Failed to create game: too many games (" + MoleServ.MAX_USER_GAMES + ")");
			}
			else {
				MoleGame game = new MoleGame(creator, title, this); game.setMoveTime(DEF_MOVE_TIME);
				games.put(title, game);
				updateAll();
			}
		} 
		else {
			creator.tell(WebSockServ.MSG_ERR, "Failed to create game: bad title");
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
			if (typeTxt.equals("login")) {
				handleLogin(conn,dataTxt,MoleServ.TESTING);
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
						game.castMoleVote(user, suspect.asText());
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
				JsonNode chatNode = dataNode.get("msg"); JsonNode sourceNode = dataNode.get("source");
				if (msgNode != null && sourceNode != null) {
					String chatMsg = chatNode.asText("?"); node.put("msg", chatMsg);
					String source = sourceNode.asText("?"); node.put("source", source);
					if (source.equals("lobby")) spam("chat", node);
					else gameChat(source,node);
				}
				else { user.tell(WebSockServ.MSG_ERR, "Bad chat"); }
			} 
			else {
				user.tell(WebSockServ.MSG_ERR, "Unknown command");
			} 
		} 
		catch (JsonMappingException e) { log("JSON Mapping goof: " + e.getMessage()); } 
		catch (JsonProcessingException e) { log("JSON Processing error: " + e.getMessage()); } 
		catch (NullPointerException e) { e.printStackTrace(); }
	}
	
	private void gameChat(String title, JsonNode node) {
		for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
			MoleGame game = (MoleGame)entry.getValue();
			if (game.getTitle().equals(title)) game.spam("chat",node);
		}
	}
  	
  	public void spam(String type, String msg) {
  		ObjectNode node = mapper.createObjectNode();
  		node.put("msg", msg);
  		spam(type, node);
  	}
  
  	public void spam(String type, JsonNode node) {
  		for (MoleUser user : this.users) user.tell(type, node); 
  	}
  	
	private void updateUser(MoleUser user) { user.tell("games_update", getAllGames()); }

  	private MoleUser handleRelogging(Connection conn, String token) {
		MoleUser user = getUserByToken(token); 
		if (user != null) {
			user.tell("Multiple login detected, closing");
			user.getConn().close();
			user.setConn(conn); //users.remove(mu); users.add(newUser);
			return user;
		}
		else return null;
	}
	
	//private void handleLogin(Connection conn, String token) { handleLogin(conn,token,false); }
	private void handleLogin(Connection conn, String token, boolean testing) {
		MoleUser relogger = handleRelogging(conn,token);
		if (relogger != null) {
			relogger.tell(WebSockServ.MSG_LOG_SUCCESS, "Relog Successful: Welcome back!");
			updateUser(relogger);
		}
		else if (testing) {
			String name = token; if (validString(name)) {
				MoleUser newUser = new MoleUser(conn, token, name);
				users.add(newUser);	
				newUser.tell(WebSockServ.MSG_LOG_SUCCESS, "Test Login Successful: Welcome!");
				updateUser(newUser);
			}
			else conn.tell(WebSockServ.MSG_ERR, "Ruhoh: Invalid Data!");
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
					MoleUser newUser = new MoleUser(conn, token, username.asText());
					users.add(newUser);
					newUser.tell(WebSockServ.MSG_LOG_SUCCESS, "Login Successful: Welcome!");
					updateUser(newUser);
				}
				else conn.tell(WebSockServ.MSG_ERR, "Login Error: weird Lichess API result");
			}
		}
	}

	@Override
	public void notify(MoleUser user, MoleResult action, boolean update) {
		if (user != null) {
			if (action.success) user.tell(action.message);
			else user.tell(WebSockServ.MSG_ERR, action.message);
		}
		if (update) updateAll(); //TODO: distinguish between game and server-wide updates
	}
	
	@Override
	public void started(MoleGame game) {		
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
	  				if (game.isDefunct()) games.remove(entry.getKey()); purged = true;
	  			}
	  			if (purged) updateAll();
			}
			catch (InterruptedException e) { running = false; }
		}
		serv.stopSrv();
		log("Finished main MoleServ loop");
	}
}
