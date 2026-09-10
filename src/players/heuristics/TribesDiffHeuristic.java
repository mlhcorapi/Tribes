package players.heuristics;

import core.actors.City;
import core.game.GameState;
import utils.stats.StatSummary;

import core.actors.units.Unit;
import java.util.ArrayList;

import core.Constants;
import core.Types;

/**
 * Compares some important game metrics between an old gamestate and a new gamestate. Returns a score called 'entropy'.
 * An entropy of 0 means the gamestates are identical in terms of the metrics being compared, positive entropy means
 * that some metrics in the new gamestate have increased.
 */
public class TribesDiffHeuristic implements StateHeuristic {

    private int playerID;
    ArrayList<Integer> allIds;

    public TribesDiffHeuristic(int playerID, ArrayList<Integer> allIds)
    {
        this.allIds = allIds;
        this.playerID = playerID;
    }

    @Override
    public double evaluateState(GameState gsOld, GameState gsNew) {
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
        double difference = 0;
        int connections = gsNew.getTribe(playerID).getConnectedCities().size();
        boolean [][] visibility = gsNew.getVisibilityMap();
        int visCountNew = 0;
        for (boolean[] booleans : visibility)
            for (boolean aBoolean : booleans) visCountNew += aBoolean ? 1 : 0;

        visibility = gsOld.getVisibilityMap();
        int visCountOld = 0;
        for (boolean[] booleans : visibility)
            for (boolean aBoolean : booleans) visCountOld += aBoolean ? 1 : 0;


        difference += (connections - gsOld.getTribe(playerID).getConnectedCities().size());
        difference += (visCountNew - visCountOld);
        return difference;
    }

    private double score(GameState gsOld, GameState gsNew, int playerId)
    {
        //Metrics of the game that we want to maximise.
        int production = gsNew.getTribeProduction(playerId);
        int technologies = gsNew.getTribeTechTree(playerId).getNumResearched();
        int score = gsNew.getScore(playerId);
        int cities = gsNew.getCities(playerId).size();
        int sumCityLevelsNew = 0;
        for(City c : gsNew.getCities(playerId))
            sumCityLevelsNew += c.getLevel();
        int units = gsNew.getUnits(playerId).size();
        int kills = gsNew.getNKills(playerId);

        //compare with old metrics and calculate entropy.
        double difference = 0;

        int sumCityLevelsOld = 0;
        for(City c : gsOld.getCities(playerId))
            sumCityLevelsOld += c.getLevel();

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

        difference += 5 * (production - gsOld.getTribeProduction(playerId));
        difference += 4 * (technologies - gsOld.getTribeTechTree(playerId).getNumResearched());
        difference += 0.1 * (score - gsOld.getScore(playerId));
        difference += 4 * (cities - gsOld.getCities(playerId).size());
        difference += 2 * (units - gsOld.getUnits(playerId).size());
        difference += 3 * (kills - gsOld.getNKills(playerId));
        difference += 2 * (sumCityLevelsNew - sumCityLevelsOld);
        difference += (unitPositionRewardNew - unitPositionRewardOld);

        if (Constants.REMOVE_FISHING) {
            core.TechnologyTree ttOld = gsOld.getTribeTechTree(playerId);
            core.TechnologyTree ttNew = gsNew.getTribeTechTree(playerId);
            if (ttNew.isResearched(Types.TECHNOLOGY.FISHING) && !ttOld.isResearched(Types.TECHNOLOGY.FISHING)) difference -= 100;
            if (ttNew.isResearched(Types.TECHNOLOGY.SAILING) && !ttOld.isResearched(Types.TECHNOLOGY.SAILING)) difference -= 100;
            if (ttNew.isResearched(Types.TECHNOLOGY.WHALING) && !ttOld.isResearched(Types.TECHNOLOGY.WHALING)) difference -= 100;
            if (ttNew.isResearched(Types.TECHNOLOGY.NAVIGATION) && !ttOld.isResearched(Types.TECHNOLOGY.NAVIGATION)) difference -= 100;
            if (ttNew.isResearched(Types.TECHNOLOGY.AQUATISM) && !ttOld.isResearched(Types.TECHNOLOGY.AQUATISM)) difference -= 100;
        }

        return difference;
    }
}
