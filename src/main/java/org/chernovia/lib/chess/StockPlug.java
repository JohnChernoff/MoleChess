package org.chernovia.lib.chess;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * A simple and efficient client to run Stockfish from Java
 *
 * @author Rahul A R (with recent additions by John Chernoff)
 */
public class StockPlug {

    private Process engineProcess;
    private BufferedReader processReader;
    private OutputStreamWriter processWriter;
    private String id = "?";

    /**
     * Starts Stockfish engine as a process and initializes it
     *
     * @return True on success. False otherwise
     */
    public boolean startEngine(String path) {
        try {
            engineProcess = Runtime.getRuntime().exec(path);
            processReader = new BufferedReader(new InputStreamReader(
                    engineProcess.getInputStream()));
            processWriter = new OutputStreamWriter(
                    engineProcess.getOutputStream());
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        //System.out.println("New Process: " + engineProcess.pid());
        id = engineProcess.pid() + "";
        return true;
    }

    public String getID() {
        return id;
    }

    /**
     * Takes in any valid UCI command and executes it
     *
     * @param command
     */
    public void sendCommand(String command) {
        //System.out.println(id + " -> CMD: " + command);
        try {
            processWriter.write(command + "\n");
            processWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This is generally called right after 'sendCommand' for getting the raw
     * output from Stockfish
     *
     * @param waitTime Time in milliseconds for which the function waits before
     *                 reading the output. Useful when a long running command is
     *                 executed
     * @return Raw output from Stockfish
     */
    public String getOutput(int waitTime) {
        return getOutput("readyok", waitTime);
    }

    public String getOutput(String keyString, int waitTime) {
        StringBuffer buffer = new StringBuffer();
        try {
            //System.out.println("Sleeping: " + waitTime);
            Thread.sleep(waitTime);
            sendCommand("isready");
            while (true) {
                String text = processReader.readLine();
                //System.out.println(id + ": " + text);
                buffer.append(text + "\n");
                if (text.startsWith(keyString)) break;

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return buffer.toString();
    }

    /**
     * This function returns the best move for a given position after
     * calculating for 'waitTime' ms
     *
     * @param fen      Position string
     * @param waitTime in milliseconds
     * @return Best Move in PGN format
     */
    public String getBestMove(String fen, int waitTime) {
        sendCommand("position fen " + fen);
        sendCommand("go movetime " + waitTime);
        String output = getOutput("bestmove", waitTime + 20);
        return output.split("bestmove ")[1].split(" ")[0];
    }

    public void setOptions(int threads, int hashsize, int elo) {
        sendCommand("setoption name Threads value " + threads);
        sendCommand("setoption name Hash value " + hashsize);
        sendCommand("setoption name UCI_LimitStrength value true");
        sendCommand("setoption name UCI_Elo value " + elo);
        getOutput(100);
    }

    /**
     * Stops Stockfish and cleans up before closing it
     */
    public void stopEngine() {
        try {
            sendCommand("quit");
            processReader.close();
            processWriter.close();
        } catch (IOException e) {
        }
    }

    /**
     * Get a list of all legal moves from the given position
     *
     * @param fen Position string
     * @return String of moves
     */
    public String getLegalMoves(String fen) {
        sendCommand("position fen " + fen);
        sendCommand("d");
        return getOutput(0).split("Legal moves: ")[1];
    }

    /**
     * Draws the current state of the chess board
     *
     * @param fen Position string
     */
    public void drawBoard(String fen) {
        sendCommand("position fen " + fen);
        sendCommand("d");

        String[] rows = getOutput(0).split("\n");

        for (int i = 1; i < 18; i++) {
            System.out.println(rows[i]);
        }
    }

    /**
     * Get the evaluation score of a given board position
     *
     * @param fen      Position string
     * @param waitTime in milliseconds
     * @return evalScore
     */
    public float getEvalScore(String fen, int waitTime) {
        sendCommand("position fen " + fen);
        sendCommand("go movetime " + waitTime);

        String[] dump = getOutput(waitTime + 20).split("\n");
        String eval_str = "";
        for (int i = dump.length - 1; i >= 0; i--) {
            //System.out.println(i + " -> " + dump[i]);
            if (dump[i].startsWith("info depth ")) {
                if (dump[i].contains("mate")) return 999;
                if (dump[i].contains("score cp")) {
                    try {
                        return Float.parseFloat(dump[i].split("score cp ")[1].split(" nodes")[0]) / 100;
                    } catch (Exception e) {
                        try {
                            return Float.parseFloat(dump[i].split("score cp ")[1].split(" upperbound nodes")[0]) / 100;
                        } catch (Exception oops) {
                            System.out.println("Eval error: " + oops.getMessage() + " -> " + dump[i]);
                            System.out.println(eval_str);
                        }
                    }
                }
            }
        }
        return 0;
    }
}
