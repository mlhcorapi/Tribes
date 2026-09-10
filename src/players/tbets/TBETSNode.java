package players.tbets;

import core.actions.Action;
import core.game.GameState;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * A node in the TBETS search tree. Unlike the EMCTS family, where a node is a candidate action
 * SEQUENCE for the current turn and the tree deepens within one turn, a TBETS node is one COMPLETE
 * turn and the tree deepens across turns: the children of a node are candidate turns for whoever
 * plays next.
 *
 * <p>{@link #getState()} is the state AFTER this node's turn has been played (including its END_TURN,
 * so the active tribe is already the next one). The root is the degenerate case: an empty sequence
 * whose state is the state handed to the agent, standing in for "the turn that has just been played
 * against me".
 *
 * <p>{@code fitness} is always expressed from the searching agent's point of view, which is why the
 * backup is a minimax rather than an average: a node whose children are MY candidate turns takes
 * their max, a node whose children are the opponent's takes their min.
 */
public class TBETSNode {

    /** The turn played to reach this node. Empty for the root. */
    private final ArrayList<Action> sequence;
    /** State after {@link #sequence} (including its END_TURN). */
    private GameState state;

    private final TBETSNode parent;
    private final ArrayList<TBETSNode> children = new ArrayList<>();
    /** Canonical keys of this node's children, so a duplicate turn is rejected in O(1). */
    private final HashSet<String> childKeys = new HashSet<>();

    /** Plies from the root; also decides whose turn this node is (see {@link #isMine()}). */
    private final int depth;

    /** Heuristic value from the searching agent's point of view. */
    private double fitness;
    private boolean evaluated;

    /** {@code Explored} in the C#: this node has had its initial population of children generated. */
    private boolean explored;

    /** Game is over in {@link #getState()}, so this node can never have children. */
    private boolean leaf;

    /** {@code nDescendents}: subtree size, used as the visit count in UCB and progressive widening. */
    private int descendants;

    public TBETSNode(ArrayList<Action> sequence, TBETSNode parent, int depth) {
        this.sequence = sequence;
        this.parent = parent;
        this.depth = depth;
    }

    /**
     * Whether the turn recorded in this node was played by the searching agent. Odd depths are the
     * agent's turns because the root (depth 0) stands in for the opponent turn that preceded them.
     */
    public boolean isMine() {
        return (depth % 2) == 1;
    }

    /** Whether this node's CHILDREN would be the searching agent's turns. */
    public boolean childrenAreMine() {
        return ((depth + 1) % 2) == 1;
    }

    public ArrayList<Action> getSequence()   { return sequence; }
    public GameState getState()              { return state; }
    public void setState(GameState s)        { this.state = s; }
    public TBETSNode getParent()             { return parent; }
    public ArrayList<TBETSNode> getChildren(){ return children; }
    public int getDepth()                    { return depth; }
    public double getFitness()               { return fitness; }
    public boolean isEvaluated()             { return evaluated; }
    public boolean isExplored()              { return explored; }
    public void setExplored(boolean e)       { this.explored = e; }
    public boolean isLeaf()                  { return leaf; }
    public void setLeaf(boolean l)           { this.leaf = l; }
    public int getDescendants()              { return descendants; }

    public void setFitness(double f) {
        this.fitness = f;
        this.evaluated = true;
    }

    /** Registers a child under its canonical key. Returns false if a sibling already has that key. */
    public boolean addChild(TBETSNode child, String key) {
        if (!childKeys.add(key)) return false;
        children.add(child);
        return true;
    }

    /** Increments the subtree size on this node and every ancestor. */
    public void incDescendants() {
        TBETSNode n = this;
        while (n != null) {
            n.descendants++;
            n = n.parent;
        }
    }

    /**
     * Minimax backup: this node's fitness becomes the best (for whoever moves into its children)
     * value among its evaluated children. No-op while no child has been evaluated, mirroring the C#
     * {@code FitnessUpdate}. Returns true if the value changed, so the caller can stop propagating
     * once a level is unaffected.
     */
    public boolean backup() {
        if (leaf || children.isEmpty()) return false;

        boolean max = childrenAreMine();
        double best = max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        boolean any = false;
        for (TBETSNode c : children) {
            if (!c.evaluated) continue;
            any = true;
            if (max ? (c.fitness > best) : (c.fitness < best)) best = c.fitness;
        }
        if (!any) return false;

        boolean changed = !evaluated || best != fitness;
        fitness = best;
        evaluated = true;
        return changed;
    }

    @Override
    public String toString() {
        return "TBETSNode[depth=" + depth + ", actions=" + (sequence == null ? 0 : sequence.size())
                + ", fitness=" + String.format("%.3f", fitness) + ", children=" + children.size()
                + ", descendants=" + descendants + ", explored=" + explored + ", leaf=" + leaf + "]";
    }
}
