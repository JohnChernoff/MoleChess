package org.chernovia.molechess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.chernovia.lib.zugserv.web.*;

public class MoleServ implements ConnListener, MoleListener {
	static final ObjectMapper mapper = new ObjectMapper();
	static final Logger logger = Logger.getLogger("MoleLog");
	static int MAX_STR_LEN = 32;
	static boolean TESTING = true;
	static MoleUser[][] DUMMIES;
	ArrayList<MoleUser> users = new ArrayList<>();
	HashMap<String, MoleGame> games = new HashMap<>();
	ZugServ serv;
  
	public static void main(String[] args) {
		DUMMIES = new MoleUser[12][2];
		for (int c=0;c<=1;c++) for (int i=0;i<12;i++) {
			DUMMIES[i][c] = new MoleUser(null,null,"Dummy" + (c==0 ? "B" : "W") + i);
		}
		new MoleServ(5555);
	}
  
	public MoleServ(int port) {
		logger.log(Level.ALL, "Hello!");
		serv = (ZugServ)new WebSockServ(port, this);
		serv.startSrv();
	}
  
	public MoleUser getUser(Connection conn) {
		for (MoleUser user : users) if (user.sameConnection(conn)) return user; 
		return null;
	}
  
	public MoleUser getUser(String name) {
		for (MoleUser user : users) if (user.name.equals(name)) return user; 
		return null;
	}
  
	public boolean validString(String str) {
		return (str.length() > 0 && str.length() < MAX_STR_LEN);
	}
  
	public void newGame(MoleUser creator, String title) {
		if (validString(title) && !this.games.containsKey(title)) {
			games.put(title, new MoleGame(creator, title, this));
			spam("games_update", getAllGames());
		} 
		else {
			creator.tell(WebSockServ.MSG_ERR, "Failed to create game");
		} 
	}
  
	public ArrayNode getAllGames() {
		ArrayNode gameObj = mapper.createArrayNode();
		for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
			gameObj.add(((MoleGame)entry.getValue()).toJSON()); 
		}
		return gameObj;
	}
  
	public void newMsg(Connection conn, int channel, String msg) {
		try {
			logger.log(Level.INFO, msg);
			MoleUser user = getUser(conn);
			JsonNode msgNode = mapper.readTree(msg);
			JsonNode typeNode = msgNode.get("type"), dataNode = msgNode.get("data");
			String typeTxt = typeNode.asText(), dataTxt = dataNode.asText();
			if (typeTxt.equals("oauth")) {
				JsonNode accountData = dataNode.get("API"); //LichessSDK.apiRequest("account", dataTxt);
				if (accountData == null) {
					conn.tell(WebSockServ.MSG_ERR, "Login Error: Bad Oauth Token"); 
				}
				JsonNode username = accountData.get("username");
				if (username != null) {
					users.add(new MoleUser(conn, dataTxt, username.asText()));
					conn.tell(WebSockServ.MSG_LOG_SUCCESS, "Login Successful: Welcome!");
				} 
			} 
			else if (TESTING && typeTxt.equals("login")) {
				users.add(new MoleUser(conn, "", dataTxt));
				conn.tell(WebSockServ.MSG_LOG_SUCCESS, "Login Successful: Welcome!");
				conn.tell("games_update", getAllGames());
			} 
			else if (user == null) {
				conn.tell(WebSockServ.MSG_ERR, "Please log in");
			} 
			else if (typeTxt.equals("newgame")) {
				newGame(user, dataTxt); //TODO: sanity check
			} 
			else if (typeTxt.equals("joingame")) {
				String title = dataNode.get("title").asText();
				int color = dataNode.get("color").asInt();
				MoleGame game = games.get(title);
				if (game == null) {
					user.tell(WebSockServ.MSG_ERR, "Game does not exist");
				} 
				else {
					game.addPlayer(user, color);
				} 
			} 
			else if (typeTxt.equals("partgame")) {
				MoleGame game = games.get(dataTxt);
				if (game == null) {
					user.tell(WebSockServ.MSG_ERR, "Game not joined: " + dataTxt);
				} 
				else {
					game.dropPlayer(user);
				}
			} 
			else if (typeTxt.equals("startgame")) {
				MoleGame game = this.games.get(dataTxt);
				if (game == null) {
					user.tell(WebSockServ.MSG_ERR, "You're not in a game");
				} 
				else {
					game.startGame(user);
				} 
			} 
			else if (typeTxt.equals("move")) {
				JsonNode title = dataNode.get("board");
				JsonNode move = dataNode.get("move");
				if (title != null && move != null) {
					MoleGame game = games.get(title.asText());
					if (game == null) {
						user.tell(WebSockServ.MSG_ERR, "Game not found: " + title);
					} 
					else {
						game.voteMove(user, move.asText());
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
		catch (JsonMappingException e) { logger.log(Level.INFO, "JSON oops: " + e.getMessage()); } 
		catch (JsonProcessingException e) { logger.log(Level.INFO, "JSON error: " + e.getMessage()); } 
		catch (NullPointerException e) { e.printStackTrace(); } 
	}
	
  	public void spam(String type, String msg) {
  		ObjectNode node = mapper.createObjectNode();
  		node.put("msg", msg);
  		spam(type, (JsonNode)node);
  	}
  
  	public void spam(String type, JsonNode node) {
  		for (MoleUser user : this.users) user.tell(type, node); 
  	}

	@Override
	public void started(MoleGame game) {		
	}
	
	@Override
	public void handleAction(MoleUser user, MoleResult action) {
		if (action.result) {
			user.tell(action.message);
			spam("games_update", getAllGames());
		} 
		else {
			user.tell(WebSockServ.MSG_ERR, action.message);
		} 
	}

	@Override
	public void finished(MoleGame game) {
		games.remove(game.getTitle());
		spam("games_update", getAllGames());
	}
	
	@Override
  	public void connected(Connection conn) {}
    
	@Override
  	public void disconnected(Connection conn) {
  		MoleUser user = getUser(conn);
  		if (user != null) {
  			for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
  				MoleGame game = (MoleGame)entry.getValue();
  				game.dropPlayer(user); 				
  			}
  		}
  		spam("games_update", (JsonNode)getAllGames());
  	}
}

