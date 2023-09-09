package org.chernovia.molechess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.chernovia.lib.lichess.LichessSDK;
import org.chernovia.lib.zugserv.ConnListener;
import org.chernovia.lib.zugserv.Connection;
import org.chernovia.lib.zugserv.ZugServ;
import org.chernovia.lib.zugserv.web.WebSockServ;
import org.chernovia.utils.CommandLineParser;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/* TODO:
~handle AWOL team members
handle draws
clarify observing, empty/pregame board timeouts
Double Mole/Inspector/Takebacker/Captain Role?
ai voting
50 move rule draws

?update player leaving
?innocent accused remain
?molevote bug
?Defecting Mole rating change bug

sustain connections
*/

public class MoleServ extends Thread implements ConnListener, MoleListener {
    static final Logger LOGGER = Logger.getLogger("MoleLog");
    static final String VERSION = getVersion("VERSION");
    static final String MSG_GAME_UPDATE = "game_update";
    static final String MSG_GAMES_UPDATE = "games_update";
    static final ObjectMapper OBJ_MAPPER = new ObjectMapper();
    static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9]*$");
    static final int MAX_STR_LEN = 30;
    static String TEST = "";
    static String LOG_PATH = "%h/molechess/logs/";
    static String STOCK_PATH = "stockfish/stockfish";
    static int STOCK_STRENGTH = 2000, STOCK_MOLE_STRENGTH = 1500;
    private final ArrayList<MoleUser> users = new ArrayList<>();
    private final ConcurrentHashMap<String, MoleGame> games = new ConcurrentHashMap<>();
    private ZugServ serv;
    private int purgeFreq = 30, maxUserGames = 3, defMoveTime = 60;
    private long startTime;
    private boolean running = false;
    private boolean testing = false;
    private final MoleBase moleBase;

    public static void main(String[] args) {
        new MoleServ(5555, args).start();
    }

    public MoleServ(int port, String[] args) {
        CommandLineParser parser = new CommandLineParser(args);
        String[] test = parser.getArgumentValue("test");
        if (test != null) TEST = test[0];
        String[] path = parser.getArgumentValue("stockpath");
        if (path != null) STOCK_PATH = path[0];
        log("Stock Path: " + STOCK_PATH);
        path = parser.getArgumentValue("logpath");
        if (path != null) LOG_PATH = path[0];
        log("Log Path: " + LOG_PATH);
        String[] movetime = parser.getArgumentValue("movetime");
        if (movetime != null) defMoveTime = Integer.parseInt(movetime[0]);
        log("Move Time: " + defMoveTime);
        testing = parser.getFlag("testing");
        log("Testing: " + testing);
        log("Constructing MoleServ on port: " + port);
        serv = new WebSockServ(port, this);
        serv.startSrv();
        startTime = System.currentTimeMillis();
        final String[] dbuser = parser.getArgumentValue("dbuser");
        final String[] dbpass = parser.getArgumentValue("dbpass");
        if (dbuser != null && dbpass != null) {
            moleBase = new MoleBase("localhost:3306",
                    dbuser[0], dbpass[0], "molechess");
        } else {
            log(Level.WARNING, "DB connection info unspecified - DB functionality disabled");
            moleBase = new MoleBase(null, null, null, null);
        }
        createPlayersTableIfNotExists();
        createGameTableIfNotExist();
    }

    private void createPlayersTableIfNotExists() {
        moleBase.makeQuery(
                        "CREATE TABLE IF NOT EXISTS `players` (" +
                                "   `Name` VARCHAR(45) NOT NULL," +
                                "   `Wins` INT(11) NOT NULL," +
                                "   `Losses` INT(11) NOT NULL," +
                                "   `Rating` INT(11) NOT NULL," +
                                "   `DateCreated` DATETIME NOT NULL," +
                                "   `About` VARCHAR(100) NOT NULL," +
                                "   PRIMARY KEY (`Name`)" +
                                ")DEFAULT CHARSET = utf8;")
                .ifPresent(MoleBase.MoleQuery::runUpdate);
    }

    private void createGameTableIfNotExist() {
        moleBase.makeQuery(
                        "CREATE TABLE IF NOT EXISTS `games` (" +
                                "  `Id` BINARY(16) NOT NULL," +
                                "  `Date` DATETIME NOT NULL," +
                                "  `PGN` MEDIUMTEXT NOT NULL," +
                                "  `Winner` INT NOT NULL," +
                                "  PRIMARY KEY (`Id`)" +
                                ")DEFAULT CHARSET = utf8;")
                .ifPresent(MoleBase.MoleQuery::runUpdate);
        moleBase.makeQuery(
                        "CREATE TABLE IF NOT EXISTS `teams` (" +
                                "  `Id` INT NOT NULL AUTO_INCREMENT," +
                                "  `Game` BINARY(16) NOT NULL," +
                                "  `Player` VARCHAR(45) NOT NULL," +
                                "  `Color` INT NOT NULL," +
                                "  `Rating` INT(11) NOT NULL," +
                                "  `Mole` INT NOT NULL," +
                                "  PRIMARY KEY (`Id`)," +
                                "  INDEX `GameFK_idx` (`Game` ASC)," +
                                "  INDEX `PlayerFK_idx` (`Player` ASC)," +
                                "  CONSTRAINT `GameFK`" +
                                "  FOREIGN KEY (`Game`)" +
                                "  REFERENCES `games` (`Id`)" +
                                "  ON DELETE CASCADE" +
                                "  ON UPDATE CASCADE," +
                                "  CONSTRAINT `PlayerFK`" +
                                "  FOREIGN KEY (`Player`)" +
                                "  REFERENCES `players` (`Name`)" +
                                "  ON DELETE CASCADE" +
                                "  ON UPDATE CASCADE" +
                                ")DEFAULT CHARSET = utf8;")
                .ifPresent(MoleBase.MoleQuery::runUpdate);
    }

    public void saveGame(final String pgn, final List<MolePlayer> whiteTeam, final List<MolePlayer> blackTeam, final int winner) {
        final String gameID = UUID.randomUUID().toString().substring(0, 16);
        moleBase.makeQuery("INSERT INTO `games` (`Id`, `Date`, `PGN`, `Winner`) VALUES (?, CURRENT_TIMESTAMP, ?, ?)")
                .ifPresent(query -> query.runUpdate(statement -> {
                    statement.setString(1, gameID);
                    statement.setString(2, pgn);
                    statement.setInt(3, winner);
                }));
        final List<MolePlayer> allPlayers = Stream.concat(whiteTeam.stream(), blackTeam.stream())
                .filter(p -> !p.ai).collect(Collectors.toList());
        allPlayers.stream()
                .map(players -> "(?, ?, ?, ?, ?)")
                .reduce((a, b) -> a + ", " + b)
                .ifPresent(teamValues -> {
                    final String teamQuery = "INSERT INTO `teams` (`Game`, `Player`, `Color`, `Rating`, `Mole`) VALUES " + teamValues;
                    moleBase.makeQuery(teamQuery).ifPresent(query -> query.runUpdate(statement -> {
                        int i = 1;
                        for (MolePlayer player : allPlayers) {
                            statement.setString(i++, gameID);
                            statement.setString(i++, player.user.name);
                            statement.setInt(i++, player.color);
                            statement.setInt(i++, player.user.data.rating);
                            statement.setInt(i++, player.role == MolePlayer.ROLE.MOLE ? 1 : 0);
                        }
                    }));
                });
    }

    private void addUserData(MoleUser user) {
        moleBase.makeQuery(
                        "INSERT IGNORE INTO `players` (`Name`, `Wins`, `Losses`, `Rating`, `DateCreated`, `About`) " +
                                "VALUES (?, '0', '0', '1600', CURRENT_TIMESTAMP, '')")
                .ifPresent(query -> query.runUpdate(statement -> statement.setString(1, user.name)));
    }

    private Optional<MoleUser.MoleData> refreshAndGetUserData(MoleUser user) {
        if (user.getConn() == null) return Optional.empty();
        return moleBase.makeQuery(
                        "SELECT * FROM `players` WHERE Name=?")
                .flatMap(it -> it.mapResultSet(statement -> statement.setString(1, user.name), rs -> {
                            if (rs.next()) {
                                user.setData(
                                        rs.getInt("Wins"), rs.getInt("Losses"),
                                        rs.getInt("Rating"), rs.getString("About"));
                            }
                            return user.getData();
                        })
                );
    }

    public void updateUserData(ArrayList<MolePlayer> winners, ArrayList<MolePlayer> losers, boolean draw) {
        for (MolePlayer p : winners) refreshAndGetUserData(p.user);
        for (MolePlayer p : losers) refreshAndGetUserData(p.user);
        final int ratingDiff = (int) ((calcAvgRating(winners) - calcAvgRating(losers)) * .04);
        final int ratingGain = Math.min(Math.max(draw ? ratingDiff : 16 - ratingDiff, 0), 32);
        final BiConsumer<MolePlayer, Boolean> updateRating =
                (player, isWinner) -> player.user.getData().ifPresent(data -> { if (!player.ai) {
                updateUserRating(player.user, data.rating + (isWinner ? 1 : -1) *
                        (player.role == MolePlayer.ROLE.MOLE ? -ratingGain : ratingGain), isWinner);
            }
        });
        winners.forEach(p -> updateRating.accept(p, true));
        losers.forEach(p -> updateRating.accept(p, false));
    }

    private int calcAvgRating(ArrayList<MolePlayer> team) {
        return team
                .stream()
                .map(player -> player.ai ?
                        (player.role == MolePlayer.ROLE.MOLE ? MoleServ.STOCK_MOLE_STRENGTH : MoleServ.STOCK_STRENGTH) :
                        (player.user.getData().orElse(player.user.getEmptyData())).rating)
                .reduce(0, Integer::sum) / team.size();
    }

    private void updateUserRating(MoleUser user, int newRating, boolean winner) {
        moleBase.makeQuery(
                        "UPDATE `players` SET Rating=? WHERE Name=?")
                .ifPresent(query -> query.runUpdate(statement -> {
                    statement.setInt(1, newRating);
                    statement.setString(2, user.name);
                }));
        user.getData().ifPresent(data -> {
            user.tell("Rating change: " + data.rating + " -> " + newRating);
            moleBase.makeQuery("UPDATE `players` SET " + (winner ? "Wins" : "Losses") + "=? WHERE Name=?")
                    .ifPresent(query -> query.runUpdate(statement -> {
                        statement.setInt(1, (winner ? data.wins : data.losses) + 1);
                        statement.setString(2, user.name);
                    }));
        });
    }

    private Optional<ArrayNode> getTopPlayers(int n) { // TODO: Split into 2 functions
        return moleBase.makeQuery("SELECT * FROM players ORDER BY Rating DESC LIMIT ?").flatMap(query ->
                query.mapResultSet(statement -> statement.setInt(1, n), rs -> {
                    ArrayNode playlist = OBJ_MAPPER.createArrayNode();
                    while (rs.next()) {
                        ObjectNode node = OBJ_MAPPER.createObjectNode();
                        node.put("name", rs.getString("Name"));
                        node.put("rating", rs.getInt("Rating"));
                        playlist.add(node);
                    }
                    return Optional.of(playlist);
                })
        );
    }

    public JsonNode toJSON() {
        ObjectNode node = OBJ_MAPPER.createObjectNode();
        node.put("Uptime: ", (System.currentTimeMillis() - startTime) / 1000);
        ArrayNode usersNode = OBJ_MAPPER.createArrayNode();
        for (MoleUser user : users) {
            if (user.isActiveUser()) usersNode.add(user.toJSON(true));
        }
        node.set("users", usersNode);
        return node;
    }

    private MoleUser getUserByToken(String token) {
        for (MoleUser user : users) if (user.oauth.equals(token)) return user;
        return null;
    }

    private MoleUser getUserByName(String name) {
        for (MoleUser user : users) if (user.name.equalsIgnoreCase(name)) return user;
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
                MoleGame game = entry.getValue();
                if (shallowCopy) {
                    ObjectNode node = OBJ_MAPPER.createObjectNode();
                    node.put("title", game.getTitle());
                    gameObj.add(node);
                } else gameObj.add(game.toJSON(false));
            }
            return gameObj;
        } catch (ConcurrentModificationException fuck) {
            log(Level.SEVERE, fuck.getMessage());
            return null;
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
            if (entry.getValue().getCreator().equals(creator)) count++;
        }
        return count;
    }

    private void newGame(MoleUser creator, String title, int color) {
        if (validString(title)) {
            if (games.containsKey(title)) {
                creator.tell(ZugServ.MSG_ERR, "Failed to create game: title already exists");
            } else if (countGames(creator) > maxUserGames) {
                creator.tell(ZugServ.MSG_ERR,"Failed to create game: too many games (" + maxUserGames + ")");
            } else {
                MoleGame game = new MoleGame(creator, title, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",this);
                game.setMoveTime(defMoveTime);
                games.put(title, game);
                game.addPlayer(creator, color);
                updateGames(false);
            }
        } else {
            creator.tell(ZugServ.MSG_ERR, "Failed to create game: bad title");
        }
    }

    public void handleObs(Connection conn, String name) {
        MoleUser user = getUserByName(name);
        if (user != null) {
            user.addObserver(conn);
            user.tell("Observer added");
        }
    }

    public void newMsg(Connection conn, int channel, String msg) { //log("NewMsg: " + msg);
        try {
            MoleUser user = getUser(conn);
            JsonNode msgNode = OBJ_MAPPER.readTree(msg);
            JsonNode typeNode = msgNode.get("type"), dataNode = msgNode.get("data");
            if (typeNode == null || dataNode == null) {
                conn.tell(ZugServ.MSG_ERR, "Error: Bad Data(null)");
                return;
            }
            String typeTxt = typeNode.asText(), dataTxt = dataNode.asText();

            if (typeTxt.equals("login")) {
                handleLogin(conn, dataTxt, testing);
            } else if (typeTxt.equals("obs")) {
                handleObs(conn,dataTxt);
            } else if (user == null) {
                conn.tell(ZugServ.MSG_ERR, "Please log in");
            } else if (typeTxt.equals("newgame")) {
                JsonNode color = dataNode.get("color");
                JsonNode title = dataNode.get("game");
                if (color != null && title != null) newGame(user,title.asText(),color.asInt());
                else user.tell(ZugServ.MSG_ERR, "Ruhoh: Invalid Data!");
            } else if (typeTxt.equals("top")) {
                getTopPlayers(Integer.parseInt(dataTxt)).ifPresent(it -> user.tell("top", it));
            } else if (typeTxt.equals("chat")) {
                JsonNode sourceNode = dataNode.get("source");
                if (sourceNode != null) { // && dataNode != null
                    String source = sourceNode.asText("?");
                    if (source.equals("serv")) {
                            ObjectNode node = OBJ_MAPPER.createObjectNode();
                            node.put("user", user.name);
                            node.put("source", source);
                            node.put("msg", dataNode.get("msg").asText("?"));
                            spam("chat", node); //TODO: add player serv color
                    }
                    else {
                        MoleGame g = games.get(source);
                        if (g  != null) {
                            MolePlayer p = g.getPlayer(user);
                            if (p != null) {
                                if (p.newMessage(5,10000)) g.spam("chat",dataNode.get("msg").asText("?"),p);
                                else user.tell("Sorry, you're typing too fast!",g);
                            }
                        }
                    }
                } else {
                    user.tell(ZugServ.MSG_ERR, "Bad chat");
                }
            } else if (typeTxt.equals("cmd")) {
                handleCmd(user, dataNode);
            } else { //must be a game command
                MoleGame game = games.get(dataTxt); //TODO: simplify
                if (game == null) {
                    JsonNode gameNode = dataNode.get("game");
                    if (gameNode != null) game = games.get(gameNode.asText());
                }
                if (game != null) {
                    handleGameCmd(user,typeTxt,game,dataNode);
                } else {
                    user.tell(ZugServ.MSG_ERR, "Unknown command: " + typeTxt);
                }
            }
        } catch (JsonMappingException e) {
            log("JSON Mapping goof: " + e.getMessage());
        } catch (JsonProcessingException e) {
            log("JSON Processing error: " + e.getMessage());
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
    }

    private boolean handleGameCmd(MoleUser user, String cmd, MoleGame game, JsonNode data) {
        if (cmd.equalsIgnoreCase("role")) {
            game.tellRole(user);
        }
        else if (cmd.equalsIgnoreCase("status")) {
            user.tell("status",game.getStatus());
        }
        else if (cmd.equalsIgnoreCase("veto")) {
            JsonNode confirm = data.get("confirm");
            game.handleVeto(user, confirm == null || confirm.asBoolean());
        }
        else if (cmd.equalsIgnoreCase("resign")) {
            game.resign(user);
        }
        else if (cmd.equalsIgnoreCase("kickoff")) {
            JsonNode suspect = data.get("player");
            if (suspect != null) game.kickPlayer(user, suspect.asText());
        }
        else if (cmd.equalsIgnoreCase("voteoff")) {
            JsonNode suspect = data.get("player");
            if (suspect != null) game.castMoleVote(user, suspect.asText());
        }
        else if (cmd.equalsIgnoreCase("move")) {
            JsonNode move = data.get("move");
            JsonNode prom = data.get("promotion");
            if (move != null) game.handleMoveVote(user, move.asText() + (prom.isNull() ? "" : prom.asText()));
        }
        else if (cmd.equalsIgnoreCase("startgame")) {
            game.startGame(user);
        }
        else if (cmd.equalsIgnoreCase("partgame")) {
            game.dropPlayer(user);
        }
        else if (cmd.equalsIgnoreCase("joingame")) {
            JsonNode color = data.get("color");
            game.addPlayer(user,color.isNull() ? MoleGame.COLOR_UNKNOWN : color.asInt());
        }
        else if (cmd.equalsIgnoreCase("abort")) {
            game.abortGame(user);
        }
        else if (cmd.equalsIgnoreCase("obsgame")) {
            game.addObserver(user);
        }
        else if (cmd.equalsIgnoreCase("set_opt")) {
            setGameOptions(user,game,data);
        }
        else if (cmd.equalsIgnoreCase("get_opt")) {
            user.tell("options", game.getGameOptions());
        }
        else if (cmd.equalsIgnoreCase("update")) {
            user.tell(MSG_GAME_UPDATE, game.toJSON(true));
        }
        else {
            user.tell("Unknown game command: " + cmd); return false;
        }
        return true;
    }

    private void setGameOptions(MoleUser user, MoleGame game, JsonNode data) {
        if (!game.getCreator().equals(user)) return;

        JsonNode time = data.get("time");
        if (time != null) game.setMoveTime(time.asInt());

        JsonNode maxPlayers = data.get("max_players");
        if (maxPlayers != null) game.setMaxPlayers(maxPlayers.asInt());

        JsonNode moleVeto = data.get("mole_veto");
        if (moleVeto != null) game.setMoleVeto(moleVeto.asBoolean());

        JsonNode molePredictPiece = data.get("mole_predict_piece");
        if (molePredictPiece != null)  game.setMolePiecePrediction(molePredictPiece.asBoolean());

        JsonNode molePredictMove = data.get("mole_predict_move");
        if (molePredictMove != null) game.setMoleMovePrediction(molePredictMove.asBoolean());

        JsonNode teamPredictMove = data.get("team_predict_move");
        if (teamPredictMove != null) game.setTeamMovePrediction(teamPredictMove.asBoolean());

        JsonNode hideMoveVote = data.get("hide_move_vote");
        if (hideMoveVote != null) game.setHideMoveVote(hideMoveVote.asBoolean());
    }

    private void broadcast(MoleGame game, ObjectNode node) {
        if (game != null) game.spamNode("chat", node);
    }

    private void handleCmd(MoleUser user, JsonNode cmdNode) {
        JsonNode cmd = cmdNode.get("cmd");
        if (cmd.isNull()) {
            user.tell(ZugServ.MSG_ERR, "Error: null command");
        } else switch (cmdNode.get("cmd").asText()) {
            case "ver", "version" -> user.tell(ZugServ.MSG_SERV, "Version: " + VERSION);
            case "info" -> user.tell("info", this.toJSON());
            case "players", "who" -> user.tell("users", this.toJSON());
            case "up", "uptime" -> user.tell(ZugServ.MSG_SERV,
                    "Uptime: " + timeString(System.currentTimeMillis() - startTime));
            case "finger" -> refreshAndGetUserData(user).ifPresent(it -> user.tell(ZugServ.MSG_SERV, it.toString()));
            case "down" ->  shutdown(user,5);
            default -> user.tell(ZugServ.MSG_ERR, "Error: command not found");
        }
    }

    private void shutdown(MoleUser user, int seconds) {
        if (user.name.equals("ZugAddict")) {
            spam(ZugServ.MSG_SERV,"Shutting down in " + seconds + " seconds...");
            try {
                Thread.sleep(seconds * 1000);
                serv.stopSrv();
                Thread.sleep(2000); //give the sockets a chance to clear?
                System.exit(-1);
            }
            catch (InterruptedException e) {
                spam(ZugServ.MSG_SERV,"Er, never mind.");
            }
        }
        else user.tell(ZugServ.MSG_ERR,"Nice try.");
    }

    private void spam(String type, String msg) {
        spam(type, msg, null);
    }

    private void spam(String type, JsonNode node) {
        spam(type, node, null);
    }

    private void spam(String type, String msg, MoleUser exclude) {
        ObjectNode node = OBJ_MAPPER.createObjectNode();
        node.put("msg", msg);
        node.put("source","serv");
        spam(type, node, exclude);
    }

    private void spam(String type, JsonNode node, MoleUser exclude) {
        for (MoleUser user : this.users) if (!user.equals(exclude)) user.tell(type, node);
    }

    private void updateGameList(MoleUser user) {
        user.tell(MSG_GAMES_UPDATE, getAllGames(false));
    }

    private MoleUser handleRelogging(Connection conn, LichessAccountData data) {
        if (data.ok) {
            MoleUser user = getUserByName(data.name);
            if (user != null) {
                user.tell("Multiple login detected, closing");
                user.getConn().close();
                user.setConn(conn);  user.oauth = data.oauth;
                conn.setStatus(Connection.Status.STATUS_OK);
                return user;
            }
        }
        return null;
    }

    private void handleLogin(Connection conn, String token, boolean testing) {
        LichessAccountData accountData = new LichessAccountData(token);
        MoleUser relogger = handleRelogging(conn, accountData);
        if (relogger != null) {
            addUser(relogger, "Relog Successful: Welcome back!", false);
        } else if (testing) {
            String name = token;
            if (validString(name)) {
                addUser(new MoleUser(conn, token, name, 1600), "Test Login Successful: Welcome!");
            } else conn.tell(ZugServ.MSG_ERR, "Ruhoh: Invalid Data!");
        } else if (token == null) {
            conn.tell(ZugServ.MSG_ERR, "Login Error: Missing Oauth Token");
        } else {
            if (accountData.ok) {
                addUser(new MoleUser(conn,token,accountData.name,accountData.rating),"Welcome to MoleChess " + VERSION + "!");
            }
            else {
                conn.tell(ZugServ.MSG_ERR, "Login Error: weird Lichess API result");
            }
        }
    }

    static class LichessAccountData {
        String oauth;
        String name = "";
        int rating = 0;
        boolean ok = false;
        public LichessAccountData(String token) {
            oauth = token;
            JsonNode accountData = LichessSDK.apiRequest("account", oauth);
            if (accountData != null) {
                JsonNode username = accountData.get("username");
                if (username != null) {
                    name = username.asText();
                    JsonNode perfs = accountData.get("perfs");
                    if (perfs != null) {
                        JsonNode blitz = perfs.get("blitz");
                        if (blitz != null) {
                            JsonNode blitzRating = blitz.get("rating");
                            if (blitzRating.isInt()) {
                                rating = blitzRating.asInt(); ok = true;
                            }
                        }
                    }
                }
            }
        }
    }

    private void addUser(MoleUser user, String msg) {
        addUser(user, msg, true);
    }

    private void addUser(MoleUser user, String msg, boolean add) {
        if (add) {
            if (getUserByName(user.name) != null) {
                user.tell(ZugServ.MSG_ERR,"You're already logged in (possibly from another browser)");
                user.getConn().close(); return;
            }
            users.add(user);
            addUserData(user);
            spam(ZugServ.MSG_SERV, "Welcome, " + user.name + "!", user);
        }
        ObjectNode node = OBJ_MAPPER.createObjectNode();
        node.put("welcome",msg); node.put("name",user.name);
        user.tell(ZugServ.MSG_LOG_SUCCESS, node);
        updateGameList(user);
        getTopPlayers(10).ifPresent(it -> user.tell("top", it));
        refreshAndGetUserData(user);
    }

    @Override
    public void updateUser(MoleUser user, MoleGame game, MoleResult action, boolean moves) {
        if (user != null) {
            if (action.success) {
                user.tell(action.message,game);
                user.tell(MSG_GAME_UPDATE, game.toJSON(moves));
            } else user.tell(ZugServ.MSG_ERR, action.message,game);
        }
    }

    @Override
    public void updateGame(MoleGame game, MoleResult action, boolean moves) {
        if (action.success) {
            if (action.player != null) game.spam(action.message,action.player);
            else game.spam(action.message);
            game.spamNode(MSG_GAME_UPDATE, game.toJSON(moves)); //updateObs(game,moves);
        } else {
            game.spam(ZugServ.MSG_ERR, action.message);
        }
    }

    @Override
    public void started(MoleGame game) {
        game.spamNode(MSG_GAME_UPDATE, game.toJSON(true));
        updateGames(false);
    }


    @Override
    public void finished(MoleGame game) {
        games.remove(game.getTitle());
        updateGames(false);
    }

    @Override
    public void connected(Connection conn) {
    }

    @Override
    public void disconnected(Connection conn) { //TODO: concurrency argh
        MoleUser user = getUser(conn);
        if (user != null) {
            for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
                MoleGame game = entry.getValue();
                game.dropPlayer(user);
            }
        }
        conn.setStatus(Connection.Status.STATUS_DISCONNECTED);
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
                Thread.sleep(purgeFreq * 1000L);
                for (Map.Entry<String, MoleGame> entry : games.entrySet()) {
                    MoleGame game = entry.getValue();
                    if (game.isDefunct(9999999)) {
                        games.remove(entry.getKey());
                        purged = true;
                    }
                }
                if (purged) updateGames(false);
            } catch (InterruptedException e) {
                running = false;
            }
        }
        serv.stopSrv();
        log("Finished main MoleServ loop");
    }

    public static void log(String msg) {
        log(Level.INFO, msg);
    }

    public static void log(Level level, String msg) {
        LOGGER.log(level, msg + " (" + LocalDateTime.now() + ")");
    }

    public static List<String> loadRandomNames(final String filename) {
        List<String> names = new ArrayList<>();
        try {
            InputStream stream = MoleServ.class.getResourceAsStream("/" + filename);
            Scanner scanner = new Scanner(stream);
            while (scanner.hasNextLine()) names.add(scanner.nextLine());
            scanner.close();
            return names;
        } catch (Exception e) {
            e.printStackTrace();
            names = Arrays.asList("Steinitz", "Lasker", "Capablanca", "Karpov", "Kasparov");
            return names;
        } finally {
            log("Names: " + names.size());
        }
    }

    public static String getVersion(String filename) {
        String version = "?";
        try {
            InputStream stream = MoleServ.class.getResourceAsStream("/" + filename);
            Scanner scanner = new Scanner(stream); if (scanner.hasNextLine()) version = scanner.nextLine();
            scanner.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            log("Version: " + version);
        }
        return version;
    }

    public static String timeString(long millis) {
        int seconds = (int) (millis / 1000);
        if (seconds < 60) {
            return seconds + " seconds";
        } else {
            int minutes = seconds / 60;
            int remainder_seconds = seconds - (minutes * 60);
            if (minutes < 60) {
                return minutes + " minutes and " + remainder_seconds + " seconds";
            } else {
                int hours = minutes / 60;
                int remainder_minutes = minutes - (hours * 60);
                if (hours < 24) {
                    return hours + " hours, " + remainder_minutes + " minutes and " + remainder_seconds + " seconds";
                } else {
                    int days = hours / 24;
                    int remainder_hours = hours - (days * 24);
                    return days + " days, " + remainder_hours + " hours, " +
                            remainder_minutes + " minutes and " + remainder_seconds + " seconds";
                }
            }
        }
    }
}
