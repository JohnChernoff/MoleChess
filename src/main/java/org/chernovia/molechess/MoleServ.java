package org.chernovia.molechess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.FileNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.chernovia.lib.lichess.LichessSDK;
import org.chernovia.lib.zugserv.*;
import org.chernovia.lib.zugserv.web.*;
import org.chernovia.utils.CommandLineParser;

//TODO: how do I export to pgn?
//ornicar2: you need to use a ConcurrentHashMap
//update player leaving
//50 move rule draws
//Double Mole Role?
//Inspector Role?
//Takebacker Role?
//~Defecting Mole rating change bug
//~handle AWOL team members
//~handle draws
//~stockplug M1 blindness
//~database
//~molevote bug
//~game specific chat
//~limit number of games a user may create
//~how do I spectate that game?
//~empty/pregame board timeouts
//~handle logins with same token
//
public class MoleServ extends Thread implements ConnListener, MoleListener {
	static final String VERSION = "0.1"; 
	static final String MSG_GAME_UPDATE = "game_update";
	static final String MSG_GAMES_UPDATE = "games_update";
	static final ObjectMapper OBJ_MAPPER = new ObjectMapper();
	static final Logger LOGGER = Logger.getLogger("MoleLog");
	static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9]*$");
	static final int MAX_STR_LEN = 30;
	static String STOCK_PATH = "stockfish/stockfish";
	static int STOCK_STRENGTH = 2000, STOCK_MOLE_STRENGTH = 1500;
	private ArrayList<MoleUser> users = new ArrayList<>();
	private HashMap<String, MoleGame> games = new HashMap<>();
	private ZugServ serv;
	private int purgeFreq = 30, maxUserGames = 3, defMoveTime = 12;
	private long startTime; 
	private boolean running = false;
	private boolean testing = false;
	private MoleBase moleBase;
	
	public static void main(String[] args) { //MoleGame.getRandomNames("resources/molenames.txt");
		new MoleServ(5555,args).start();
	}
	
	public MoleServ(int port, String[] args) {
		CommandLineParser parser = new CommandLineParser(args);
		String[] path = parser.getArgumentValue("stockpath");  
		if (path != null) STOCK_PATH = path[0]; 
		log("Stock Path: " + STOCK_PATH);
		String[] movetime = parser.getArgumentValue("movetime"); 
		if (movetime != null) defMoveTime = Integer.parseInt(movetime[0]); 
		log("Move Time: " + defMoveTime);
		testing = parser.getFlag("testing"); 
		log("Testing: " + testing);
		log("Constructing MoleServ on port: " + port);
		serv = (ZugServ)new WebSockServ(port, this);
		serv.startSrv();
		startTime = System.currentTimeMillis();
		moleBase = new MoleBase("localhost:3306",
		parser.getArgumentValue("dbuser")[0],parser.getArgumentValue("dbpass")[0],"molechess");
	}
	
	private void addUserData(MoleUser user) {
		if (refreshUserData(user) == null) {
			MoleBase.MoleQuery query = moleBase.makeQuery(
				"INSERT INTO `players` (`Name`, `Wins`, `Losses`, `Rating`, `DateCreated`, `About`) " + 
				"VALUES ('" + user.name + "', '0', '0', '1600', CURRENT_TIMESTAMP, '')");
			query.runUpdate(); //("Insert Result: " + query.runUpdate());
		}
	}
	
	private MoleUser.MoleData refreshUserData(MoleUser user) {
		if (user.getConn() == null) return null;
		MoleBase.MoleQuery query = moleBase.makeQuery(
				"SELECT * FROM `players` WHERE Name='" + user.name + "'");
		ResultSet rs = query.runQuery(); 
		try {
			if (rs != null && rs.next()) {
				user.setData(
					rs.getInt("Wins"),rs.getInt("Losses"),rs.getInt("Rating"),rs.getString("About"));
				query.cleanup(); return user.getData();
			}
		}
		catch (SQLException doh) { log(Level.SEVERE,doh.getMessage()); }
		query.cleanup(); return null;
	}
	
	public void updateUserData(ArrayList<MolePlayer> winners, ArrayList<MolePlayer> losers, boolean draw) {
		for (MolePlayer p : winners) refreshUserData(p.user);
		for (MolePlayer p : losers) refreshUserData(p.user);
			int ratingDiff = (int)((calcAvgRating(winners) - calcAvgRating(losers)) * .04);
		int ratingGain = draw ? ratingDiff : 16 - ratingDiff;
		if (ratingGain > 32) ratingGain = 32; else if (ratingGain < 0) ratingGain = 0;
		for (MolePlayer p : winners) {
			if (!p.ai) updateUserRating(p.user,p.user.getData().rating + 
			(p.role == MolePlayer.ROLE.MOLE ? -ratingGain : ratingGain),true);
		}
		for (MolePlayer p : losers) {
			if (!p.ai) updateUserRating(p.user,p.user.getData().rating - 
			(p.role == MolePlayer.ROLE.MOLE ? -ratingGain : ratingGain),false);
		}
	}
	
	private int calcAvgRating(ArrayList<MolePlayer> team) {
		int total = 0; for (MolePlayer p : team) {
			int rating = p.ai ? 
			(p.role == MolePlayer.ROLE.MOLE ? MoleServ.STOCK_MOLE_STRENGTH : MoleServ.STOCK_STRENGTH) : 
			p.user.getData().rating; 
			total += rating; 
		}
		return Math.round(total/team.size());
	}
	
	private void updateUserRating(MoleUser user, int newRating, boolean winner) {
		MoleBase.MoleQuery query = moleBase.makeQuery(
			"UPDATE `players` SET Rating='" + newRating + "' WHERE Name='" + user.name + "'");
			query.runUpdate();
		user.tell("Rating change: " + user.getData().rating + " -> " + newRating);
		if (winner) {
			query.setQueryString("UPDATE `players` SET Wins='" + (user.getData().wins + 1) + 
			"' WHERE Name='" + user.name + "'");
			query.runUpdate();
		}
		else {
			query.setQueryString("UPDATE `players` SET Losses='" + (user.getData().losses + 1) + 
			"' WHERE Name='" + user.name + "'");
			query.runUpdate();
		}
	}
	
	private ArrayNode getTopPlayers(int n) {
		ArrayNode playlist = OBJ_MAPPER.createArrayNode();
		MoleBase.MoleQuery query = moleBase.makeQuery(
			"SELECT * FROM players ORDER BY Rating DESC LIMIT " + n);
		ResultSet rs = query.runQuery(); 
		if (rs != null) try {
			while (rs.next()) {
				ObjectNode node = OBJ_MAPPER.createObjectNode();
				node.put("name", rs.getString("Name"));
				node.put("rating", rs.getInt("Rating"));
				playlist.add(node);
			}
		}
		catch (SQLException ergh) {}
		return playlist;
	}
	
	public JsonNode toJSON() {
		ObjectNode node = OBJ_MAPPER.createObjectNode();
		node.put("Uptime: ", (System.currentTimeMillis() - startTime)/1000);
		ArrayNode usersNode = OBJ_MAPPER.createArrayNode();
		for (MoleUser user : users) usersNode.add(user.toJSON(true));
		node.set("users",usersNode);
		return node;
	}
	
	private MoleUser getUserByToken(String token) {
		for (MoleUser user : users) if (user.oauth.equals(token)) return user; 
		return null;
	}
	
	private MoleUser getUser(Connection conn) {
		for (MoleUser user : users) if (user.sameConnection(conn)) return user; 
		return null;
	}
	
	private ArrayNode getAllGames(boolean shallowCopy) {
		try {
			ArrayNode gameObj = OBJ_MAPPER.createArrayNode();
			for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
				MoleGame game = (MoleGame)entry.getValue();
				if (shallowCopy) {
					ObjectNode node = OBJ_MAPPER.createObjectNode();
					node.put("title", game.getTitle());
					gameObj.add(node);
				}
				else gameObj.add(game.toJSON(false));
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
			if (ALPHANUMERIC_PATTERN.matcher(str.trim()).find()) valid = true;
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
			else if (countGames(creator) > maxUserGames) {
				creator.tell(WebSockServ.MSG_ERR, 
						"Failed to create game: too many games (" + maxUserGames + ")");
			}
			else {
				MoleGame game = new MoleGame(creator, title, this); game.setMoveTime(defMoveTime);
				games.put(title, game);
				updateGames(false);
			}
		} 
		else {
			creator.tell(WebSockServ.MSG_ERR, "Failed to create game: bad title");
		} 
	}
    
	public void newMsg(Connection conn, int channel, String msg) { //log("NewMsg: " + msg);
		try {
			MoleUser user = getUser(conn);
			JsonNode msgNode = OBJ_MAPPER.readTree(msg);
			JsonNode typeNode = msgNode.get("type"), dataNode = msgNode.get("data");
			if (typeNode == null || dataNode == null) {
				conn.tell(WebSockServ.MSG_ERR, "Error: Bad Data(null)"); return;
			}
			String typeTxt = typeNode.asText(), dataTxt = dataNode.asText();
			if (typeTxt.equals("login")) {
				handleLogin(conn,dataTxt,testing);
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
				JsonNode suspect = dataNode.get("player");
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
			else if (typeTxt.equals("kickoff")) {
				JsonNode title = dataNode.get("board");
				JsonNode suspect = dataNode.get("player");
				if (title != null && suspect != null) {
					MoleGame game = games.get(title.asText());
					if (game == null) {
						user.tell(WebSockServ.MSG_ERR, "Game not found: " + title);
					} 
					else {
						game.kickPlayer(user, suspect.asText());
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
			else if (typeTxt.equals("top")) {
				user.tell("top",getTopPlayers(Integer.parseInt(dataTxt)));
			}
			else if (typeTxt.equals("chat")) {
				ObjectNode node = OBJ_MAPPER.createObjectNode();
				node.put("player", user.name);
				JsonNode chatNode = dataNode.get("msg"); JsonNode sourceNode = dataNode.get("source");
				if (msgNode != null && sourceNode != null) {
					String chatMsg = chatNode.asText("?"); node.put("msg", chatMsg);
					String source = sourceNode.asText("?"); node.put("source", source);
					if (source.equals("lobby")) spam("chat", node);
					else broadcast(source,node);
				}
				else { user.tell(WebSockServ.MSG_ERR, "Bad chat"); }
			} 
			else if (typeTxt.equals("update")) {
				MoleGame game = games.get(dataTxt);
				if (game != null) user.tell(MSG_GAME_UPDATE,game.toJSON(true));
			}
			else if (typeTxt.equals("cmd")) {
				handleCmd(user,dataNode);
			}
			else {
				user.tell(WebSockServ.MSG_ERR, "Unknown command");
			} 
		} 
		catch (JsonMappingException e) { log("JSON Mapping goof: " + e.getMessage()); } 
		catch (JsonProcessingException e) { log("JSON Processing error: " + e.getMessage()); } 
		catch (NullPointerException e) { e.printStackTrace(); }
	}
	
	private void broadcast(String title, JsonNode node) {
		for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
			MoleGame game = (MoleGame)entry.getValue();
			if (game.getTitle().equals(title)) game.spam("chat",node);
		}
	}
	
	private void handleCmd(MoleUser user, JsonNode cmdNode) {
		JsonNode cmd = cmdNode.get("cmd");
		if (cmd.isNull()) {
			user.tell(WebSockServ.MSG_ERR,"Error: null command");
		}		
		else switch (cmdNode.get("cmd").asText()) {
			case "info":
				user.tell("info",this.toJSON()); break;
			case "who":
				user.tell("users",this.toJSON()); break;
			case "uptime":
				user.tell(WebSockServ.MSG_SERV,
						"Uptime: " + ((System.currentTimeMillis() - startTime) / 1000)); break;
			case "finger":
				user.tell(WebSockServ.MSG_SERV,refreshUserData(user).toString()); break;
			default: user.tell(WebSockServ.MSG_ERR,"Error: command not found");
		}
	}
  	
  	//private void spam(String type, String msg) {
  	//	ObjectNode node = mapper.createObjectNode(); node.put("msg", msg); spam(type, node);
  	//}
  	private void spam(String type, JsonNode node) {
  		for (MoleUser user : this.users) user.tell(type, node); 
  	}
  	
	private void updateGameList(MoleUser user) { user.tell(MSG_GAMES_UPDATE, getAllGames(false)); }

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
			addUser(relogger,"Relog Successful: Welcome back!",false);
		}
		else if (testing) {
			String name = token; if (validString(name)) {
				addUser(new MoleUser(conn, token, name),"Test Login Successful: Welcome!");
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
					addUser(new MoleUser(conn, token, username.asText()),"Login Successful: Welcome!");
				}
				else conn.tell(WebSockServ.MSG_ERR, "Login Error: weird Lichess API result");
			}
		}
	}
	
	private void addUser(MoleUser user, String msg) { addUser(user,msg,true); }
	private void addUser(MoleUser user, String msg, boolean add) {
		if (add) { users.add(user); addUserData(user); }
		user.tell(WebSockServ.MSG_LOG_SUCCESS, msg);
		updateGameList(user);
		user.tell("top",getTopPlayers(10));
		refreshUserData(user);
	}

	@Override
	public void updateUser(MoleUser user,MoleGame game,MoleResult action,boolean moves) {
		if (user != null) {
			if (action.success) {
				user.tell(action.message);
				user.tell(MSG_GAME_UPDATE,game.toJSON(moves));
			}
			else user.tell(WebSockServ.MSG_ERR,action.message);
		}
	}
	
	@Override
	public void updateGame(MoleGame game,MoleResult action,boolean moves) {
		if (action.success) {
			game.spam(action.message);
			game.spam(MSG_GAME_UPDATE,game.toJSON(moves));
		}
		else {
			game.spam(WebSockServ.MSG_ERR,action.message);
		}
	}
	
	@Override
	public void started(MoleGame game) {
		game.spam(MSG_GAME_UPDATE, game.toJSON(true));
		updateGames(false);
	}
	

	@Override
	public void finished(MoleGame game) {
		games.remove(game.getTitle());
		updateGames(false);
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
  	}
	
	private void updateGames(boolean deepcopy) {
		spam("games_update", getAllGames(deepcopy));
	}
	
	public void run() {
		log("Starting main MoleServ loop");
		running = true;
		while (running) {
			boolean purged = false;
			try { 
				Thread.sleep(purgeFreq * 1000); //TODO: use ConcurrentHashMap (ornicar)
	  			for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
	  				MoleGame game = (MoleGame)entry.getValue();
	  				if (game.isDefunct()) {
	  					games.remove(entry.getKey()); purged = true;
	  				}
	  			}
	  			if (purged) updateGames(false);
			}
			catch (InterruptedException e) { running = false; }
		}
		serv.stopSrv();
		log("Finished main MoleServ loop");
	}
	
	public static void log(String msg) { log(Level.INFO,msg);	}
	public static void log(Level level, String msg) { 
		LOGGER.log(level,msg + " (" + LocalDateTime.now() + ")"); 
	}
	
    public static List<String> loadRandomNames(final String filename) {
		List<String> names = new ArrayList<>();
		try {
			java.io.File file = new java.io.File(filename);
			Scanner scanner = new Scanner(file);
			while (scanner.hasNextLine()) names.add(scanner.nextLine());
			scanner.close();
			return names;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			names = Arrays.asList("Steinitz", "Lasker", "Capablanca", "Karpov", "Kasparov");
			return names;
		} finally {
			log("Names: " + names.size());
		}
	}


}