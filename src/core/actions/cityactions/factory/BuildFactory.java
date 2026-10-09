package core.actions.cityactions.factory;

import core.TechnologyTree;
import core.Types;
import core.actions.Action;
import core.actions.ActionFactory;
import core.actions.cityactions.Build;
import core.actions.tribeactions.ResearchTech;
import core.actors.Actor;
import core.actors.City;
import core.actors.Tribe;
import core.game.Board;
import core.game.GameState;
import utils.Vector2d;

import java.util.LinkedList;

public class BuildFactory implements ActionFactory {

    // MAGAT: values() clones the array on every call.
    private static final Types.BUILDING[] BUILDINGS = Types.BUILDING.values();

    @Override
    public LinkedList<Action> computeActionVariants(final Actor actor, final GameState gs) {

        City city = (City) actor;
        LinkedList<Action> actions = new LinkedList<>();
        Board board = gs.getBoard();
        LinkedList<Vector2d> tiles = board.getCityTiles(city.getActorId());

        // MAGAT: one probe for the feasibility tests (isFeasible reads an action and changes nothing); an
        // action is created only for a feasible building, as before.
        Build probe = new Build(city.getActorId());

        for(Vector2d tile : tiles){
            //check if tile is empty (the same for every building)
            if(board.getBuildingAt(tile.x, tile.y) != null) continue;
            probe.setTargetPos(tile);
            for(Types.BUILDING building: BUILDINGS){
                probe.setBuildingType(building);
                if (probe.isFeasible(gs)) {
                    Build action = new Build(city.getActorId());
                    action.setBuildingType(building);
                    action.setTargetPos(tile.copy());
                    actions.add(action);
                }
            }
        }
        return actions;
    }

}
