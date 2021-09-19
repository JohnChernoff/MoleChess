package org.chernovia.molechess;

import java.util.ArrayList;

public interface MoleListener {
	public void started(MoleGame game);
	public void updateUserData(ArrayList<MolePlayer> winners, ArrayList<MolePlayer> losers, boolean draw);
	public void updateUser(MoleUser user, MoleGame game, MoleResult action, boolean movelist);
	public void updateGame(MoleGame game, MoleResult action, boolean movelist);
	public void finished(MoleGame game);
}
