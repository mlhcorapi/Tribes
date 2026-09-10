package core.actions.cityactions;

import core.TribesConfig;
import core.Types;
import core.actions.Action;
import core.actors.Tribe;
import core.game.Board;
import core.game.GameState;
import core.actors.Actor;
import core.actors.City;
import utils.Vector2d;

import java.util.LinkedList;

public class ResourceGathering extends CityAction
{
    private Types.RESOURCE resource;

    public ResourceGathering(int cityId)
    {
        super(Types.ACTION.RESOURCE_GATHERING);
        super.cityId = cityId;
    }

    public void setResource(Types.RESOURCE resource) {this.resource = resource;}
    public Types.RESOURCE getResource() {
        return resource;
    }

    @Override
    public boolean isFeasible(final GameState gs)
    {
        // A stale/mutated cityId can resolve to a non-City actor (e.g. a SuperUnit, since actor
        // ids are shared) or to nothing at all. Treat that as infeasible rather than throwing.
        City city = gs.getActor(this.cityId, City.class);
        if (city == null) return false;
        Board b = gs.getBoard();
        Tribe t = b.getTribe(city.getTribeId());

        // Check if resource can be gathered
        if(b.getResourceAt(targetPos.x, targetPos.y) == this.resource && t.getStars() >= this.resource.getCost()){
            switch (this.resource){
                case ANIMAL:
                    return t.getTechTree().isResearched(Types.TECHNOLOGY.HUNTING);
                case FISH:
                    return t.getTechTree().isResearched(Types.TECHNOLOGY.FISHING);
                case WHALES:
                    return t.getTechTree().isResearched(Types.TECHNOLOGY.WHALING);
                case FRUIT:
                    return t.getTechTree().isResearched(Types.TECHNOLOGY.ORGANIZATION);
            }
        }
        return false;
    }

    @Override
    public Action copy() {
        ResourceGathering res = new ResourceGathering(this.cityId);
        res.setResource(this.resource);
        res.setTargetPos(targetPos.copy());
        return res;
    }

    public String toString()
    {
        // targetPos is part of this action's identity (one action per resource TILE; see the
        // factory and isFeasible). It must be in the string: the EMCTS-family agents key
        // sequences by toString (seqKey / UniqueSequences), and omitting the tile made two
        // same-type gathers at different tiles collide (verified by ActionKeyCollisionProbe).
        return "RESOURCE_GATHERED by city " + this.cityId + " at " + targetPos + " : " + resource.toString();
    }

    public boolean equals(Object o) {
        if(!(o instanceof ResourceGathering))
            return false;

        ResourceGathering other = (ResourceGathering) o;

        return super.equals(other) && resource == other.resource;
    }
}
