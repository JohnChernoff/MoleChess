package org.chernovia.molechess

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import org.chernovia.lib.chess.StockPlug
import org.chernovia.molechess.MolePlayer.ROLE

class StockVoter(
    private val l: StockListener,
    private val path: String?,
    private val fen: String,
    private val black: Boolean,
    private val fens: List<Pair<MolePlayer, String>>,
    private val time: Int,
    private val elo: Int
) : Thread() {

    private val stockfish: StockPlug = StockPlug()
    override fun run() {
        stockfish.startEngine(path)
        stockfish.setOptions(1, 25, elo)
        val currentVal = (if (black) -1 else 1) * stockfish.getEvalScore(fen, time)
        val evaluations =
            fens.map { Pair(it.first, (if (black) 1 else -1) * stockfish.getEvalScore(it.second, time)) }
        stockfish.stopEngine()
        l.updateEvaluations(currentVal, evaluations)
    }
}

fun MolePlayer.startSuspecting(players: List<Pair<MolePlayer, Move?>>, currentBoard: Board, time: Int) {
    suspecting = true
    val fens = players.filter { it.second != null }
        .map { p ->
            currentBoard.clone().let { nb ->
                nb.doMove(p.second)
                Pair(p.first, nb.fen)
            }
        }
    StockVoter(
        this,
        MoleServ.STOCK_PATH,
        currentBoard.fen,
        currentBoard.sideToMove == Side.BLACK,
        fens,
        time,
        if (role == ROLE.PLAYER) MoleServ.STOCK_STRENGTH else MoleServ.STOCK_MOLE_STRENGTH
    ).start()
}