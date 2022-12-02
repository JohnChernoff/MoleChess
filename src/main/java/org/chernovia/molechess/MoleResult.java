package org.chernovia.molechess;

public class MoleResult {
    boolean success;
    String message;
    MoleUser user;
    MolePlayer player;

    public MoleResult(String s) {
        success = true;
        message = s;
        user = null; player = null;
    }

    public MoleResult(String s, MoleUser u) {
        success = true;
        message = s;
        user = u; player = null;
    }

    public MoleResult(String s, MolePlayer p) {
        success = true;
        message = s;
        user = p.user; player = p;
    }

    public MoleResult(boolean b, String s) {
        success = b;
        message = s;
        user = null; player = null;
    }

    public MoleResult(boolean b, String s, MoleUser u) {
        success = b;
        message = s;
        user = u; player = null;
    }

    public MoleResult(boolean b, String s, MolePlayer p) {
        success = b;
        message = s;
        user = p.user; player = p;
    }
}
