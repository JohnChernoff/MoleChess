package org.chernovia.molechess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.chernovia.lib.zugserv.web.*;

public class MoleServ implements ConnListener {
  static final ObjectMapper mapper = new ObjectMapper();
  
  static final Logger logger = Logger.getLogger("MoleLog");
  
  static final String[] MOLEPLAYERS = new String[] { "MysteriousChallenger", "ZugTest" };
  
  static String MOLE_OAUTH_BLACK;
  
  static String MOLE_OAUTH_WHITE;
  
  static int MAX_STR_LEN = 32;
  
  static boolean TESTING = true;
  
  HashMap<String, MoleGame> games = new HashMap<>();
  
  ArrayList<MoleUser> users = new ArrayList<>();
  
  ZugServ serv;
  
  BufferedReader gameStream;
  
  boolean running;
  
  public static void main(String[] args) {
    MOLE_OAUTH_BLACK = args[0];
    MOLE_OAUTH_WHITE = args[1];
    new MoleServ(5555);
  }
  
  public MoleServ(int port) {
    logger.log(Level.ALL, "Hello!");
    this.serv = (ZugServ)new WebSockServ(port, this);
    this.serv.startSrv();
  }
  
  public MoleUser getUser(Connection conn) {
    for (MoleUser user : this.users) {
      if (user.sameConnection(conn))
        return user; 
    } 
    return null;
  }
  
  public MoleUser getUser(String name) {
    for (MoleUser user : this.users) {
      if (user.name.equals(name))
        return user; 
    } 
    return null;
  }
  
  public boolean validString(String str) {
    return (str.length() > 0 && str.length() < MAX_STR_LEN);
  }
  
  public void newGame(MoleUser creator, String title) {
    if (validString(title) && !this.games.containsKey(title)) {
      this.games.put(title, new MoleGame(creator, title));
      spam("games_update", (JsonNode)getAllGames());
    } else {
      creator.tell("err_msg", "Failed to create game");
    } 
  }
  
  public ArrayNode getAllGames() {
    ArrayNode gameObj = mapper.createArrayNode();
    for (Map.Entry<String, MoleGame> entry : this.games.entrySet())
      gameObj.add(((MoleGame)entry.getValue()).toJSON()); 
    return gameObj;
  }
  
  public void handleResult(MoleUser user, MoleResult action) {
    if (action.result) {
      user.tell(action.message);
      spam("games_update", (JsonNode)getAllGames());
    } else {
      user.tell("err_msg", action.message);
    } 
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
        	conn.tell(WebSockServ.MSG_ERR, "Login Error: Bad Oauth Token"); return; 
        }
        JsonNode username = accountData.get("username");
        if (username != null) {
          this.users.add(new MoleUser(conn, dataTxt, username.asText()));
          conn.tell(WebSockServ.MSG_LOG_SUCCESS, "Login Successful: Welcome!");
        } 
      } 
      if (TESTING && typeTxt.equals("login")) {
        this.users.add(new MoleUser(conn, "", dataTxt));
        conn.tell("log_OK", "Login Successful: Welcome!");
        conn.tell("games_update", (JsonNode)getAllGames());
      } else if (user == null) {
        conn.tell("err_msg", "Please log in");
      } else if (typeTxt.equals("newgame")) {
        newGame(user, dataTxt);
      } else if (typeTxt.equals("joingame")) {
        String title = dataNode.get("title").asText();
        int color = dataNode.get("color").asInt();
        MoleGame game = this.games.get(title);
        if (game == null) {
          user.tell("err_msg", "Game does not exist");
        } else {
          handleResult(user, game.addPlayer(user, color));
        } 
      } else if (typeTxt.equals("partgame")) {
        MoleGame game = this.games.get(dataTxt);
        if (game == null) {
          user.tell("err_msg", "Game not joined: " + dataTxt);
        } else {
          handleResult(user, game.dropPlayer(user));
          if (game.isAbandoned()) {
            this.games.remove(dataTxt);
            spam("games_update", (JsonNode)getAllGames());
          } 
        } 
      } else if (typeTxt.equals("startgame")) {
        MoleGame game = this.games.get(dataTxt);
        if (game == null) {
          user.tell("err_msg", "You're not in a game");
        } else {
          handleResult(user, game.startGame(user));
        } 
      } else if (typeTxt.equals("move")) {
        JsonNode title = dataNode.get("board");
        JsonNode move = dataNode.get("move");
        if (title != null && move != null) {
          MoleGame game = this.games.get(title.asText());
          if (game == null) {
            user.tell("err_msg", "Game not found: " + title);
          } else {
            handleResult(user, game.tryMove(user, move.asText()));
          } 
        } else {
          user.tell("err_msg", "WTF: " + dataTxt);
        } 
      } else if (typeTxt.equals("voteoff")) {
        JsonNode title = dataNode.get("board");
        JsonNode suspect = dataNode.get("suspect");
        if (title != null && suspect != null) {
          MoleGame game = this.games.get(title.asText());
          if (game == null) {
            user.tell("err_msg", "Game not found: " + title);
          } else {
            handleResult(user, game.handleVote(user, suspect.asText()));
          } 
        } else {
          user.tell("err_msg", "WTF: " + dataTxt);
        } 
      } else if (typeTxt.equals("chat")) {
        ObjectNode node = mapper.createObjectNode();
        node.put("player", user.name);
        node.put("msg", dataTxt);
        spam("chat", (JsonNode)node);
      } else {
        user.tell("err_msg", "Unknown command");
      } 
    } catch (JsonMappingException e) {
      e.printStackTrace();
      return;
    } catch (JsonProcessingException e) {
      e.printStackTrace();
      return;
    } catch (NullPointerException e) {
      e.printStackTrace();
      return;
    } 
  }
  
  public void connected(Connection conn) {}
  
  public void disconnected(Connection conn) {
    MoleUser user = getUser(conn);
    if (user != null)
      for (Map.Entry<String, MoleGame> entry : this.games.entrySet())
        ((MoleGame)entry.getValue()).dropPlayer(user);  
    spam("games_update", (JsonNode)getAllGames());
  }
  
  public void spam(String type, String msg) {
    ObjectNode node = mapper.createObjectNode();
    node.put("msg", msg);
    spam(type, (JsonNode)node);
  }
  
  public void spam(String type, JsonNode node) {
    for (MoleUser user : this.users)
      user.tell(type, node); 
  }
}

