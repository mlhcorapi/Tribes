package core.actions.unitactions;

import core.Types;
import core.actions.Action;
import core.actions.cityactions.CityAction;
import core.game.GameState;
import core.actors.units.Unit;

public class Attack extends UnitAction
{
    private int targetId;

    public Attack (int unitId)
    {
        super(Types.ACTION.ATTACK);
        super.unitId = unitId;
    }

    public void setTargetId(int targetId) {this.targetId = targetId;}
    public int getTargetId() {
        return targetId;
    }

    @Override
    public boolean isFeasible(final GameState gs)
    {
        Unit target = gs.getActor(this.targetId, Unit.class);
        Unit attacker = gs.getActor(this.unitId, Unit.class);

        // Check both units still exist and that the attacker can attack
        if(target == null || attacker == null || !attacker.canAttack() || attacker.getType() == Types.UNIT.MIND_BENDER)
            return false;

        return unitInRange(attacker, target, gs.getBoard());
    }

    @Override
    public Action copy() {
        Attack attack = new Attack(this.unitId);
        attack.setTargetId(this.targetId);
        return attack;
    }

    public String toString() { return "ATTACK by unit " + this.unitId + " to unit " + this.targetId;}

    public boolean equals(Object o) {
        if(!(o instanceof Attack))
            return false;
        Attack other = (Attack) o;

        return super.equals(other) && targetId == other.targetId;
    }
}
