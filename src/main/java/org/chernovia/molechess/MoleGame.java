package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MoleGame implements Runnable {

    class MoveVote {
        MolePlayer player;
        Move move;
        boolean selected = false;

        public MoveVote(MolePlayer p, Move m) {
            player = p;
            move = m;
        }

        public String toString() {
            return (player.user.name + ": " + move);
        }

        public JsonNode toJSON() {
            ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
            node.set("player", player != null ? player.toJSON() : null);
            ObjectNode moveNode = MoleServ.OBJ_MAPPER.createObjectNode();
            moveNode.put("from", move.getFrom().value());
            moveNode.put("to", move.getTo().value());
            moveNode.put("san", move.getSan());
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
            fen = fenString;
            color = c;
            alts = new ArrayList<>();
            selected = new ArrayList<>();
            for (MoveVote mv : votes) {
                if (mv.selected) selected.add(mv);
                else alts.add(mv);
            }
        }

        public JsonNode toJSON() {
            ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
            ArrayNode altsArray = MoleServ.OBJ_MAPPER.createArrayNode();
            for (MoveVote alt : alts) altsArray.add(alt.toJSON());
            node.set("alts", altsArray);
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
        int voteCount;

        public MoleTeam() {
            players = new ArrayList<>();
            voteCount = 0;
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
    class BucketList {
        ArrayList<MolePlayer> black_players = new ArrayList<>();
        ArrayList<MolePlayer> white_players = new ArrayList<>();
        BucketList(boolean populate) {
            if (populate) {
                black_players.addAll(teams[COLOR_BLACK].players);
                white_players.addAll(teams[COLOR_WHITE].players);
            }
        }
        BucketList(BucketList list) {
            black_players.addAll(list.black_players);
            white_players.addAll(list.white_players);
        }
        private float getTeamAvg(ArrayList<MolePlayer> players) {
            float sum = 0;
            for (MolePlayer player: players) {
                sum += player.user.blitzRating;

            }
            return sum / players.size();
        }
        float diff() {
            return Math.abs(getTeamAvg(black_players) - getTeamAvg(white_players));
        }
        float compare(BucketList list) {
            return list.diff() - diff();
        }
        public String toString() {
            StringBuilder s = new StringBuilder("List: \n");
            s.append("Black: \n"); for (MolePlayer p : black_players) s.append(p.user.name).append("(").append(p.user.blitzRating).append(") "); s.append("\n");
            s.append("White: \n"); for (MolePlayer p : white_players) s.append(p.user.name).append("(").append(p.user.blitzRating).append(") "); s.append("\n");
            s.append("Average Diff: ").append(diff());
            return s.toString();
        }
    }

    public static List<String> MOLE_NAMES = MoleServ.loadRandomNames("molenames.txt");
    public static final int COLOR_UNKNOWN = -1, COLOR_BLACK = 0, COLOR_WHITE = 1;
    //public enum GAME_RESULT {ONGOING, DRAW, CHECKMATE, STALEMATE, ABANDONED}
    public enum GAME_PHASE {PREGAME, VOTING, POSTGAME}
    List<Color> colorList;
    private int colorPointer = 0;
    private final String READY = "ready", UNBALANCED = "unbalanced", INSUFFICIENT = "insufficient";
    private final ArrayList<MolePlayer> playerBucket = new ArrayList<>();
    private final boolean BUCKETS = true;
    private final MoleTeam[] teams = new MoleTeam[2];
    ArrayList<MoleUser> observers = new ArrayList<>();
    private final MoleListener listener;
    private final MoleUser creator;
    private final String title;
    private boolean playing;
    private long lastActivity;
    private int minPlayers = 3, maxPlayers = 6, kickFlag = 2;
    private int turn;
    private int moveTime = 60, postTime = 300, preTime = 999;
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
    private float currentGUIHue = (float) Math.random();
    private boolean PASTELS = false;
    public static final Pattern VALID_MOVE_PATTERN = Pattern.compile("[a-h][1-8][a-h][1-8][qQrRbBnN]?");
    private List<String> selectedMoves = new ArrayList<>();

    public MoleGame(MoleUser c, String t, String startFEN, MoleListener l) {
        creator = c;
        title = t;
        playing = false;
        listener = l;
        teams[COLOR_BLACK] = new MoleTeam(); teams[COLOR_WHITE] = new MoleTeam();
        moveHistory = new ArrayList<>();
        lastActivity = System.currentTimeMillis();
        turn = COLOR_WHITE;
        board = new Board(); board.loadFromFen(startFEN);
        moveNum = 1;
        Color[] COLORS = {
                new Color(255, 92, 92),
                new Color(128, 36, 222),
                new Color(255, 255, 0),
                new Color(92, 128, 28),
                new Color(255, 55, 200),
                new Color(255, 255, 128),
                new Color(172, 172, 172),
                new Color(255, 255, 255),
                new Color(255, 0, 0),
                new Color(255, 0, 255),
                new Color(255, 200, 0),
                new Color(128, 128, 128)
        };
        colorList = Arrays.asList(COLORS);
        Collections.shuffle(colorList);
    }

    public ArrayList<MoleTeam> getTeams() {
        ArrayList<MoleTeam> teamList = new ArrayList<>();
        teamList.add(teams[COLOR_BLACK]); teamList.add(teams[COLOR_WHITE]);
        return teamList;
    }

    public String getStatus() {
        return switch (phase) {
            case PREGAME -> isReady().message;
            case VOTING -> "voting";
            case POSTGAME -> "postgame";
        };
    }

    public MoleUser getCreator() {
        return creator;
    }

    public String getTitle() {
        return title;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMoveTime(int t) {
        moveTime = t;
    }

    public int getKickFlag() {
        return kickFlag;
    }

    public boolean isDefunct() {
        return isDefunct(preTime * 1000);
    }

    public boolean isDefunct(int timeout) {
        return (!playing && ((System.currentTimeMillis() - timeout) > lastActivity));
    }

    private void update(MoleUser user, MoleResult action) { update(user, action, false); }
    private void update(MoleResult action) { update(null, action, false); }
    private void update(MoleResult action, boolean moves) {
        update(null, action, moves);
    }
    private void update(MoleUser user, MoleResult action, boolean moves) {
        if (listener != null) {
            if (user == null) listener.updateGame(this, action, moves);
            else listener.updateUser(user, this, action, moves);
        }
    }

    public ObjectNode toJSON(boolean history) {
        ObjectNode obj = MoleServ.OBJ_MAPPER.createObjectNode();
        ArrayNode buckArray = MoleServ.OBJ_MAPPER.createArrayNode();
        if (BUCKETS) {
            for (MolePlayer p : playerBucket) buckArray.add(p.toJSON());
            obj.set("bucket",buckArray);
        }
        ArrayNode teamArray = MoleServ.OBJ_MAPPER.createArrayNode();
        for (MoleTeam team : getTeams()) teamArray.add(team.toJSON());
        obj.set("teams", teamArray);
        obj.put("title", title);
        obj.put("creator", creator.name);
        obj.put("currentFEN", board.getFen());
        if (history) obj.set("history", historyToJSON());
        return obj;
    }

    public void addObserver(MoleUser user) {
        if (!observers.contains(user)) {
            observers.add(user); //user.tell(MSG_TYPE_MOVELIST,historyToJSON());
            update(user, new MoleResult("Observing: " + title), true);
        } else update(user, new MoleResult(false, "Error: already observing"));
    }

    public void removeObserver(MoleUser user) {
        observers.remove(user);
        update(user, new MoleResult("No longer observing: " + title));
    }

    public void addPlayer(MoleUser user, int color) {
        MolePlayer player = getPlayer(user);
        if (player != null) {
            if (player.away) {
                player.away = false;
                update(user, new MoleResult("Rejoining game: " + title), true);
                update(new MoleResult(user.name + " rejoins the game"));

            } else update(user, new MoleResult(false, "Error: already joined"));
        } else if (phase != GAME_PHASE.PREGAME) {
            update(user, new MoleResult(false, "Game already begun"));
        } else if ((BUCKETS & playerBucket.size() >= (maxPlayers * 2)) ||
                (!BUCKETS && teams[color].players.size() >= maxPlayers - 1)) {
            update(user, new MoleResult(false, "Too many players"));
        } else {
            if (BUCKETS) {
                MolePlayer newPlayer = new MolePlayer(user, this, COLOR_UNKNOWN, nextGUIColor());
                playerBucket.add(newPlayer);
            }
            else {
                MolePlayer newPlayer = new MolePlayer(user, this, color, nextGUIColor());
                teams[color].players.add(newPlayer);
            }
            update(user, new MoleResult("Joined game: " + title), true);
            update(new MoleResult(user.name + " joins the game"));
            lastActivity = System.currentTimeMillis();
        }
    }

    public void dropPlayer(MoleUser user) {
        boolean observed = false; //kinda kludgy, but hey
        if (observers.contains(user)) {
            removeObserver(user);
            observed = true;
        }
        MolePlayer player = getPlayer(user);
        if (player != null) {
            if (phase == GAME_PHASE.PREGAME) {
                if (BUCKETS) playerBucket.remove(player); else teams[player.color].players.remove(player);
            } else {
                player.away = true;
            }
            update(player.user,new MoleResult("Leaving: " + title));
            update(new MoleResult(user.name + " leaves"));
            if (isDeserted()) {
                switch (phase) {
                    case PREGAME -> listener.finished(this);
                    case VOTING -> endGame(COLOR_UNKNOWN, "deserted");
                    case POSTGAME -> gameThread.interrupt();
                }
            }
        } else if (!observed) update(user, new MoleResult(false, "Player not found"));
    }

    public void kickPlayer(MoleUser kicker, String username) {
        MolePlayer kickerPlayer = getPlayer(kicker);
        if (kickerPlayer == null) {
            update(kicker, new MoleResult(false, "Not in this game: " + kicker.name));
        } else {
            MolePlayer player = getPlayer(username, kickerPlayer.color);
            if (player == null) {
                update(kicker, new MoleResult(false, "Not in this game: " + username));
            } else if (phase != GAME_PHASE.VOTING) {
                update(kicker, new MoleResult(false, "Bad phase: " + phase));
            } else if (player.skipped < kickFlag) {
                update(kicker, new MoleResult(false,
                        username + " must be inactive for " + kickFlag + " turns (currently: " +
                                player.skipped + ")"));
            } else if (player.ai) {
                update(kicker, new MoleResult(false, "Cannot kick robots!"));
            } else {
                update(new MoleResult(kicker.name + " kicks " + username + " (reason: inactivity)"));
                player.votedOff = true;
            }
        }
    }

    public MoleResult isReady() {
        if (BUCKETS) {
            if (playerBucket.size() < minPlayers) {
                return new MoleResult(aiFilling,INSUFFICIENT);
            }
            else {
                if (playerBucket.size() % 2 == 0) return new MoleResult(true, READY);
                else return new MoleResult(true,UNBALANCED);
            }
        }
        else {
            if (teams[COLOR_BLACK].players.size() != teams[COLOR_WHITE].players.size()) {
                return new MoleResult(aiFilling,UNBALANCED);
            } else if (teams[COLOR_BLACK].players.size() < minPlayers) {
                return new MoleResult(aiFilling, INSUFFICIENT);
            } else {
                return new MoleResult(true, READY);
            }
        }
    }

    public void startGame(MoleUser user) {
        if (phase != GAME_PHASE.PREGAME) {
            update(user, new MoleResult(false, "Game already begun"));
        } else if (!creator.equals(user)) {
            update(user, new MoleResult(false, "Error: permission denied"));
        } else {
            MoleResult ready = isReady();
            if (ready.success) {
                if (BUCKETS) {
                    if (playerBucket.size() > 1) {
                        BucketList bucketList = bucketSort(); log("Sorted: " + bucketList.toString()); //System.exit(-1);
                        for (MolePlayer p : bucketList.black_players) {
                            teams[COLOR_BLACK].players.add(p); p.color = COLOR_BLACK;
                        }
                        for (MolePlayer p : bucketList.white_players) {
                            teams[COLOR_WHITE].players.add(p); p.color = COLOR_WHITE;
                        }
                    }
                    else {
                        int c = Math.random() < .5 ? COLOR_BLACK : COLOR_WHITE;
                        MolePlayer p = playerBucket.get(0); teams[c].players.add(p); p.color = c;
                    }
                    playerBucket.clear();
                }
                if (aiFilling) {
                    aiFill(COLOR_BLACK);
                    aiFill(COLOR_WHITE);
                }
                for (MolePlayer p : teams[COLOR_WHITE].players) {
                    p.user.tell("color", "white");
                }
                for (MolePlayer p : teams[COLOR_BLACK].players) {
                    p.user.tell("color", "black");
                }
                gameThread = new Thread(this);
                gameThread.start();
            }
            else {
                update(user, new MoleResult(false, "Error: " + ready.message + " players"));
            }
        }
    }

    private BucketList bucketSort() {
        BucketList bestBuck = null;
        MolePlayer[] people = new MolePlayer[playerBucket.size()];
        for (int i=0;i<playerBucket.size();i++) people[i] = playerBucket.get(i);
        int l2 = people.length/2;
        int[] indices = new int[l2 + 1];
        for (int i = 0; i < l2; i++) indices[i] = i;
        indices[l2] = people.length;

        while (people.length % 2 == 0 ? indices[0] == 0 : indices[0] < people.length - (indices.length - 2)) {
            for (int i = indices[indices.length - 2]; i < people.length; i++) {
                BucketList list = new BucketList(false);

                for (int j = 0; j < indices.length - 1; j++) {  //System.out.print(people[indices[j]].user.name);
                    list.black_players.add(people[indices[j]]);
                }

                for (int j = 0; j < indices[0]; j++) { //System.out.print(people[j].user.name);
                    list.white_players.add(people[j]);
                }

                for (int j = 1; j < indices.length; j++) {
                    for (int k = indices[j - 1] + 1; k < indices[j]; k++) { //System.out.print(people[k].user.name);
                        list.white_players.add(people[k]);
                    }
                }

                indices[indices.length - 2]++; //System.out.println(list.toString());

                if (bestBuck == null || list.compare(bestBuck) > 0) {
                    bestBuck = new BucketList(list); //System.out.println(list.toString());
                }
            }

            for (int i = indices.length - 2; i > 0; i--) {
                if (indices[i] == people.length - (indices.length - 2 - i)) {
                    indices[i - 1]++;
                    for (int j = i; j < indices.length - 1; j++) {
                        indices[j] = indices[j - 1] + 1;
                    }
                }
            }
        }
        return bestBuck;
    }

    public void handleMoveVote(MoleUser user, String moveStr) {
        MolePlayer player = getPlayer(user);
        if (player == null) {
            update(user, new MoleResult(false, "Player not found: " + user.name));
        } else handleMoveVote(player, moveStr);
    }

    public void handleMoveVote(final MolePlayer player, final String moveStr) { //log("Handling new move: " + moveStr);
        getMove(moveStr).ifPresentOrElse(move -> {
                    if (phase != GAME_PHASE.VOTING) {
                        update(player.user, new MoleResult(false, "Bad phase: " + phase));
                    } else if (player.color != turn) {
                        update(player.user, new MoleResult(false, "Current turn: " + colorString(turn)));
                    } else if (player.votedOff) {
                        update(player.user, new MoleResult(false, "Sorry, you've been voted off"));
                    } else if (addMoveVote(player, move)) {
                        updateMoveVotes();
                    } else {
                        update(player.user, new MoleResult(false, "Bad Move: " + moveStr));
                    }
                }, () -> update(player.user, new MoleResult(false, "Bad Move: " + moveStr))
        );
    }

    private void updateMoveVotes() {
        String movePfx = moveNum + (turn == COLOR_BLACK ? "..." : ". ");
        ArrayNode listNode = MoleServ.OBJ_MAPPER.createArrayNode();
        for (MolePlayer p : teams[turn].players) {
            ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
            node.put("player_name",p.user.name);
            node.put("player_color",p.guiColor.getRGB());
            node.put("player_move",movePfx + (p.move != null ? p.move.getSan() : "?"));
            listNode.add(node);
        }
        ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
        node.put("move",moveNum);
        node.put("list",listNode);
        spamNode("votelist",node);
    }

    //TODO: fix weird name voting bug
    public void castMoleVote(MoleUser user, String suspectName) {
        MolePlayer player = getPlayer(user);
        if (player == null) {
            update(user, new MoleResult(false, "Player not found: " + user.name));
        } else if (!playing) {
            update(user, new MoleResult(false, "Game not currently running"));
        } else if (teams[player.color].voteCount >= voteLimit) {
            update(user, new MoleResult(false, "No more voting!"));
        } else if (player.votedOff) {
            update(user, new MoleResult(false, "Sorry, you've been voted off"));
        } else {
            MolePlayer p = getPlayer(suspectName, player.color);
            if (phase != GAME_PHASE.VOTING) {
                update(user, new MoleResult(false, "Cannot vote during: " + phase));
            } else if (p != null) {
                handleMoleVote(player, p);
            } else {
                update(user, new MoleResult(false, "Suspect not found"));
            }
        }
    }

    public void resign(MoleUser user) {
        MolePlayer player = getPlayer(user);
        if (player == null) {
            update(user, new MoleResult(false, "Player not found: " + user.name));
        } else if (phase != GAME_PHASE.VOTING) {
            update(user, new MoleResult(false, "Bad phase: " + phase));
        } else if (player.color != turn) {
            update(user, new MoleResult(false, "Wrong turn: " + colorString(turn)));
        } else {
            player.resigning = true;
            update(new MoleResult(player.user.name + " resigns"));
            if (resigning(player.color)) endGame(getNextTurn(), "resignation");
        }
    }

    public void run() {
        playing = true;
        setMole(COLOR_BLACK);
        setMole(COLOR_WHITE);
        listener.started(this);  //starting position
        newPhase(GAME_PHASE.VOTING);
        while (playing) {
            spam("Turn #" + moveNum + ": " + colorString(turn));
            updateMoveVotes();
            autoPlay(turn); //boolean timeout =
            newPhase(GAME_PHASE.VOTING, moveTime);
            if (playing) {
                Move move;
                ArrayList<Move> moveList = getMoveVotes(turn);
                if (moveList.size() == 0) {
                    spam("No legal moves selected, picking randomly...");
                    move = pickMove(board.legalMoves()); move.setSan(getSan(move,turn));
                } else {
                    //spam("Picking randomly from the following moves:"); // \n" + listMoves(turn));
                    //for (MolePlayer p : teams[turn].players) {
                    //    spam(p.user.name + " -> " + ((p.move == null) ? "-" : p.move.getSan()),p);
                    //}
                    move = pickMove(moveList);
                }
                if (makeMove(move).success) {
                    moveHistory.add(getMoveVotes(turn, board.getFen(), move));
                    update(new MoleResult("Selected Move: " + move.getSan()), true);
                    selectedMoves.add(move.getSan());
                    if (playing) {
                        clearMoveVotes(turn);
                        turn = getNextTurn();
                        moveNum++;
                    }
                } else { spam("WTF: " + move); return; } ////shouldn't occur
            }
        }
        if (!isDeserted()) newPhase(GAME_PHASE.POSTGAME, postTime);
        listener.finished(this);
    }

    private void autoPlay(int turn) {
        for (MolePlayer player : teams[turn].players) {
            if (player.ai) player.analyzePosition(board.getFen(), (int) (moveTime * calcFactor) * 1000);
        }
    }

    private void handleMoleVote(MolePlayer player, MolePlayer p) {
        player.vote = p;
        update(new MoleResult(player.user.name + " votes off: " + p.user.name,player));
        MolePlayer suspect = checkVote(player.color);
        if (suspect != null) {
            spam(suspect.user.name + " is voted off!",suspect);
            if (suspect.role == MolePlayer.ROLE.MOLE) {
                spam(suspect.user.name + " was " + "the Mole!",suspect); //award(player.color, moleBonus);
                if (defection) {
                    int newColor = getNextTurn(suspect.color);
                    teams[suspect.color].players.remove(suspect);
                    suspect.color = newColor;
                    suspect.role = MolePlayer.ROLE.PLAYER;
                    teams[suspect.color].players.add(suspect);
                    update(new MoleResult(suspect.user.name + " defects to " + colorString(newColor) + "!"));
                } else suspect.votedOff = true;
            } else {
                MolePlayer mole = getMole(player.color);
                if (mole != null) spam(mole.user.name + " was " + "the Mole!",mole); //award(mole, moleBonus);
                else spam("WTF: no mole!");
            }
            teams[player.color].voteCount++;
            if (endOnAccusation) {
                endGame(COLOR_UNKNOWN, "Mole vote");
            } else if (endOnMutualAccusation &&
                    teams[COLOR_BLACK].voteCount > 0 &&
                    teams[COLOR_WHITE].voteCount > 0) endGame(COLOR_UNKNOWN, "mutual mole vote");
        }
    }

    private JsonNode historyToJSON() {
        ArrayNode historyNode = MoleServ.OBJ_MAPPER.createArrayNode();
        for (MoveVotes votes : moveHistory) historyNode.add(votes.toJSON());
        return historyNode;
    }

    public MolePlayer getPlayer(MoleUser user) {
        for (MolePlayer player : getAllPlayers()) {
            if (player.user.equals(user)) return player;
        }
        return null;
    }

    private MolePlayer getPlayer(String name, int color) {
        for (MolePlayer player : getAllPlayers()) {
            if (player.user.name.equalsIgnoreCase(name) &&
                    (color == COLOR_UNKNOWN || player.color == color)) return player;
        }
        return null;
    }

    private boolean isDeserted() {
        if (phase == GAME_PHASE.PREGAME) {
            for (MolePlayer player : playerBucket) {
                if (creator.equals(player.user)) return false;
            }
            return true;
        }
        for (MolePlayer player : getAllPlayers()) {
            if (!player.away && !player.ai) return false;
        }
        return true;
    }

    private boolean newPhase(GAME_PHASE p) { return newPhase(p,0); }
    private boolean newPhase(GAME_PHASE p, int seconds) {
        phase = p;
        spam("phase", phase.toString());
        boolean timeout = true;
        if (seconds > 0) {
            ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
            node.put("seconds", seconds); //node.put("max_seconds",moveTime);
            node.put("turn", turn);
            node.put("title", title);
            spamNode("countdown", node);
            try {
                Thread.sleep((seconds * 1000L));
            } catch (InterruptedException e) {
                timeout = false;
            }
        }
        if (playing) endgameCheck();
        return timeout;
    }

    private boolean endgameCheck() {
        if (playing) {
            if (activePlayers(turn, true) == 0) endGame(getNextTurn(), "forfeit");
            else if (board.isStaleMate()) endGame(COLOR_UNKNOWN, "stalemate");
            else if (board.isMated()) endGame(turn, "checkmate");
            else if (board.isInsufficientMaterial()) endGame(COLOR_UNKNOWN, "insufficient material");
        }
        return !playing;
    }

    //handle aborts
    public void endGame(int winner, String reason) {
        listener.saveGame(createPGN(), teams[COLOR_WHITE].players, teams[COLOR_BLACK].players, winner);
        if (winner != COLOR_UNKNOWN) {
            spam(colorString(winner) + " wins by " + reason + "!"); //award(winner,winBonus);
            listener.updateUserData(teams[winner].players, teams[getNextTurn(winner)].players, false);

        } else {
            spam("Game Over! (" + reason + ")");
            listener.updateUserData(teams[COLOR_WHITE].players, teams[COLOR_BLACK].players, true);
        }
        playing = false;
        if (gameThread != null && gameThread.getState() == Thread.State.TIMED_WAITING) gameThread.interrupt();
    }

    private String createPGN() {
        if (selectedMoves.size() % 2 == 1) selectedMoves.add("");
        return IntStream.iterate(0, i -> i + 1).limit(selectedMoves.size() / 2)
                .mapToObj(i -> (i + 1) + ". " + selectedMoves.get(i * 2) + " " + selectedMoves.get(i * 2 + 1))
                .reduce((a, b) -> a + " " + b).orElse("");
    }

    ////new MolePlayer(MoleServ.DUMMIES[i++][color], this, color, nextGUIColor());
    private void aiFill(int color) {
        while (teams[color].players.size() < minPlayers) {
            int n = (int) Math.floor(Math.random() * MOLE_NAMES.size());
            MolePlayer player = new MolePlayer(
                    new MoleUser(null, null, MOLE_NAMES.get(n),1600), this, color, nextGUIColor());
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
            int p = (int) Math.floor(Math.random() * teams[color].players.size());
            player = teams[color].players.get(p);
        } while (onlyHuman(player)); //humans cannot be a mole with all-AI teammates

        player.role = MolePlayer.ROLE.MOLE; player.user.tell("You're the mole!",this);

        for (MolePlayer p : teams[color].players) {
            p.user.tell("mole",p.role == MolePlayer.ROLE.MOLE ? "true" : "false", this);
        }
    }

    private int activePlayers(int color, boolean ignoreAI) {
        int active = 0;
        for (MolePlayer player : teams[color].players) { //log(player.user.name + " is: ");
            if (ignoreAI) {
                if (player.isActive()) {
                    active++; //log("active");
                }
            } else if (player.isInteractive()) {
                active++; //log("active");
            }
        }
        return active;
    }

    private String colorString(int color) {
        return (color == COLOR_BLACK) ? "Black" : "White";
    }

    private int getNextTurn() {
        return getNextTurn(turn);
    }

    private int getNextTurn(int color) {
        if (color == COLOR_WHITE) return COLOR_BLACK;
        else return COLOR_WHITE;
    }

    private String listMoves(int color) {
        return teams[color].players
                .stream()
                .filter(player -> player.move != null)
                .map(player -> player.user.name + ": " + player.move.getSan() + "\n")
                .reduce("", (acc, p) -> acc + p);
    }

    private Move pickMove(List<Move> moves) {
        int n = (int) (Math.random() * moves.size());
        return moves.get(n);
    }

    private String getFrom(final Piece piece, final Square from, final Square to) {
        if (piece.getPieceType() == PieceType.PAWN || piece.getPieceType() == PieceType.NONE) {
            return "";
        }
        final List<Move> conflictingMoves = board.getPieceLocation(piece)
                .stream()
                .map(square -> new Move(square.value() + to.value(), board.getSideToMove()))
                .filter(move -> board.legalMoves().contains(move) && move.getFrom() != from)
                .collect(Collectors.toList());
        if (conflictingMoves.size() < 1) {
            return "";
        }
        final boolean conflictOnFile = conflictingMoves.stream().map(move -> move.getFrom().getFile()).anyMatch(file -> file == from.getFile());
        final boolean conflictOnRank = conflictingMoves.stream().map(move -> move.getFrom().getRank()).anyMatch(rank -> rank == from.getRank());
        if (conflictOnFile && conflictOnRank) {
            return from.value();
        } else if (conflictOnFile) {
            return from.value().substring(1, 2);
        } else {
            return from.value().substring(0, 1);
        }
    }

    private String getSan(final Move move, final int color) {
        if (board.legalMoves().contains(move)) {
            final Board auxBoard = board.clone();
            auxBoard.doMove(move);
            Piece piece = board.getPiece(move.getFrom());
            final String ending = auxBoard.isMated() ? "#" : auxBoard.isKingAttacked() ? "+" : "";
            if (piece.equals(Piece.BLACK_KING) && move.getFrom().equals(Square.E8)) {
                if (move.getTo().equals(Square.G8)) {
                    return "0-0" + ending;
                } else if (move.getTo().equals(Square.C8)) {
                    return "0-0-0" + ending;
                }
            } else if (piece.equals(Piece.WHITE_KING) && move.getFrom().equals(Square.E1)) {
                if (move.getTo().equals(Square.G1)) {
                    return "0-0" + ending;
                } else if (move.getTo().equals(Square.C1)) {
                    return "0-0-0" + ending;
                }
            }
            final String takes = (board.getPiece(move.getTo()).getPieceSide() == null || board.getPiece(move.getTo()).getPieceSide().ordinal() != color) ? "" : "x";
            final String promotion = move.getPromotion() != Piece.NONE ? "=" + move.getPromotion().getSanSymbol() : "";
            final String from = getFrom(piece, move.getFrom(), move.getTo()).toLowerCase();
            final String to = move.getTo().value().toLowerCase();
            final String sanSymbol = (piece.getSanSymbol().equals("") && takes.equals("x") && from.equals("")) ?
                    move.getFrom().value().substring(0, 1).toLowerCase() : piece.getSanSymbol();
            return sanSymbol + from + takes + to + promotion + ending;
        }
        return "";
    }

    private boolean addMoveVote(final MolePlayer player, final Move move) { //log("Adding: " + move + " -> " + move.getPromotion());
        final String san = getSan(move, player.color); if (san.equals("")) return false;
        move.setSan(san);
        player.move = move;
        if (countMoveVotes(player.color) >= activePlayers(turn, true)) gameThread.interrupt();
        return true;
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
        ArrayList<Move> moveList = new ArrayList<>();
        for (MolePlayer player : teams[color].players) if (player.move != null) moveList.add(player.move);
        return moveList;
    }

    private MoveVotes getMoveVotes(int color, String fen, Move selectedMove) {
        ArrayList<MoveVote> voteList = new ArrayList<>();
        boolean selected = false;
        for (MolePlayer player : teams[color].players) {
            if (player.move != null) {
                MoveVote mv = new MoveVote(player, player.move);
                if (player.move == selectedMove) {
                    selected = true;
                    mv.selected = true;
                }
                voteList.add(mv);
                player.skipped = 0;
            } else player.skipped++;
        }
        if (!selected) {
            MoveVote mv = new MoveVote(null, selectedMove);
            mv.selected = true;
            voteList.add(mv);
        }
        return new MoveVotes(voteList, fen, color);
    }

    private Optional<Move> getMove(final String moveStr) { //log("Attempted Move: " + moveStr);
        final Matcher moveStrMatcher = VALID_MOVE_PATTERN.matcher(moveStr);
        if (moveStrMatcher.find()) {
            return Optional.of(new Move(moveStrMatcher.group(), turn == COLOR_BLACK ? Side.BLACK : Side.WHITE));
        }
        return Optional.empty();
    }

    private MoleResult makeMove(Move move) {
        if (board.doMove(move)) { //spamMove(move);
            endgameCheck();
            return new MoleResult("Move: " + move);
        } else return new MoleResult(false, "Invalid Move: " + move); //shouldn't occur
    }

    private void spamMoves(Move move) {
        ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
        node.put("lm", move == null ? "" : move.toString());
        node.put("fen", board.getFen());
        spamNode(MoleServ.MSG_GAME_UPDATE, node);
    }

    private MolePlayer checkVote(int color) { //log("Checking vote...");
        HashMap<MolePlayer, Integer> voteMap = new HashMap<>();
        for (MolePlayer p : teams[color].players) {
            if (p.vote != null) {
                if (voteMap.containsKey(p.vote)) voteMap.put(p.vote, voteMap.get(p.vote) + 1);
                else voteMap.put(p.vote, 1);
            }
        }
        int quorum = activePlayers(color, true) - 1; //log("Quorum: " + quorum);
        for (MolePlayer p : teams[color].players) {
            if (voteMap.containsKey(p)) {
                Integer votes = voteMap.get(p);    //log("Votes for " + p.user.name + ": " + votes.intValue());
                if (votes >= quorum) return p;
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
            spam(player.user.name + " gets " + bonus + " points",player);
        }
    }

    private ArrayList<MolePlayer> getAllPlayers() {
        if (BUCKETS && phase == GAME_PHASE.PREGAME) return playerBucket;
        ArrayList<MolePlayer> list = new ArrayList<>();
        list.addAll(teams[COLOR_BLACK].players);
        list.addAll(teams[COLOR_WHITE].players);
        return list;
    }

    public void spam(String msg) { spam("game_msg", msg, null); }
    public void spam(String type, String msg) { spam(type,msg,null); }
    public void spam(String msg, MolePlayer p) { spam("game_msg", msg, p); }
    public void spam(String type, String msg, MolePlayer p) {
        ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
        node.put("msg", msg);
        node.set("player",(p == null) ? null : p.toJSON());
        spamNode(type, node);
    }

    public void spamNode(String type, ObjectNode node) {
        node.put("source", title);
        try {
            for (MolePlayer player : getAllPlayers()) if (!player.away) player.user.tell(type,node);
        } catch (ConcurrentModificationException oops) { //dunno how exactly this happens...
            log(oops.getMessage());
        }
        for (MoleUser user : observers) user.tell(type, node);
    }

    private Color nextGUIColor() {
        if (PASTELS) {
            currentGUIHue += .3;
            if (currentGUIHue > 1) currentGUIHue--; //log("Current Hue: " + currentGUIHue);
            return Color.getHSBColor(currentGUIHue,
                    (2.5f + ((float) Math.random() * 7.5f)) / 10, (5 + ((float) Math.random() * 5)) / 10);
        }
        else {
            if (colorPointer < colorList.size()) return colorList.get(colorPointer++);
            else {
                colorPointer = 0; return colorList.get(colorPointer);
            }
        }
    }

    private static void log(String msg) {
        MoleServ.log(msg);
    }

}
