package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;

import java.awt.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.logging.*;

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

        public ObjectNode toJSON() {
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
        MoveVote selected;
        ArrayList<MoveVote> alts;
        String fen;
        int color;

        public MoveVotes(ArrayList<MoveVote> votes, String fenString, int c) {
            fen = fenString;
            color = c;
            alts = new ArrayList<>();
            for (MoveVote mv : votes) {
                if (mv.selected) selected = mv;
                else alts.add(mv);
            }
        }

        public ObjectNode toJSON() {
            ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
            ArrayNode altsArray = MoleServ.OBJ_MAPPER.createArrayNode();
            for (MoveVote alt : alts) altsArray.add(alt.toJSON());
            node.set("alts", altsArray);
            node.set("selected", selected.toJSON());
            node.put("fen", fen);
            node.put("turn", color);
            return node;
        }
    }

    class MoleTeam {
        ArrayList<MolePlayer> players;
        ArrayList<MolePlayer> startPlayers;
        int voteCount;
        int color;
        int bombFlag = 99;
        int inspectFlag = -12;

        public MoleTeam() {
            players = new ArrayList<>(); startPlayers = new ArrayList<>();
            voteCount = 0;
        }

        public ObjectNode toJSON() {
            ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
            ArrayNode playerArray = MoleServ.OBJ_MAPPER.createArrayNode();
            for (MolePlayer player : players) playerArray.add(player.toJSON());
            node.set("players", playerArray);
            node.put("vote_count", voteCount);
            node.put("color", color);
            return node;
        }

        @Override
        public String toString() { return toString(false); }
        public String toString(boolean original) {
            StringBuffer s = new StringBuffer();
            for (MolePlayer player : original ? startPlayers : players) {
                s.append(player.user.name);
                if (player.role == MolePlayer.ROLE.MOLE) s.append("(*) "); else s.append(" ");
            }
            return s.toString().trim();
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

    private Logger gameLog;
    private FileHandler logfileHandler;
    public static List<String> MOLE_NAMES = MoleServ.loadRandomNames("molenames.txt");
    public static final Pattern VALID_MOVE_PATTERN = Pattern.compile("[a-h][1-8][a-h][1-8][qQrRbBnN]?");
    public static final int COLOR_UNKNOWN = -1, COLOR_BLACK = 0, COLOR_WHITE = 1;
    //public enum GAME_RESULT {ONGOING, DRAW, CHECKMATE, STALEMATE, ABANDONED}
    public enum GAME_PHASE {PREGAME, VOTING, VETO, POSTGAME}
    List<Color> colorList;
    private float currentGUIHue = (float) Math.random();
    private int colorPointer = 0;
    private final String READY = "ready", UNBALANCED = "unbalanced", INSUFFICIENT = "insufficient";
    private final ArrayList<MolePlayer> playerBucket = new ArrayList<>();
    private final boolean BUCKETS = true;
    private final MoleTeam[] teams = new MoleTeam[2];
    ArrayList<MoleUser> observers = new ArrayList<>();
    private final MoleListener listener;
    private final MoleUser creator;
    private final String title;
    private boolean playing, closing;
    private long lastActivity;
    private int minPlayers = 3, maxPlayers = 6, kickFlag = 2, abortMoveLimit = 4;
    private int turn;
    private int moveTime = 60, vetoTime = 8, postTime = 300, preTime = 999, newTime = 0;
    private long phaseStamp = 0;
    private double calcFactor = .25;
    private Board board;
    private Thread gameThread;
    private int ply;
    private ArrayList<MoveVotes> moveHistory; //private List<String> selectedMoves = new ArrayList<>();
    private GAME_PHASE phase = GAME_PHASE.PREGAME;
    private int voteLimit = 1;
    private int moleBonus = 100, winBonus = 200;
    private int inspectPly = 12;
    private int bombPly = 100;
    private boolean aiFilling = true;
    private boolean endOnMutualAccusation = false;
    private boolean endOnAccusation = false;
    private boolean defection = true;
    private boolean moleVeto = false;
    private boolean veto = false;
    private boolean molePiecePrediction = false;
    private boolean moleMovePrediction = false;
    private boolean teamMovePrediction = false;
    private boolean hideMoveVote = false;
    private boolean moleBomb = true;
    private boolean inspecting = true;
    private boolean casual = true;
    private boolean PASTELS = false;
    private final String CR = System.getProperty("line.separator"); //System.lineSeparator();
    private StringBuffer pgnBuff = new StringBuffer();

    public MoleGame(MoleUser c, String t, String startFEN, MoleListener l) {
        gameLog = Logger.getLogger(t);
        try {
            gameLog.setUseParentHandlers(false);
            logfileHandler = new FileHandler(MoleServ.LOG_PATH + t + System.currentTimeMillis() + ".log");
            gameLog.addHandler(logfileHandler);
            gameLog.log(Level.INFO,"Game Created");
        } catch (IOException e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
        creator = c;
        title = t;
        playing = false; closing = false;
        listener = l;
        teams[COLOR_BLACK] = new MoleTeam(); teams[COLOR_BLACK].color = COLOR_BLACK;
        teams[COLOR_WHITE] = new MoleTeam(); teams[COLOR_WHITE].color = COLOR_WHITE;
        moveHistory = new ArrayList<>();
        lastActivity = System.currentTimeMillis();
        turn = COLOR_WHITE;
        board = new Board(); board.loadFromFen(startFEN);
        ply = 1;
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
        teamList.add(teams[COLOR_BLACK]); teamList.add(teams[COLOR_WHITE]); //TODO: loop through array?
        return teamList;
    }

    public GAME_PHASE getPhase() {
        return phase;
    }

    public String getStatus() {
        return switch (phase) {
            case PREGAME -> isReady().message;
            case VOTING -> "voting";
            case VETO -> "veto";
            case POSTGAME -> "postgame";
        };
    }

    public MoleUser getCreator() {
        return creator;
    }

    public String getTitle() {
        return title;
    }

    public void setMoveTime(int t) {
        newTime = t;
        spam("Move Time: " + t);
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int n) {
        if (n > minPlayers && n < 99) {
            maxPlayers = n;
            spam("Max Players: " + n);
        }
    }

    public void setMoleVeto(boolean bool) {
        if (moleVeto != bool) {
            moleVeto = bool; spam("Mole Veto: " + moleVeto);
        }
    }

    public void setMolePiecePrediction(boolean bool) {
        if (molePiecePrediction != bool) {
            molePiecePrediction = bool; spam("Mole Piece Prediction: " + molePiecePrediction);
        }
    }

    public void setMoleMovePrediction(boolean bool) {
        if (moleMovePrediction != bool) {
            moleMovePrediction = bool; spam("Mole Move Prediction: " + moleMovePrediction);
        }
    }

    public void setTeamMovePrediction(boolean bool) {
        if (teamMovePrediction != bool) {
            teamMovePrediction = bool; spam("Team Move Prediction: " + teamMovePrediction);
        }
    }

    public void setHideMoveVote(boolean bool) {
        if (hideMoveVote != bool) {
            hideMoveVote = bool; spam("Hide Move Votes: " + hideMoveVote);
        }
    }

    public void setInspecting(boolean bool) {
        if (inspecting != bool) {
            inspecting = bool; spam("Inspector Role: " + inspecting);
        }
    }

    public void setMoleBomb(boolean bool) {
        if (moleBomb != bool && phase == GAME_PHASE.PREGAME) {
            moleBomb = bool; spam("Mole Bomb: " + moleBomb);
        }
    }

    public void setCasual(boolean bool) {
        if (casual != bool && phase == GAME_PHASE.PREGAME) {
            casual = bool; spam("Casual: " + casual);
        }
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
        long elapsed = (System.currentTimeMillis() - phaseStamp) / 1000; //TODO: weird negative time bug
        if (phase == GAME_PHASE.VOTING) obj.put("timeRemaining",moveTime - elapsed);
        else if (phase == GAME_PHASE.VETO) obj.put("timeRemaining",vetoTime - elapsed);
        obj.put("turn", turn);
        obj.put("phase",phase.toString());
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
        if (color < 0 || color >= teams.length) {
            update(user, new MoleResult(false, "Bad color")); return;
        }
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
            update(new MoleResult(user.name + " joins the game"));
            user.tell("join",toJSON(false)); //update(user, new MoleResult("Joined game: " + title), true);
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
                if (phase == GAME_PHASE.POSTGAME && user.equals(creator)) interruptPhase();
            }
            update(new MoleResult(user.name + " leaves"));
            user.tell("part",toJSON(false)); //update(player.user,new MoleResult("Leaving: " + title));
            if (isDeserted()) {
                switch (phase) {
                    case PREGAME -> listener.finished(this);
                    case VOTING -> endGame(COLOR_UNKNOWN, "deserted");
                    case POSTGAME -> interruptPhase();
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
                player.away = true;
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

    public void abortGame(MoleUser user) {
        if (!creator.equals(user)) {
            update(new MoleResult(false,"You're not the creator of this game",user));
        }
        else if (phase == GAME_PHASE.VOTING && ply >= abortMoveLimit) { //VETO?
            update(new MoleResult(false,"You can only abort running games before move " + abortMoveLimit,user));
        }
        else {
            spam("Game aborted by creator: " + user.name);
            closeGame();
        }
    }

    public void closeGame() {
        closing = true; interruptPhase();
        listener.finished(this);
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
                        MolePlayer mole = getMole(turn);
                        if (player.role == MolePlayer.ROLE.MOLE) {
                            if (mole == null) {
                                update(player.user, new MoleResult(false, "Mole not found!"));
                            }
                            else if (!mole.isRampaging()) {
                                update(player.user, new MoleResult(false, "Mole not rampaging!"));
                            }
                            else if (addMovePrediction(player,move)) {
                                spam(colorString(player.color) + "'s Mole has made a prediction!");
                                update(player.user, new MoleResult(true, "You predicted: " + player.prediction.getSan()));
                            }
                        }
                        else update(player.user, new MoleResult(false, "Current turn: " + colorString(turn)));
                    } else if (player.votedOff) {
                        update(player.user, new MoleResult(false, "Sorry, you've been voted off"));
                    } else if (player.inspecting) {
                        update(player.user, new MoleResult(false, "Sorry, you've inspected this turn"));
                    } else if (addMoveVote(player, move)) {
                        if (hideMoveVote) confirmMove(player,moveStr,move.getSan());
                        updateMoveVotes();
                    } else {
                        update(player.user, new MoleResult(false, "Bad Move: " + moveStr));
                    }
                }, () -> update(player.user, new MoleResult(false, "Bad Move: " + moveStr))
        );
    }

    private void confirmMove(MolePlayer player, String moveStr, String san) {
        ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
        node.set("player",player.toJSON());
        node.put("move",moveStr);
        node.put("san",san);
        node.set("game",this.toJSON(false));
        player.user.tell("move_conf",node);
    }

    private void updateMoveVotes() {
        ArrayNode listNode = MoleServ.OBJ_MAPPER.createArrayNode();
        for (MolePlayer p : teams[turn].players) {
            ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
            node.put("player_name",p.user.name);
            node.put("player_color",p.guiColor.getRGB());
            if (p.move == null) node.put("player_move","-");
            else if (hideMoveVote) node.put("player_move","(hidden)");
            else node.put("player_move",getTurnPrefix() + (p.move.getSan()));
            listNode.add(node);
        }
        ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
        node.put("move", ply);
        node.set("list",listNode);
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
                handleMoleVote(player,p);
            } else {
                update(user, new MoleResult(false, "Suspect not found (or wrong color)"));
            }
        }
    }

    public void resign(MoleUser user) {
        MolePlayer player = getPlayer(user);
        if (player == null) {
            update(user, new MoleResult(false, "Player not found: " + user.name));
        } else if ((phase == GAME_PHASE.PREGAME || phase == GAME_PHASE.POSTGAME) || ply < abortMoveLimit) {
            if (getCreator().equals(user)) abortGame(user);
            else update(user, new MoleResult(false, "Bad phase: " + phase));
        }
        else if (player.resigning) {
            update(user, new MoleResult(false, "You've already resigned!"));
        } else {
            player.resigning = true;
            update(new MoleResult(player.user.name + " resigns"));
            if (resigning(player.color)) endGame(getNextTurn(player.color), "resignation");
        }
    }

    public void handleVeto(MoleUser user, boolean confirm) {
        MolePlayer player = getPlayer(user);
        if (player == null) {
            update(user, new MoleResult(false, "Player not found: " + user.name));
        } else if (phase != GAME_PHASE.VETO) {
            update(user, new MoleResult(false, "Bad phase: " + phase));
        } else if (player.color == turn) {
            update(user, new MoleResult(false, "Wrong turn: " + colorString(turn)));
        } else if (player.role != MolePlayer.ROLE.MOLE) {
            update(user, new MoleResult(false, "You're not a Mole!"));
        } else if (player.isRampaging()) {
            update(user, new MoleResult(false, "You're rampaging!"));
        } else {
            veto = confirm;
            interruptPhase();
        }
    }

    public void interruptPhase() {
        if (gameThread != null && gameThread.getState() == Thread.State.TIMED_WAITING) gameThread.interrupt();
    }

    public void run() {
        playing = true;
        newPhase(GAME_PHASE.VOTING); //necessary for getAllPlayers()
        setRole(MolePlayer.ROLE.MOLE,MoleServ.TEST.equalsIgnoreCase("mole_test"));
        if (inspecting) setRole(MolePlayer.ROLE.INSPECTOR,MoleServ.TEST.equalsIgnoreCase("inspect_test"));
        confirmRoles();
        listener.started(this);  //starting position
        startPGN();
        while (playing && !closing) {
            spam("Turn #" + ply + ": " + colorString(turn));
            updateMoveVotes();
            autoPlay(turn); //boolean timeout =
            newPhase(GAME_PHASE.VOTING, moveTime);
            if (playing && !closing) {
                Move move = null;
                ArrayList<Move> moveList = getMoveVotes(turn);
                MolePlayer mole = getMole(turn);
                MolePlayer counterMole = getMole(getNextTurn());
                boolean bomb = false;
                if (mole != null && mole.bombing && mole.move != null) {
                    move = mole.move;
                    spam("MOLE BOMB!");
                    spam("molebomb",title);
                    bomb = true;
                }
                else {
                    move = pickMove(moveList);
                }

                if (mole != null && counterMole != null && moleVeto && (mole.isRampaging() || bomb)) {
                    spam("The enemy mole may veto the following move: " + move.getSan());
                    counterMole.user.tell("veto",title);
                    veto = false;
                    newPhase(GAME_PHASE.VETO,vetoTime); //TODO: test for closing?
                    if (veto) {
                        spam("Move vetoed!");
                        moveList.remove(move);
                        move = pickMove(moveList);
                        veto = false; bomb = false;
                        spam("The vetoing mole once voted for the following move: " + revealMoleMove(counterMole));
                    }
                }

                if (bomb) for (MolePlayer p : teams[turn].players) p.move = null;

                if (makeMove(move).success) {
                    MoveVotes votes = getMoveVotes(turn, board.getFen(), move,bomb);
                    moveHistory.add(votes);
                    updatePGN(votes);
                    update(new MoleResult("Selected Move: " + move.getSan()), true); //TODO: make less spammy
                    ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
                    node.put("move",move.getSan()); node.set("game",this.toJSON(false));
                    spamNode("move",node); //selectedMoves.add(move.getSan());
                    endgameCheck();
                    if (playing) {
                        MolePlayer predictor = testPrediction(mole,counterMole);
                        if (predictor != null) {
                            spam(mole.user.name + "'s move was predicted by " +
                            (predictor.role == MolePlayer.ROLE.MOLE ? " the enemy Mole!" : predictor.user.name + "!"));
                            defect(mole); //TODO: award points
                        }
                        clearPlayerFlags();
                        turn = getNextTurn();
                        ply++; teams[turn].bombFlag++; teams[turn].inspectFlag++;
                    }
                } else { spam("WTF: " + move); return; } ////shouldn't occur

            }
        }
        if (!closing && !isDeserted()) newPhase(GAME_PHASE.POSTGAME, postTime);
        closeGame();
    }

    private Move pickMove(List<Move> moves) {
        Move move;
        if (moves.size() == 0) {
            spam("No legal moves selected, picking randomly...");
            return getRandomMove();
        } else {
            int n = (int) (Math.random() * moves.size());
            return moves.get(n);
        }
    }

    private Move getRandomMove() {
        Move move = pickMove(board.legalMoves()); move.setSan(getSan(move)); return move;
    }

    private MolePlayer testPrediction(MolePlayer mole, MolePlayer counterMole) {
        if (mole == null || !mole.isRampaging() || mole.move == null || counterMole == null) return null;
        if (moleMovePrediction && counterMole.prediction != null) {
            if (counterMole.prediction.equals(mole.move)) return counterMole;
        }
        if (teamMovePrediction) {
            for (MolePlayer player : teams[mole.color].players) {
                if (player.role != MolePlayer.ROLE.MOLE && player.move != null && player.move.equals(mole.move)) {
                    return player;
                }
            }
        }
        return null;
    }

    public void inspect(MoleUser user) {
        MolePlayer player = getPlayer(user);
        if (player == null) {
            update(user,new MoleResult(false,"Player not found"));
        }
        else if (phase != GAME_PHASE.VOTING || player.color != turn) {
            update(user,new MoleResult(false,"Wrong turn/phase"));
        }
        else if (player.role != MolePlayer.ROLE.INSPECTOR) {
            update(user,new MoleResult(false,"You're not the inspector!"));
        }
        else if (teams[player.color].inspectFlag < inspectPly) {
            update(user,new MoleResult(false,"You can inspect in " +
                    (inspectPly - teams[player.color].inspectFlag) + " moves."));
        }
        else {
            user.tell("The mole played: " + revealMoleMove(getMole(player.color)),this);
            player.inspecting = true;
            player.move = getRandomMove();
            teams[player.color].inspectFlag = 0;
        }
    }

    public void moleBomb(MoleUser user) {
        MolePlayer player = getPlayer(user);
        if (player == null) {
            update(user,new MoleResult(false,"Player not found"));
        }
        else if (phase != GAME_PHASE.VOTING || player.color != turn) {
            update(user,new MoleResult(false,"Wrong turn/phase"));
        }
        else if (player.role != MolePlayer.ROLE.MOLE) {
            update(user,new MoleResult(false,"You're not the Mole!"));
        }
        else if (teams[player.color].bombFlag < bombPly) {
            update(user, new MoleResult(false,"You can bomb in " +
                    (bombPly - teams[player.color].bombFlag) + " moves."));
        }
        else if (!moleBomb) {
            update(user,new MoleResult(false,"Bomb disabled"));
        }
        else { //user.tell("Bomb disabled, pending bugfix, sorry!",this);
            user.tell("Bomb set!",this);
            player.bombing = true;
            teams[player.color].bombFlag = 0;
        }
    }

    private String revealMoleMove(MolePlayer mole) {
        if (mole == null) return "(no mole)";
        ArrayList<MoveVote> moves = getMoves(mole);
        if (moves.size() < 1) return "(no moves)";
        else {
            int n = (int)Math.floor(Math.random() * moves.size());
            MoveVote vote = moves.get(n);
            return vote.move.getSan();
        }
    }

    private ArrayList<MoveVote> getMoves(MolePlayer player) {
        ArrayList<MoveVote> moveList = new ArrayList<>();
        for (MoveVotes moves : getMoves(player.color)) {
            if (moves.selected.player != null) { //TODO: figure out why this happens
                if (moves.selected.player.equals(player)) moveList.add(moves.selected);
                for (MoveVote move : moves.alts) if (move.player.equals(player)) moveList.add(move);
            }
        }
        return moveList;
    }

    private ArrayList<MoveVotes> getMoves(int color) {
        ArrayList<MoveVotes> moves = new ArrayList<>();
        for (MoveVotes vote : moveHistory) if (vote.color == color) moves.add(vote);
        return moves;
    }

    private void autoPlay(int turn) { //TODO: possible timechange bug?
        for (MolePlayer player : teams[turn].players) {
            if (player.ai) player.analyzePosition(board.getFen(), (int) (moveTime * calcFactor) * 1000);
        }
    }

    private void handleMoleVote(MolePlayer player, MolePlayer p) {
        if (player.vote == p) {
            player.vote = null;
            update(new MoleResult(player.user.name + " retracts their vote"));
            return;
        }
        player.vote = p;
        update(new MoleResult(player.user.name + " votes off: " + p.user.name,player));
        MolePlayer suspect = checkVote(player.color);
        if (suspect != null) {
            spam(suspect.user.name + " is voted off!",suspect);
            if (suspect.role == MolePlayer.ROLE.MOLE) { //TODO: updates instead of spam?
                spam(suspect.user.name + " was " + "the Mole!",suspect); //award(player.color, moleBonus);
                pgnBuff.append(" {").append("VOTED OFF: ").append(suspect.user.name).append("} ");
                if (defection) defect(suspect); else suspect.votedOff = true;
            } else {
                MolePlayer mole = getMole(player.color);
                if (mole != null) {
                    spam(mole.user.name + " was " + "the Mole!",mole); //award(mole, moleBonus);
                    spamNode("rampage", mole.toJSON());
                    pgnBuff.append(" {").append("RAMPAGE: ").append(mole.user.name).append("} ");
                }
                else spam("WTF: no mole!");
            }
            teams[player.color].voteCount++;
            if (endOnAccusation) {
                endGame(COLOR_UNKNOWN, "Mole vote");
            }
            else if (endOnMutualAccusation && teams[COLOR_BLACK].voteCount > 0 && teams[COLOR_WHITE].voteCount > 0) {
                endGame(COLOR_UNKNOWN, "mutual mole vote");
            }
        }
    }

    private void defect(MolePlayer player) {
        defect(player,getNextTurn(player.color));
    }

    private void defect(MolePlayer player, int newColor) {
        teams[player.color].players.remove(player);
        player.color = newColor;
        player.role = MolePlayer.ROLE.PLAYER;
        player.move = null;
        player.skipped = 0;
        teams[player.color].players.add(player);
        update(new MoleResult(player.user.name + " defects to " + colorString(newColor) + "!"));
        spamNode("defection",player.toJSON());
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
        if (newTime > 0) { moveTime = newTime; newTime = 0; } //TODO: clarify movetime setting effects
        phaseStamp = System.currentTimeMillis();
        spamNode("phase",toJSON(false));
        boolean timeout = true;
        if (seconds > 0) {
            try { Thread.sleep((seconds * 1000L)); } catch (InterruptedException e) { timeout = false; }
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

    public void endGame(int winner, String reason) {
        String result;
        if (!playing && !closing) {
            spam("WTF: game already finished"); return;
        }
        else if (winner != COLOR_UNKNOWN) {
            spam(colorString(winner) + " wins by " + reason + "!"); //award(winner,winBonus);
            if (!casual) listener.updateUserData(teams[winner].players, teams[getNextTurn(winner)].players,false);
            result = (winner == COLOR_WHITE) ? "1-0" : "0-1";
        } else {
            spam("Game Over! (" + reason + ")");
            if (!casual) listener.updateUserData(teams[COLOR_WHITE].players, teams[COLOR_BLACK].players,true);
            if (reason.equals("deserted")) result = "aborted"; else result = "1/2-1/2";
        }

        if (!casual && phase != GAME_PHASE.PREGAME) {
            listener.saveGame(createPGN(result), teams[COLOR_WHITE].players, teams[COLOR_BLACK].players, winner);
            for (MolePlayer player : teams[COLOR_WHITE].startPlayers) {
                if (player.role == MolePlayer.ROLE.MOLE) spam("White Mole: " + player.user.name);
            }
            for (MolePlayer player : teams[COLOR_BLACK].startPlayers) {
                if (player.role == MolePlayer.ROLE.MOLE) spam("Black Mole: " + player.user.name);
            }
        }

        playing = false;
        interruptPhase();
    }

    private void startPGN() {
        pgnBuff.append(pgnTag("Site","molechess.com") + CR);
        pgnBuff.append(pgnTag("Date", LocalDate.now().toString()) + CR);
        pgnBuff.append(pgnTag("White",teams[COLOR_WHITE].toString(true)) + CR);
        pgnBuff.append(pgnTag("Black",teams[COLOR_BLACK].toString(true)) + CR);
    }

    private void updatePGN(MoveVotes votes) {
        String pfx = getTurnPrefix();
        pgnBuff.append(pfx)
                .append(votes.selected.move.getSan())
                .append(" {")
                .append(votes.selected.player == null ? "?" : votes.selected.player.user.name)
                .append(getPgnMoveArrows(votes))
                .append("} ");
        for (MoveVote alt : votes.alts) if (alt.player != null)
            pgnBuff.append(" ( ")
                    .append(alt.move.getSan())
                    .append(" {")
                    .append(alt.player.user.name)
                    .append("} ) ");
    }

    private String createPGN(String result) {
        StringBuffer pgn = new StringBuffer();
        pgn.append(pgnTag("Result",result) + CR);
        return pgn.append(pgnBuff).toString();
    }

    private String getPgnMoveArrows(MoveVotes votes) {
        StringBuffer buff = new StringBuffer("[%cal ");
        for (MoveVote alt : votes.alts) buff.append("R" + getMoveArrowString(alt.move) + ",");
        buff.append("G" + getMoveArrowString(votes.selected.move) + "]");
        return buff.toString();
    }

    private String getMoveArrowString(Move move) { //ignores promotion
        return move.getFrom().toString().toLowerCase() + move.getTo().toString().toLowerCase();
    }

    private String pgnTag(String type, String val) {
        return "[" + type + " \"" + val + "\"]";
    }

    private void aiFill(int color) {
        while (teams[color].players.size() < minPlayers) {
            int n = (int) Math.floor(Math.random() * MOLE_NAMES.size());
            MolePlayer player = new MolePlayer(
                    new MoleUser(null, null, MOLE_NAMES.get(n).trim(),1600), this, color, nextGUIColor());
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

    private void confirmRoles() { //System.out.println("Confirming roles...");
        for (MoleTeam team : teams) Collections.shuffle(team.players);
        for (MolePlayer p : getAllPlayers()) { //System.out.println(p.user.name + ": " + p.role);
            p.user.tell("role",p.role.toString(),this);
            teams[p.color].startPlayers.add(p);
        }
    }

    private void setRole(MolePlayer.ROLE role, boolean creatorRole) {
        for (MoleTeam team : getTeams()) {
            MolePlayer p = findRolePlayer(team,role,creatorRole);
            if (p != null) p.role = role;
            else spam("Could not assign role: " + role);
        }
    }

    private MolePlayer findRolePlayer(MoleTeam team, MolePlayer.ROLE role, boolean creatorRole) {
        if (creatorRole) {
            MolePlayer player = getPlayer(creator);
            if (player != null && team.players.contains(player)) return player;
        }
        Collections.shuffle(team.players);
        for (MolePlayer player : team.players) {
            if (player.role == MolePlayer.ROLE.PLAYER && (role != MolePlayer.ROLE.MOLE || !onlyHuman(player))) {
                return player;
            }
        }
        return null;
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
        return switch (color) {
            case COLOR_BLACK -> "Black";
            case COLOR_WHITE -> "White";
            case COLOR_UNKNOWN -> "?";
            default -> "err";
        };
    }

    private int getNextTurn() {
        return getNextTurn(turn);
    }

    private int getNextTurn(int color) { //TODO: firstColor, lastColor (for extra-color games)
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

    private String getFromString(final Piece piece, final Square from, final Square to) {
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

    public String getSan(final Move move) {
        return getSan(move,board);
    }

    /*
     * Returns a SAN representation of given move
     * @param move the move object
     * @param sanBoard the board to make the move on
     * @return San representation of the given move if the move is legal; null otherwise
     */
    private String getSan(final Move move, final Board sanBoard) {
        if (sanBoard.legalMoves().contains(move)) {
            final Board auxBoard = sanBoard.clone();
            auxBoard.doMove(move);
            Square from = move.getFrom(); Square to = move.getTo();
            Piece piece = sanBoard.getPiece(from);
            final String ending = auxBoard.isMated() ? "#" : auxBoard.isKingAttacked() ? "+" : "";
            if (piece.equals(Piece.BLACK_KING) && from.equals(Square.E8)) {
                if (to.equals(Square.G8)) {
                    return "O-O" + ending;
                } else if (to.equals(Square.C8)) {
                    return "O-O-O" + ending;
                }
            } else if (piece.equals(Piece.WHITE_KING) && from.equals(Square.E1)) {
                if (to.equals(Square.G1)) {
                    return "O-O" + ending;
                } else if (to.equals(Square.C1)) {
                    return "O-O-O" + ending;
                }
            }

            Piece target = sanBoard.getPiece(move.getTo());
            final String takes = (target != Piece.NONE ||
                    (piece.getPieceType() == PieceType.PAWN && !from.getFile().equals(to.getFile()))) ? "x" : "";
            final String promotion = move.getPromotion() != Piece.NONE ? "=" + move.getPromotion().getSanSymbol() : "";
            final String fromStr = getFromString(piece,from,to).toLowerCase();
            final String toStr = to.value().toLowerCase();
            final String sanSymbol = (piece.getSanSymbol().equals("") && takes.equals("x") && fromStr.equals("")) ?
                    from.value().substring(0, 1).toLowerCase() : piece.getSanSymbol();
            return sanSymbol + fromStr + takes + toStr + promotion + ending;
        }
        return null;
    }

    private String getTurnPrefix() {
        int n = (int) (Math.floor(ply / 2) + 1);
        return turn == COLOR_WHITE ? n + "." : n + "...";
    }

    private boolean addMoveVote(final MolePlayer player, final Move move) { //log("Adding: " + move + " -> " + move.getPromotion());
        final String san = getSan(move); if (san == null) return false;
        move.setSan(san);
        player.move = move;
        if (countMoveVotes(player.color) >= activePlayers(turn, true)) interruptPhase();
        return true;
    }

    private boolean addMovePrediction(final MolePlayer player, final Move move) {
        final String san = getSan(move); if (san == null) return false;
        move.setSan(san);
        player.prediction = move;
        return true;
    }

    private int countMoveVotes(int color) {
        int count = 0;
        for (MolePlayer player : teams[color].players) if (player.move != null) count++;
        return count;
    }

    private void clearPlayerFlags() {
        for (MolePlayer player : getAllPlayers()) {
            player.move = null; player.prediction = null; player.piecePrediction = null;
            player.inspecting = false; player.bombing = false; player.blocking = false;
        }
    }

    private ArrayList<Move> getMoveVotes(int color) { //TODO: random moves for nulls?
        ArrayList<Move> moveList = new ArrayList<>();
        for (MolePlayer player : teams[color].players) if (player.move != null) moveList.add(player.move);
        return moveList;
    }

    private MoveVotes getMoveVotes(int color, String fen, Move selectedMove, boolean bomb) {
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
            } else if (!bomb) player.skipped++;
        }
        if (!selected) {
            MoveVote mv = new MoveVote(bomb ?
                    new MolePlayer(new MoleUser(null,"","Mole",0),this,color,Color.BLACK) :
                    new MolePlayer(new MoleUser(null,"","RNG",0),this,color,Color.BLACK),
                    selectedMove);
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
        if (board.doMove(move)) {
            gameLog.log(Level.INFO,"New Move: " + getTurnPrefix() + move);
            return new MoleResult("Move: " + move);
        } else return new MoleResult(false, "Invalid Move: " + move); //shouldn't occur
    }

    private void spamMoves(Move move) {
        ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
        node.put("lm", move == null ? "" : move.toString());
        node.put("fen", board.getFen());
        spamNode(MoleServ.MSG_GAME_UPDATE, node);
    }

    private MolePlayer checkVote(int color) {
        for (MolePlayer p : teams[color].players) if (checkVote(p)) return p;
        return null;
    }

    private boolean checkVote(MolePlayer suspect) {
        int votes = 0;
        for (MolePlayer voter : teams[suspect.color].players) {
            if (!voter.equals(suspect) && !voter.ai && voter.isActive()) {
                if (voter.vote != suspect) return false;
                else votes++;
            }
        }
        return votes > 0; //to avoid weirdness with AI/away/etc.
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

    public ArrayList<MolePlayer> getAllPlayers() {
        if (BUCKETS && phase == GAME_PHASE.PREGAME) return playerBucket;
        ArrayList<MolePlayer> list = new ArrayList<>();
        for (MoleTeam team : teams) list.addAll(team.players);
        return list;
    }

    public void spam(String msg) { spam("game_msg", msg, null); }
    public void spam(String type, String msg) { spam(type,msg,null); }
    public void spam(String msg, MolePlayer p) { spam("game_msg", msg, p); }
    public void spam(String type, String msg, MolePlayer p) {
        ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
        node.put("msg", msg);
        node.set("player",(p == null) ? null : p.toJSON()); //useful for conveying player color, etc.
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
                PASTELS = true; return nextGUIColor(); //colorPointer = 0; return colorList.get(colorPointer);
            }
        }
    }

    public ObjectNode getGameOptions() {
        ObjectNode options = MoleServ.OBJ_MAPPER.createObjectNode();
        options.put("move_time",moveTime);
        options.put("max_play",maxPlayers);
        options.put("mole_veto",moleVeto);
        options.put("mole_move_predict",moleMovePrediction);
        options.put("mole_piece_predict",molePiecePrediction);
        options.put("team_move_predict",teamMovePrediction);
        options.put("hide_move",hideMoveVote);
        options.put("mole_bomb",moleBomb);
        options.put("inspector_role",inspecting);
        options.put("casual",casual);
        return options;
    }

    public void tellRole(MoleUser user) {
        MolePlayer p = getPlayer(user);
        if (p != null) user.tell("Your role: " + p.role); else user.tell("You're not in this game!");
    }
    private static void log(String msg) {
        MoleServ.log(msg);
    }

}
