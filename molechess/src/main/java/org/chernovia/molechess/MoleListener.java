package org.chernovia.molechess;

import java.util.ArrayList;

public interface MoleListener {
	public void updateUserData(ArrayList<MolePlayer> winners, ArrayList<MolePlayer> losers, boolean draw);
	public void started(MoleGame game);
	public void notify(MoleUser user, MoleResult action, boolean update);
	public void updateAll();
	public void finished(MoleGame game);
}
