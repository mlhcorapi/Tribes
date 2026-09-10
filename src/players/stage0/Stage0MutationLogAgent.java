package players.stage0;

import core.Types;
import core.actions.Action;
import core.actions.cityactions.Build;
import core.actions.cityactions.Spawn;
import core.actions.tribeactions.ResearchTech;
import core.actions.unitactions.UnitAction;
import core.actors.Actor;
import core.actors.units.Unit;
import core.game.GameState;
import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import players.ExperimentalEMCTS.ExperimentalEMCTSTreeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 0 of the action-bias line of work (see {@code analysis/STAGE0_SIGNAL_PLAN.md}):
 * OBSERVATION-ONLY instrumentation of the mutation operator. Plays exactly like
 * {@link ExperimentalEMCTSAgent} — same RNG draws in the same order, same forward-model calls,
 * same ComputeMeter charges — and additionally records one row per mutation event:
 * which action was removed, which was inserted, at what position, how much the greedy repair
 * rewrote, and the parent/child sequence values. The offline analysis
 * ({@code analysis/analyze_stage0.py}) uses those rows to decide whether per-action value
 * estimates are reliable enough WITHIN a single turn's search to be worth exploiting
 * (biased mutation / repair shortlisting), before any biased sampler is built.
 *
 * <p><b>Behavioural equivalence is the core contract of this class.</b> {@link #mutate} is a
 * verbatim copy of the base implementation with bookkeeping added between the copied lines; the
 * bookkeeping performs no {@code advance()}/{@code copy()}/{@code eval()} calls and consumes no
 * randomness, so a game played by this agent is move-for-move identical to one played by a plain
 * {@code ExperimentalEMCTSAgent} with the same seed. {@code Stage0SignalRunner verify} asserts
 * this on a real game before the data runs.
 *
 * <p>Crossover must stay OFF (both {@code useCrossoverExpansion} and
 * {@code useCrossoverBridgeBurning} false — the params defaults): with crossover off, every child
 * node is created by {@link #mutate} and immediately scored by {@link #eval}, which is the
 * pairing this class relies on to attach the child's value to the pending mutation record.
 * The constructor enforces it.
 */
public class Stage0MutationLogAgent extends ExperimentalEMCTSAgent {

    /** Per-event fields produced by this agent (the runner prefixes run metadata columns). */
    public static final String EVENT_FIELDS =
            "Tick,Episode,Iter,Burns,UnitsAtEvent,SeqLen,MutPos,NumAvailable,"
            + "RemovedType,RemovedMid,RemovedFull,InsertedType,InsertedMid,InsertedFull,"
            + "RepairKept,RepairReplaced,InsertedSurvived,ChildSeqLen,Duplicate,"
            + "ParentValue,ChildValue,"
            + "ExpandedDepth,ExpandedChildren,ExpandedAge,IncumbentMoved";

    /** Hard cap so a pathological game cannot exhaust the heap; overflow is counted, not fatal. */
    private static final int MAX_EVENTS_PER_GAME = 3_000_000;

    private final ArrayList<String> eventRows = new ArrayList<>();
    private int droppedEvents = 0;   // rows beyond MAX_EVENTS_PER_GAME
    private int orphanEvents = 0;    // pending mutations never completed by eval() (expect 0)

    // Per-turn counters, reset by randomActions() (called exactly once at the start of planTurn).
    private int curTick = -1;
    private int episodeIdx = -1;     // 0-based planning-episode index within this game
    private int iterInTurn = 0;      // mutation events so far this turn
    private int burnsInTurn = 0;     // bridge burns executed so far this turn

    // The mutation awaiting its child's evaluation. planTurn always calls eval(child) directly
    // after mutate(child), so at most one is pending at a time.
    private ExperimentalEMCTSTreeNode pendingChild;
    private ExperimentalEMCTSTreeNode pendingParent;
    private StringBuilder pendingRow;
    private int pendingDepth, pendingChildren, pendingAge;

    // ---- selection instrumentation (R9) --------------------------------------------------------
    // The question these answer is whether mutation payoff depends on WHERE in the tree the search
    // is expanding — which could not be asked before, because the pre-fix descent made every
    // expansion a fresh leaf and the notion of a node being expanded repeatedly did not exist.
    //
    // NOTE: times_visited is deliberately NOT logged. It is incremented exactly once per node, in
    // planTurn, right after that node is created and scored — so it is 1 for every node in the tree
    // and a column of it would carry no information. The meaningful analogues are how many children
    // the expanded node has accumulated, and how long it has been the expansion target.
    private ExperimentalEMCTSTreeNode lastExpanded;
    private int expandedDepth;       // depth from the root; recomputed only when the target changes
    private int expandedAge;         // mutations spawned from this target so far

    public Stage0MutationLogAgent(long seed, ExperimentalEMCTSParams params) {
        super(seed, params);
        if (params.useCrossoverExpansion || params.useCrossoverBridgeBurning)
            throw new IllegalArgumentException(
                    "Stage0MutationLogAgent requires both crossover operators OFF");
    }

    /** All mutation-event rows of the game so far, in {@link #EVENT_FIELDS} order. */
    public List<String> getEventRows() { return eventRows; }
    public int getDroppedEvents() { return droppedEvents; }
    public int getOrphanEvents() { return orphanEvents; }

    // ------------------------------------------------------------------ per-turn hooks

    /** planTurn's first act is building the root here, so this doubles as the turn-start hook. */
    @Override
    protected ExperimentalEMCTSTreeNode randomActions(GameState gs) {
        curTick = gs.getTick();
        episodeIdx++;
        iterInTurn = 0;
        burnsInTurn = 0;
        if (pendingChild != null) orphanEvents++;
        pendingChild = null; pendingParent = null; pendingRow = null;
        lastExpanded = null; expandedDepth = 0; expandedAge = 0;
        return super.randomActions(gs);
    }

    @Override
    protected void recordBridgeBurn(int bridgeBurningPhaseNumber, double bestSequenceValue,
                                    long turnStartTime, int tick) {
        super.recordBridgeBurn(bridgeBurningPhaseNumber, bestSequenceValue, turnStartTime, tick);
        burnsInTurn++;
    }

    // ------------------------------------------------------------------ instrumented operators

    /**
     * Verbatim copy of {@link ExperimentalEMCTSAgent#mutate} with observation-only bookkeeping.
     * Any change to the base implementation must be mirrored here (guarded by
     * {@code Stage0SignalRunner verify}).
     */
    @Override
    protected ExperimentalEMCTSTreeNode mutate(ExperimentalEMCTSTreeNode node, GameState gs) {
        GameState clone = gs.copy();
        ArrayList<Action> seq = node.getSequence();
        int moveToMutate = seq.isEmpty() ? 0 : m_rnd.nextInt(seq.size());
        ArrayList<Action> newSeq = new ArrayList<>();
        for (int i = 0; i < moveToMutate; i++) {
            advance(gs, seq.get(i));
            newSeq.add(seq.get(i));
        }
        ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);

        Action inserted = null;
        if (!allAvailableActions.isEmpty()) {
            inserted = allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
            newSeq.add(inserted);
        }

        // ---- bookkeeping (reads only; no advances/copies/evals, no RNG) ----
        // Keys are resolved against the PREFIX state `gs` — the state both the removed and the
        // inserted action apply in — because unit ids in toString() only mean something there.
        Action removed = (moveToMutate < seq.size()) ? seq.get(moveToMutate) : null;
        int numAvailable = allAvailableActions.size();
        int seqLen = seq.size();
        // Selection state, read before this mutation's child is attached. Depth is recomputed only
        // when the expansion target changes, which under an exploitative policy is only on an
        // improvement — so the parent walk costs nothing per mutation.
        if (node != lastExpanded) {
            lastExpanded = node;
            expandedAge = 0;
            expandedDepth = 0;
            for (ExperimentalEMCTSTreeNode p = node.getParent(); p != null; p = p.getParent())
                expandedDepth++;
        }
        int expandedChildren = node.getChildren().size();
        int ageAtEvent = expandedAge++;
        String remType = typeKey(removed), remMid = midKey(removed, gs), remFull = fullKey(removed);
        String insType = typeKey(inserted), insMid = midKey(inserted, gs), insFull = fullKey(inserted);
        // ---- end bookkeeping ----

        for (int i = moveToMutate + 1; i < seq.size(); i++) {
            newSeq.add(seq.get(i));
        }
        newSeq = truncateAtEndTurn(repair(newSeq, clone));
        ExperimentalEMCTSTreeNode newNode = new ExperimentalEMCTSTreeNode(newSeq, node);
        node.addChild(newNode);
        int nChildren = node.getChildren().size();
        if (nChildren > maxChildrenOnANode) maxChildrenOnANode = nChildren;

        // ---- bookkeeping: assemble the row up to the fields only known after eval() ----
        // Repair keeps the surviving Action OBJECTS, so identity tells us whether the inserted
        // action made it into the child at its position.
        boolean survived = inserted != null && newSeq.size() > moveToMutate
                && newSeq.get(moveToMutate) == inserted;
        StringBuilder row = new StringBuilder(192);
        row.append(curTick).append(',').append(episodeIdx).append(',')
           .append(iterInTurn).append(',').append(burnsInTurn).append(',');
        // UnitsAtEvent is filled at eval time so it includes this child's own build+eval cost.
        row.append('{').append('}');   // placeholder replaced in eval()
        row.append(',').append(seqLen).append(',').append(moveToMutate).append(',')
           .append(numAvailable).append(',')
           .append(remType).append(',').append(remMid).append(',').append(remFull).append(',')
           .append(insType).append(',').append(insMid).append(',').append(insFull).append(',')
           .append(lastRepairKept).append(',').append(lastRepairReplaced).append(',')
           .append(survived ? 1 : 0).append(',').append(newSeq.size()).append(',');
        if (pendingChild != null) orphanEvents++;
        pendingChild = newNode;
        pendingParent = node;
        pendingRow = row;
        pendingDepth = expandedDepth; pendingChildren = expandedChildren; pendingAge = ageAtEvent;
        iterInTurn++;
        // ---- end bookkeeping ----

        return newNode;
    }

    /** Completes the pending mutation record once the child has been scored. */
    @Override
    public void eval(GameState gs, ExperimentalEMCTSTreeNode node) {
        boolean match = pendingChild != null && node == pendingChild;
        // Read BEFORE super.eval registers this child's key.
        boolean duplicate = match && uniqueSeqKeys.contains(seqKey(node.getSequence()));
        super.eval(gs, node);
        if (!match) return;

        // planTurn applies exactly this test right after eval returns, so evaluating it here reads
        // the incumbent before it moves and records the same decision without duplicating state.
        boolean incumbentMoved = incumbent != null && node.getValue() > incumbent.getValue();

        StringBuilder row = pendingRow;
        ExperimentalEMCTSTreeNode parent = pendingParent;
        pendingChild = null; pendingParent = null; pendingRow = null;

        if (eventRows.size() >= MAX_EVENTS_PER_GAME) { droppedEvents++; return; }
        int ph = row.indexOf("{}");
        row.replace(ph, ph + 2, String.valueOf(
                Math.round(core.game.ComputeMeter.computeUnits())));
        row.append(duplicate ? 1 : 0).append(',')
           .append(round4(parent.getValue())).append(',')
           .append(round4(node.getValue())).append(',')
           .append(pendingDepth).append(',')
           .append(pendingChildren).append(',')
           .append(pendingAge).append(',')
           .append(incumbentMoved ? 1 : 0);
        eventRows.add(row.toString());
    }

    // ------------------------------------------------------------------ action keys

    /** Coarse key: the {@link Types.ACTION} enum name (~25 values). */
    private static String typeKey(Action a) {
        return a == null ? "NONE" : a.getActionType().name();
    }

    /**
     * Mid key: action type qualified by the identity-free parameter that most plausibly carries
     * value — the acting unit's TYPE for unit actions, the building/unit/tech for BUILD/SPAWN/
     * RESEARCH_TECH. Unit ids and coordinates are deliberately absent so the key generalises
     * across actors and positions within a turn.
     */
    private static String midKey(Action a, GameState gs) {
        if (a == null) return "NA";
        try {
            if (a instanceof UnitAction) {
                Actor act = gs.getActor(((UnitAction) a).getUnitId());
                String unit = (act instanceof Unit) ? ((Unit) act).getType().name() : "?";
                return a.getActionType().name() + ":" + unit;
            }
            if (a instanceof Build)        return "BUILD:" + ((Build) a).getBuildingType();
            if (a instanceof Spawn)        return "SPAWN:" + ((Spawn) a).getUnitType();
            if (a instanceof ResearchTech) return "RESEARCH_TECH:" + ((ResearchTech) a).getTech();
        } catch (Exception ignored) { }
        return a.getActionType().name();
    }

    /** Fine key: the action's full content toString(), CSV-sanitised. */
    private static String fullKey(Action a) {
        if (a == null) return "NA";
        String s = a.toString();
        // toString may contain the CSV delimiter (e.g. Vector2d "(x, y)") — sanitise.
        return s.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }

    private static String round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        return String.valueOf(Math.round(v * 10000.0) / 10000.0);
    }
}
