package org.chernovia.molechess;

import kotlin.Pair;

import java.util.List;

public interface StockListener {
    void newStockMove(String move);
    void updateEvaluations(float currentEval, List<Pair<MolePlayer, Float>> evaluations);
}
