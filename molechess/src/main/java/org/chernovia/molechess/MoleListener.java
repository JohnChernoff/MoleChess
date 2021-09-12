package org.chernovia.molechess;

public interface MoleListener {
	public void started(MoleGame game);
	public void notify(MoleUser user, MoleResult action, boolean update);
	public void updateAll();
	public void finished(MoleGame game);
}
