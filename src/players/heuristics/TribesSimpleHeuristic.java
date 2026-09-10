package players.heuristics;

import core.TechnologyTree;
import core.actors.City;
import core.actors.units.Unit;
import core.game.GameState;
import core.*;
import utils.stats.StatSummary;

import java.util.ArrayList;

public class TribesSimpleHeuristic implements StateHeuristic{

    private int playerID;
    ArrayList<Integer> allIds;
    private int WIN_BOOST = 1000000000;
    private int LOSE_BOOST = 1000000000;

    public TribesSimpleHeuristic(int playerID, ArrayList<Integer> allIds)
    {
        this.allIds = allIds;
        this.playerID = playerID;
    }

    /**
     * Two-argument form used by the (Experimental)EMCTS agents. SIMPLE is a RAW
     * (absolute) state evaluation, so the old state is ignored and we return the raw
     * value of the new state. (DIFF, by contrast, returns new-vs-old differences.)
     */
    @Override
    public double evaluateState(GameState oldState, GameState newState)
    {
        core.game.ComputeMeter.onEval();
        if (core.game.ComputeMeter.PROFILE) {
            long __t0 = System.nanoTime();
            double __v = evaluateState(newState);
            core.game.ComputeMeter.addEvalNanos(System.nanoTime() - __t0);
            return __v;
        }
        return evaluateState(newState);
    }

    @Override
    public double evaluateState(GameState gameState)
    {
        double[] scores = new double[allIds.size()];
        double myScore = 0.0;
        StatSummary othersScore = new StatSummary();
        for(int i = 0; i < scores.length; ++i)
        {
            scores[i] = score(gameState, i);
            if(i == playerID)
                myScore = scores[i];
            else
                othersScore.add(scores[i]);
        }
        double scoreDiff = othersScore.n() > 0 ? myScore - othersScore.mean() : myScore;

        double visCount = viscPerc(gameState);

        return scoreDiff + visCount*10;
    }

    private double viscPerc(GameState gameState)
    {
        boolean [][] visibility = gameState.getVisibilityMap();
        int visCount = 0;
        for (boolean[] booleans : visibility)
            for (boolean aBoolean : booleans) visCount += aBoolean ? 1 : 0;

        return (double)visCount / (visibility.length*visibility[0].length);
    }

    private double score(GameState gameState, int playerId)
    {
        int numAvailableActions = gameState.getAllAvailableActions().size();
        int availableProduction = gameState.getTribeProduction(playerId);
        TechnologyTree tt = gameState.getTribeTechTree(playerId);
        int score = gameState.getScore(playerId);
        ArrayList<City> cities = gameState.getCities(playerId);
        ArrayList<Unit> units = gameState.getUnits(playerId);

        int unitPositionReward = 0;
        core.game.Board board = gameState.getBoard();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            int x = u.getPosition().x;
            int y = u.getPosition().y;
            Types.TERRAIN t = board.getTerrainAt(x, y);
            Types.RESOURCE r = board.getResourceAt(x, y);
            int cityId = board.getCityIdAt(x, y);
            if (!u.isFresh()) {
                if (t == Types.TERRAIN.VILLAGE || r == Types.RESOURCE.RUINS || (t == Types.TERRAIN.CITY && cityId != -1 && ((City) board.getActor(cityId)).getTribeId() != playerId)) {
                    unitPositionReward += 10;
                }
            }
        }

        int boost = 0;
        if(gameState.isGameOver())
            if(gameState.getTribeWinStatus(playerId) == Types.RESULT.WIN)
                boost = WIN_BOOST;
            else if (gameState.getTribeWinStatus(playerId) == Types.RESULT.LOSS)
                // NEGATIVE: a loss must repel the search. This was `boost = LOSE_BOOST`
                // (positive, same as a win) since the original import; in the 2-player
                // score-diff both sides' boosts then cancelled exactly, so terminal
                // outcomes were invisible to every agent using this heuristic.
                boost = -LOSE_BOOST;

        double fishingPenalty = 0;
        if (Constants.REMOVE_FISHING) {
            if (tt.isResearched(Types.TECHNOLOGY.FISHING)) fishingPenalty -= 100;
            if (tt.isResearched(Types.TECHNOLOGY.SAILING)) fishingPenalty -= 100;
            if (tt.isResearched(Types.TECHNOLOGY.WHALING)) fishingPenalty -= 100;
            if (tt.isResearched(Types.TECHNOLOGY.NAVIGATION)) fishingPenalty -= 100;
            if (tt.isResearched(Types.TECHNOLOGY.AQUATISM)) fishingPenalty -= 100;
        }

        return boost + numAvailableActions + availableProduction*100 + tt.getNumResearched()*10 + score + cities.size()*500 + units.size()*10 + unitPositionReward + fishingPenalty;
    }
}
