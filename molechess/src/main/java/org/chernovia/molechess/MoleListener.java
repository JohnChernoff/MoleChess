package org.chernovia.molechess;

public interface MoleListener {
	public void started(MoleGame game);
	public void handleAction(MoleUser user, MoleResult action);
	public void finished(MoleGame game);
}
