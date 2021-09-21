package org.chernovia.molechess;

import org.chernovia.lib.chess.StockPlug;

public class StockThread extends Thread {
	StockListener listener;
	StockPlug stockfish;
	String path;
	String fen;
	int moveTime, elo;
	
	public StockThread(StockListener l, String path, String fen, int moveTime, int elo) { 
		stockfish = new StockPlug();
		this.path = path; listener = l; this.fen = fen; this.moveTime = moveTime; this.elo = elo;
	}
	
	public void run() {
		stockfish.startEngine(path);
		stockfish.setOptions(1,25,elo);
		String move = stockfish.getBestMove(fen, moveTime);
		listener.newStockMove(move); //System.out.println("New Move: " + move);
		stockfish.stopEngine();
	}
}
