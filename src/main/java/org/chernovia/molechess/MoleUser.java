package org.chernovia.molechess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.chernovia.lib.zugserv.Connection;
import org.chernovia.lib.zugserv.ZugServ;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

public class MoleUser {
    static final long DISCO_UNKNOWN = -1;
    String lichessToken, pushToken = "";
    long discoID = MoleUser.DISCO_UNKNOWN;
    String name;
    int blitzRating;
    private Connection conn;
    public MoleData data;
    private ArrayList<Connection> observers = new ArrayList<>();
    Deque<Long> messageStack = new ConcurrentLinkedDeque<>();

    public class MoleData {
        String about;
        int wins, losses, rating;

        public MoleData(int wins, int losses, int rating, String about) {
            this.wins = wins;
            this.losses = losses;
            this.rating = rating;
            this.about = about;
        }

        public String toString() {
            return "Wins: " + wins + ", Losses: " + losses + ", Rating: " + rating;
        }

        public JsonNode toJSON(boolean ratingOnly) {
            ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
            if (!ratingOnly) {
                node.put("wins", wins);
                node.put("losses", losses);
                node.put("about", about);
            }
            node.put("rating", rating);
            return node;
        }
    }

    public MoleUser(Connection c, String o, String n, int r) {
        conn = c;
        if (conn != null) {
            conn.setStatus(Connection.Status.STATUS_OK);
        }
        lichessToken = o;
        name = n;
        blitzRating = r;
    }

    public void addObserver(Connection c) {
        if (!observers.contains(c)) observers.add(c);
    }

    public void setData(int wins, int losses, int rating, String about) {
        data = new MoleData(wins, losses, rating, about);
    }

    public MoleData getEmptyData() {
        return this.new MoleData(0, 0, 0, "");
    }

    public Optional<MoleData> getData() {
        return Optional.ofNullable(data);
    }

    public boolean sameConnection(Connection c) {
        return (this.conn == c);
    }

    public Connection getConn() {
        return conn;
    }

    public void setConn(Connection c) {
        conn = c;
    }

    public boolean isActiveUser() {
        if (conn == null) return false;
        Connection.Status status = conn.getStatus();
        if (status == null) return false;
        else return (status.equals(Connection.Status.STATUS_OK));
    }

    //public void doink(String msg) { tell(ZugServ.MSG_ERR,msg,null); }
    public void tell(String msg) { tell(ZugServ.MSG_SERV,msg,null); }
    public void tell(String msg, MoleGame game) { tell(ZugServ.MSG_SERV,msg,game); }
    public void tell(String type, String msg) { tell(type,msg,null); }
    public void tell(String type, String msg, MoleGame game) {
        ObjectNode node = MoleServ.OBJ_MAPPER.createObjectNode();
        node.put("msg", msg);
        node.put("source",game == null ? "serv" : game.getTitle());
        tell(type,node);
    }
    public void tell(String type, JsonNode node) {
        if (this.conn != null) this.conn.tell(type, node); //TODO: check status?
        for (Connection obs : observers) {
            if (obs.getStatus() != Connection.Status.STATUS_DISCONNECTED) obs.tell(type,node);
        }
    }

    public JsonNode toJSON(boolean ratingOnly) {
        ObjectNode obj = MoleServ.OBJ_MAPPER.createObjectNode();
        obj.put("name", this.name);
        obj.put("blitz", this.blitzRating);
        if (data != null) obj.set("data", data.toJSON(ratingOnly));
        return obj;
    }

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof MoleUser)) return false;
        return ((MoleUser) o).lichessToken.equals(this.lichessToken);
    }

    public boolean newMessage(int max, long millis) {
        long currentTime = System.currentTimeMillis(); int n = 0; long t = currentTime - millis;
        for (long msg : messageStack) {
            if (msg > t) n++;
            else messageStack.remove(msg);
        }
        if (n > max) return false;
        messageStack.add(currentTime);
        return true;
    }
}
