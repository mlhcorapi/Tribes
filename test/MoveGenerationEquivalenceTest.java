import core.Types;
import core.actions.Action;
import core.actions.cityactions.Build;
import core.actions.cityactions.factory.BuildFactory;
import core.actions.unitactions.StepMove;
import core.actors.Actor;
import core.actors.City;
import core.actors.Tribe;
import core.actors.units.Unit;
import core.game.Board;
import core.game.Game;
import core.game.GameState;
import org.junit.Test;
import players.Agent;
import players.DoNothingAgent;
import players.ExperimentalEMCTS.CrossoverExpansionAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import utils.Vector2d;
import utils.graph.PathNode;
import utils.graph.Pathfinder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * The faster legal-action generation (MAGAT's tools/patch_engine_perf.py) returns exactly what the original
 * code returned, in the same order. Seeded games are captured at several turns and played forward with
 * random legal actions; at every state, for every unit of every tribe, the destinations Dijkstra finds
 * (position and cost, in order) and the A* paths to some of them are compared with the original
 * Pathfinder and StepMove (ReferencePathfinder, ReferenceStepMove: verbatim copies); every city's build
 * actions with the original BuildFactory loop; and every tile's unit with the original hash lookup.
 */
public class MoveGenerationEquivalenceTest {

    private static final long[] GAME_SEEDS = {90210L, 1234L, 777L};
    private static final Types.GAME_MODE[] MODES = {Types.GAME_MODE.CAPITALS, Types.GAME_MODE.SCORE};
    private static final int[] CAPTURE_TICKS = {4, 16, 30};
    private static final int PLAYOUT_ACTIONS = 80;

    private int states, units, paths, cities, builds;
    private final java.util.TreeSet<String> buildingTypes = new java.util.TreeSet<>();

    @Test
    public void legalActionGenerationIsUnchanged() throws Exception {
        for (long seed : GAME_SEEDS)
            for (Types.GAME_MODE mode : MODES)
                for (int tick : CAPTURE_TICKS) {
                    GameState gs = capture(seed, mode, tick);
                    Random rnd = new Random(seed * 31 + tick);
                    for (int step = 0; step <= PLAYOUT_ACTIONS && !gs.isGameOver(); step++) {
                        compareEverything(gs, "seed " + seed + " " + mode + " tick " + tick + " step " + step);
                        ArrayList<Action> actions = gs.getAllAvailableActions();
                        if (actions.isEmpty()) break;
                        gs.advance(actions.get(rnd.nextInt(actions.size())), true);
                    }
                }
        System.out.println("MoveGenerationEquivalenceTest: " + states + " states, " + units + " units' destinations, "
                + paths + " paths, " + cities + " cities' build actions (" + builds + " actions of " + buildingTypes.size()
                + " building types " + buildingTypes + "), every tile's unit");
        assertTrue("the playouts reached enough states to mean something", states > 1000 && units > 3000 && builds > 500);
        assertTrue("and enough kinds of building: " + buildingTypes, buildingTypes.size() >= 4);
    }

    private static GameState capture(long seed, Types.GAME_MODE mode, int tick) {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.num_fmcalls = 400;
        params.heuristic_method = params.SIMPLE_HEURISTIC;
        ArrayList<Agent> players = new ArrayList<>();
        players.add(new CrossoverExpansionAgent(seed, params));
        players.add(new CrossoverExpansionAgent(seed + 1, params));
        core.Constants.MAX_TURNS_CAPITALS = 40;
        Game game = new Game();
        game.init(players, seed, new Types.TRIBE[]{Types.TRIBE.XIN_XI, Types.TRIBE.IMPERIUS}, seed + 4321L, mode);
        return game.captureAtTickAsTurnStart(tick);
    }

    private void compareEverything(GameState gs, String where) throws Exception {
        states++;
        Board board = gs.getBoard();
        compareUnitsOnTiles(board, where);
        for (Tribe tribe : gs.getTribes()) {
            List<Integer> unitIds = new ArrayList<>(tribe.getExtraUnits());
            for (int cityId : tribe.getCitiesID()) {
                Actor c = gs.getActor(cityId);
                if (!(c instanceof City)) continue;
                unitIds.addAll(((City) c).getUnitsID());
                compareBuilds(gs, (City) c, where);
            }
            for (int unitId : unitIds) {
                Actor u = gs.getActor(unitId);
                if (u instanceof Unit) comparePaths(gs, (Unit) u, where);
            }
        }
    }

    private void comparePaths(GameState gs, Unit unit, String where) {
        units++;
        String at = where + ", unit " + unit.getActorId() + " at " + unit.getPosition();
        ArrayList<PathNode> now = new Pathfinder(unit.getPosition(), new StepMove(gs, unit)).findPaths();
        ArrayList<PathNode> was = new ReferencePathfinder(unit.getPosition(), new ReferenceStepMove(gs, unit)).findPaths();
        assertEquals(at + ": destinations", describe(was), describe(now));
        for (int i = 0; i < was.size(); i += Math.max(1, was.size() / 3)) {
            Vector2d goal = was.get(i).getPosition();
            ArrayList<PathNode> pathNow = new Pathfinder(unit.getPosition(), new StepMove(gs, unit)).findPathTo(goal);
            ArrayList<PathNode> pathWas = new ReferencePathfinder(unit.getPosition(), new ReferenceStepMove(gs, unit)).findPathTo(goal);
            assertEquals(at + ": path to " + goal, describe(pathWas), describe(pathNow));
            paths++;
        }
    }

    private static String describe(List<PathNode> nodes) {
        if (nodes == null) return "no path";
        StringBuilder sb = new StringBuilder();
        for (PathNode n : nodes) sb.append(n.getX()).append(',').append(n.getY()).append('@').append(n.getTotalCost()).append(' ');
        return sb.toString();
    }

    private void compareBuilds(GameState gs, City city, String where) {
        cities++;
        List<String> now = new ArrayList<>(), was = new ArrayList<>();
        for (Action a : new BuildFactory().computeActionVariants(city, gs)) now.add(a.toString());
        // The original BuildFactory.computeActionVariants loop.
        Board board = gs.getBoard();
        LinkedList<Vector2d> tiles = board.getCityTiles(city.getActorId());
        for (Vector2d tile : tiles) {
            for (Types.BUILDING building : Types.BUILDING.values()) {
                if (board.getBuildingAt(tile.x, tile.y) == null) {
                    Build action = new Build(city.getActorId());
                    action.setBuildingType(building);
                    action.setTargetPos(tile.copy());
                    if (action.isFeasible(gs)) {
                        was.add(action.toString());
                        buildingTypes.add(building.name());
                    }
                }
            }
        }
        builds += was.size();
        assertEquals(where + ", city " + city.getActorId() + ": build actions", was, now);
    }

    @SuppressWarnings("unchecked")
    private static void compareUnitsOnTiles(Board board, String where) throws Exception {
        Field unitsField = Board.class.getDeclaredField("units"), actorsField = Board.class.getDeclaredField("gameActors");
        unitsField.setAccessible(true);
        actorsField.setAccessible(true);
        int[][] onTile = (int[][]) unitsField.get(board);
        HashMap<Integer, Actor> actors = (HashMap<Integer, Actor>) actorsField.get(board);
        for (int x = 0; x < board.getSize(); x++)
            for (int y = 0; y < board.getSize(); y++)
                assertSame(where + ", unit at " + x + "," + y, actors.get(onTile[x][y]), board.getUnitAt(x, y));
    }
}
