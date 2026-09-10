import core.Types;
import core.actions.Action;
import core.actions.cityactions.Build;
import core.actions.cityactions.BurnForest;
import core.actions.cityactions.CityAction;
import core.actions.cityactions.ClearForest;
import core.actions.cityactions.Destroy;
import core.actions.cityactions.GrowForest;
import core.actions.cityactions.LevelUp;
import core.actions.cityactions.ResourceGathering;
import core.actions.cityactions.Spawn;
import core.actions.unitactions.UnitAction;
import core.actors.Actor;
import core.actors.City;
import core.actors.units.Unit;
import core.game.Game;
import core.game.GameState;
import org.junit.Test;
import players.Agent;
import players.DoNothingAgent;
import players.ExperimentalEMCTS.CrossoverExpansionAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import utils.Vector2d;

import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * Regression test for the intermittent "cannot cast" / NullPointerException crashes.
 *
 * Actor ids come from a single per-board counter shared by units and cities, and Examine /
 * LevelUp consume the game state's Random to decide whether they create an actor at all. Because
 * {@link GameState#copy()} re-seeds that Random, replaying one action sequence on two copies of
 * the same state can hand the same id to actors of different kinds. Actions carry raw ids, so a
 * planned or recombined sequence routinely reaches a state where one of its ids resolves to
 * nothing, or to an actor of the wrong kind.
 *
 * isFeasible is the predicate that is supposed to answer "does this action apply here". It must
 * answer false for such an action, never throw - and advancing the state with it must be a no-op
 * rather than a crash.
 */
public class StaleActorIdTest {

    private static GameState midGameState() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.num_fmcalls = 400;
        params.heuristic_method = params.SIMPLE_HEURISTIC;

        ArrayList<Agent> players = new ArrayList<>();
        players.add(new CrossoverExpansionAgent(1234L, params));
        players.add(new DoNothingAgent(1234L));

        core.Constants.MAX_TURNS_CAPITALS = 40;
        Game game = new Game();
        game.init(players, 90210L, new Types.TRIBE[]{Types.TRIBE.XIN_XI, Types.TRIBE.IMPERIUS},
                  4321L, Types.GAME_MODE.CAPITALS);
        return game.captureAtTickAsTurnStart(8);
    }

    /** An id that no actor in this state uses. */
    private static int unusedActorId(GameState gs) {
        return gs.getBoard().getActorIDcounter() + 500;
    }

    private static int anyIdOf(GameState gs, Class<?> kind) {
        for (int id = 1; id <= gs.getBoard().getActorIDcounter(); id++) {
            Actor a = gs.getActor(id);
            if (kind.isInstance(a)) return id;
        }
        return -1;
    }

    /**
     * Every unit action, pointed at a city id and at an id nobody owns, must answer false rather
     * than throw - and advancing with it must leave the state alone instead of crashing.
     */
    @Test
    public void unitActionsSurviveIdsThatAreNotUnits() {
        GameState gs = midGameState();
        int cityId = anyIdOf(gs, City.class);
        assertTrue("test needs a city in the captured state", cityId > 0);

        int checked = 0;
        for (Action a : gs.getAllUnitActions()) {
            if (!(a instanceof UnitAction)) continue;
            for (int badId : new int[]{cityId, unusedActorId(gs)}) {
                UnitAction stale = (UnitAction) a.copy();
                stale.setUnitId(badId);
                assertFalse(stale + " with a non-unit id must be infeasible", stale.isFeasible(gs));

                GameState probe = gs.copy();
                probe.advance(stale, true);   // must not throw
                checked++;
            }
        }
        assertTrue("test needs unit actions to exercise", checked > 0);
    }

    /**
     * The originally observed crash: a BUILD whose cityId resolves to a SuperUnit, because an
     * Examine earlier in the replayed sequence rolled a SuperUnit on this copy and took the id
     * the city had on the copy the sequence was built from.
     */
    @Test
    public void cityActionsSurviveIdsThatAreNotCities() {
        GameState gs = midGameState();
        int unitId = anyIdOf(gs, Unit.class);
        assertTrue("test needs a unit in the captured state", unitId > 0);

        int checked = 0;
        for (int badId : new int[]{unitId, unusedActorId(gs)}) {
            for (CityAction stale : cityActionsFor(badId)) {
                assertFalse(stale + " with a non-city id must be infeasible", stale.isFeasible(gs));

                GameState probe = gs.copy();
                probe.advance(stale, true);   // must not throw
                checked++;
            }
            // and whatever the state itself offers, re-pointed at the bad id
            for (Action a : gs.getAllCityActions()) {
                Action copy = a.copy();
                if (!(copy instanceof CityAction)) continue;
                CityAction stale = (CityAction) copy;
                stale.setCityId(badId);
                assertFalse(stale + " with a non-city id must be infeasible", stale.isFeasible(gs));

                GameState probe = gs.copy();
                probe.advance(stale, true);   // must not throw
                checked++;
            }
        }
        assertTrue("test needs city actions to exercise", checked > 0);
    }

    /** One of each city action, all pointed at the given (wrong-kind or unused) actor id. */
    private static ArrayList<CityAction> cityActionsFor(int cityId) {
        ArrayList<CityAction> out = new ArrayList<>();
        Vector2d pos = new Vector2d(1, 1);

        Build build = new Build(cityId);
        build.setBuildingType(Types.BUILDING.FARM);
        build.setTargetPos(pos.copy());
        out.add(build);

        Build monument = new Build(cityId);
        monument.setBuildingType(Types.BUILDING.EMPERORS_TOMB);
        monument.setTargetPos(pos.copy());
        out.add(monument);

        LevelUp levelUp = new LevelUp(cityId);
        levelUp.setBonus(Types.CITY_LEVEL_UP.WORKSHOP);
        out.add(levelUp);

        Spawn spawn = new Spawn(cityId);
        spawn.setUnitType(Types.UNIT.WARRIOR);
        out.add(spawn);

        ResourceGathering gather = new ResourceGathering(cityId);
        gather.setResource(Types.RESOURCE.ANIMAL);
        gather.setTargetPos(pos.copy());
        out.add(gather);

        Destroy destroy = new Destroy(cityId);
        destroy.setTargetPos(pos.copy());
        out.add(destroy);

        BurnForest burn = new BurnForest(cityId);
        burn.setTargetPos(pos.copy());
        out.add(burn);

        ClearForest clear = new ClearForest(cityId);
        clear.setTargetPos(pos.copy());
        out.add(clear);

        GrowForest grow = new GrowForest(cityId);
        grow.setTargetPos(pos.copy());
        out.add(grow);

        return out;
    }

    /** The typed lookup itself: right kind through, wrong kind and missing id as null. */
    @Test
    public void typedActorLookupRejectsTheWrongKind() {
        GameState gs = midGameState();
        int cityId = anyIdOf(gs, City.class);
        int unitId = anyIdOf(gs, Unit.class);
        assertTrue(cityId > 0 && unitId > 0);

        assertNotNull(gs.getActor(cityId, City.class));
        assertNotNull(gs.getActor(unitId, Unit.class));
        assertNull("a city id must not resolve as a unit", gs.getActor(cityId, Unit.class));
        assertNull("a unit id must not resolve as a city", gs.getActor(unitId, City.class));
        assertNull(gs.getActor(unusedActorId(gs), City.class));
        assertNull(gs.getActor(unusedActorId(gs), Unit.class));
    }
}
