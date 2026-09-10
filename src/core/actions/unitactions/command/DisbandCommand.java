package core.actions.unitactions.command;

import core.Types;
import core.actions.Action;
import core.actions.ActionCommand;
import core.actions.unitactions.Convert;
import core.actions.unitactions.Disband;
import core.actors.City;
import core.actors.Tribe;
import core.actors.units.Unit;
import core.game.Board;
import core.game.GameState;

public class DisbandCommand implements ActionCommand {

    @Override
    public boolean execute(Action a, GameState gs) {
        Disband action = (Disband)a;
        int unitId = action.getUnitId();

        if(action.isFeasible(gs))
        {
            Unit unit = gs.getActor(unitId, Unit.class);
            Board b = gs.getBoard();
            Tribe t = gs.getTribe(unit.getTribeId());
            //null when the unit belongs to the tribe rather than a city; removeUnitFromCity handles it.
            City c = b.getActor(unit.getCityId(), City.class);

            int starsGained = (int) (unit.COST / 2.0); //half, rounded down
            t.addStars(starsGained);
            b.removeUnitFromBoard(unit);
            b.removeUnitFromCity(unit, c, t);
            t.subtractScore(unit.getType().getPoints());
            return true;
        }

        return false;
    }
}
