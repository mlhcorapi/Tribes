package players.portfolio.scripts;

import core.Types;
import core.actions.Action;
import core.actors.Actor;
import core.game.GameState;
import players.portfolio.scripts.utils.BuildingFunc;
import utils.Pair;

import java.util.ArrayList;
import java.util.Random;

import static core.Types.BUILDING.*;

public class BuildMonumentScr extends BaseScript {

    //Selects the action that builds a monument in the best possible place.

    private Random rnd;

    public BuildMonumentScr(Random rnd)
    {
        this.rnd = rnd;
    }

    @Override
    public Pair<Action, Double> process(GameState gs, Actor ac)
    {
        ArrayList<Action> candidate_actions = new ArrayList<>();
        double bestScore = Double.NEGATIVE_INFINITY;

        //Looks for the first monument that can be built in an idle space. Returns null if no idle spaces.
        Types.BUILDING[] targets = new Types.BUILDING[]{ALTAR_OF_PEACE, EMPERORS_TOMB, EYE_OF_GOD, GATE_OF_POWER,
                PARK_OF_FORTUNE, TOWER_OF_WISDOM, GRAND_BAZAR,};

        int numTies = 0;
        Action finalAction = null;
        for(Types.BUILDING target : targets){
            Pair<Action, Double> p = new BuildingFunc().buildInIdle(target, gs, actions, rnd);
            if(p != null) {
                Action buildMonument = p.getFirst();
                double score = p.getSecond();
                if (score > bestScore) {
                    bestScore = score;
                    numTies = 1;
                    finalAction = buildMonument;
                } else if (score == bestScore) {
                    numTies++;
                    if (rnd.nextInt(numTies) == 0) {
                        finalAction = buildMonument;
                    }
                }
            }
        }

        if(finalAction != null)
            return new Pair<>(finalAction, bestScore);
        return null;
    }

}
