package players.heuristics;

import core.game.GameState;
import utils.stats.StatSummary;

import core.actors.City;
import core.actors.units.Unit;
import java.util.ArrayList;

import core.Constants;
import core.Types;

/**
 * Compares some important game metrics between an old gamestate and a new gamestate. Returns a score called 'entropy'.
 * An entropy of 0 means the gamestates are identical in terms of the metrics being compared, positive entropy means
 * that some metrics in the new gamestate have increased.
 */
public class TribesEntropyHeuristic implements StateHeuristic {

    private int playerID;
    ArrayList<Integer> allIds;

    public TribesEntropyHeuristic(int playerID, ArrayList<Integer> allIds)
    {
        this.allIds = allIds;
        this.playerID = playerID;
    }

    @Override
    public double evaluateState(GameState gsOld, GameState gsNew)
    {
        double[] scores = new double[allIds.size()];
        double myScore = 0.0;
        StatSummary othersScore = new StatSummary();
        for(int i = 0; i < scores.length; ++i)
        {
            scores[i] = score(gsOld, gsNew, i);
            if(i == playerID)
                myScore = scores[i];
            else
                othersScore.add(scores[i]);
        }

        double scoreDiff = othersScore.n() > 0 ? myScore - othersScore.mean() : myScore;
        double scoreOwn = scoreOwn(gsOld, gsNew);
        return scoreDiff + scoreOwn;
    }

    private double scoreOwn(GameState gsOld, GameState gsNew)
    {
        int connections = gsNew.getTribe(playerID).getConnectedCities().size();
        boolean [][] visibility = gsNew.getVisibilityMap();
        int visCountNew = 0;
        for (boolean[] booleans : visibility)
            for (boolean aBoolean : booleans) visCountNew += aBoolean ? 1 : 0;

        visibility = gsOld.getVisibilityMap();
        int visCountOld = 0;
        for (boolean[] booleans : visibility)
            for (boolean aBoolean : booleans) visCountOld += aBoolean ? 1 : 0;

        double entropy = 0;
        entropy += connections > gsOld.getTribe(playerID).getConnectedCities().size() ? 1 : 0;
        entropy += visCountNew > visCountOld ? 1 : 0;
        return entropy;
    }


    private double score(GameState gsOld, GameState gsNew, int playerId) {
        //Metrics of the game that we want to maximise.
        int production = gsNew.getTribeProduction(playerId);
        int technologies = gsNew.getTribeTechTree(playerId).getNumResearched();
        int score = gsNew.getScore(playerId);
        int cities = gsNew.getCities(playerId).size();
        int units = gsNew.getUnits(playerId).size();
        int kills = gsNew.getNKills(playerId);

        //compare with old metrics and calculate entropy.
        double entropy = 0;

        int unitPositionRewardOld = 0;
        core.game.Board boardOld = gsOld.getBoard();
        ArrayList<Unit> unitsOld = gsOld.getUnits(playerId);
        for (int i = 0; i < unitsOld.size(); i++) {
            Unit u = unitsOld.get(i);
            int x = u.getPosition().x;
            int y = u.getPosition().y;
            Types.TERRAIN t = boardOld.getTerrainAt(x, y);
            Types.RESOURCE r = boardOld.getResourceAt(x, y);
            int cityId = boardOld.getCityIdAt(x, y);
            if (!u.isFresh()) {
                if (t == Types.TERRAIN.VILLAGE || r == Types.RESOURCE.RUINS || (t == Types.TERRAIN.CITY && cityId != -1 && ((City) boardOld.getActor(cityId)).getTribeId() != playerId)) {
                    unitPositionRewardOld += 10;
                }
            }
        }

        int unitPositionRewardNew = 0;
        core.game.Board boardNew = gsNew.getBoard();
        ArrayList<Unit> unitsNew = gsNew.getUnits(playerId);
        for (int i = 0; i < unitsNew.size(); i++) {
            Unit u = unitsNew.get(i);
            int x = u.getPosition().x;
            int y = u.getPosition().y;
            Types.TERRAIN t = boardNew.getTerrainAt(x, y);
            Types.RESOURCE r = boardNew.getResourceAt(x, y);
            int cityId = boardNew.getCityIdAt(x, y);
            if (!u.isFresh()) {
                if (t == Types.TERRAIN.VILLAGE || r == Types.RESOURCE.RUINS || (t == Types.TERRAIN.CITY && cityId != -1 && ((City) boardNew.getActor(cityId)).getTribeId() != playerId)) {
                    unitPositionRewardNew += 10;
                }
            }
        }

        entropy += production > gsOld.getTribeProduction(playerId) ? 1 : 0;
        entropy += technologies > gsOld.getTribeTechTree(playerId).getNumResearched() ? 1 : 0;
        entropy += score > gsOld.getScore(playerId) ? 1 : 0;
        entropy += cities > gsOld.getCities(playerId).size() ? 1 : 0;
        entropy += units > gsOld.getUnits(playerId).size() ? 1 : 0;
        entropy += kills > gsOld.getNKills(playerId) ? 1 : 0;
        entropy += unitPositionRewardNew > unitPositionRewardOld ? 1 : 0;

        if (Constants.REMOVE_FISHING) {
            core.TechnologyTree ttOld = gsOld.getTribeTechTree(playerId);
            core.TechnologyTree ttNew = gsNew.getTribeTechTree(playerId);
            if (ttNew.isResearched(Types.TECHNOLOGY.FISHING) && !ttOld.isResearched(Types.TECHNOLOGY.FISHING)) entropy -= 1;
            if (ttNew.isResearched(Types.TECHNOLOGY.SAILING) && !ttOld.isResearched(Types.TECHNOLOGY.SAILING)) entropy -= 1;
            if (ttNew.isResearched(Types.TECHNOLOGY.WHALING) && !ttOld.isResearched(Types.TECHNOLOGY.WHALING)) entropy -= 1;
            if (ttNew.isResearched(Types.TECHNOLOGY.NAVIGATION) && !ttOld.isResearched(Types.TECHNOLOGY.NAVIGATION)) entropy -= 1;
            if (ttNew.isResearched(Types.TECHNOLOGY.AQUATISM) && !ttOld.isResearched(Types.TECHNOLOGY.AQUATISM)) entropy -= 1;
        }

        return entropy;
    }
}
