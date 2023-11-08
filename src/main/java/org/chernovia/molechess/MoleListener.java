package org.chernovia.molechess;

import java.util.ArrayList;
import java.util.List;

public interface MoleListener {
    void started(MoleGame game);

    void updateUserData(ArrayList<MolePlayer> winners, ArrayList<MolePlayer> losers, boolean draw);

    void updateUser(MoleUser user, MoleGame game, MoleResult action, boolean movelist);

    void updateGame(MoleGame game, MoleResult action, boolean movelist);

    void ready(MoleGame game);

    void finished(MoleGame game);

    void saveGame(String pgn, List<MolePlayer> whiteTeam, List<MolePlayer> blackTeam, int winner);
}
