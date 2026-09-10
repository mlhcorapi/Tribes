package players.ExperimentalEMCTS;

import core.Types;
import core.actions.Action;
import core.actions.cityactions.CityAction;
import core.actions.unitactions.UnitAction;
import core.actors.Actor;
import core.actors.City;
import core.actors.units.Unit;
import core.game.GameState;
import players.Agent;
import players.heuristics.StateHeuristic;
import utils.ElapsedCpuTimer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import core.data.dto.CrossoverEventData;
import core.data.dto.TurnData;
import core.data.dto.BridgeBurnEventData;


public class ExperimentalEMCTSAgent extends Agent {

    public ArrayList<CrossoverEventData> crossoverDataList = new ArrayList<>();
    public ArrayList<TurnData> turnDataList = new ArrayList<>();
    public ArrayList<BridgeBurnEventData> bridgeBurnDataList = new ArrayList<>();

    @Override
    public List<CrossoverEventData> getCrossoverDataLog() {
        return crossoverDataList;
    }

    @Override
    public List<TurnData> getTurnDataLog() {
        return turnDataList;
    }

    @Override
    public List<BridgeBurnEventData> getBridgeBurnDataLog() {
        return bridgeBurnDataList;
    }

    /**
     * Records a bridge-burn (root promotion) event. Shared by every experimental
     * variant so that both standard and crossover bridge burning are captured.
     * gameId is a placeholder (-1) and reassigned by the main thread in ExperimentRunner.
     */
    protected void recordBridgeBurn(int bridgeBurningPhaseNumber, double bestSequenceValue, long turnStartTime, int tick) {
        long expansionPhaseTimestamp = System.currentTimeMillis() - turnStartTime;
        bridgeBurnDataList.add(new BridgeBurnEventData(
                -1, tick, expansionPhaseTimestamp, bridgeBurningPhaseNumber, bestSequenceValue, this.fmCallsCount,
                // v6: SEARCH compute-units spent so far this turn (init excluded — the meter was reset
                // after initialization). Gives the per-phase budget trajectory and makes within-turn
                // drift in the advance/copy/eval mix observable.
                core.game.ComputeMeter.computeUnits()
        ));
    }

    // Per-turn planning state: the engine calls act() once per action, so we plan the
    // entire turn once (a single phased search) and replay the cached best action
    // sequence across the turn's subsequent act() calls instead of re-searching.
    protected ArrayList<Action> turnPlan = new ArrayList<>();
    protected int plannedTick = -1;
    // Wall-clock baseline for event timestamps: the start of the turn (set when planning).
    protected long turnStartTimeMillis;


    protected Random m_rnd;
    protected StateHeuristic heuristic;
    protected ExperimentalEMCTSParams params;

    protected ExperimentalEMCTSTreeNode root;

    /** v4 hook: snapshot of the INITIAL root sequence, captured exactly when the root is first built
     *  by randomActions() (before any search / bridge-burn promotion mutates it). Used by the v4 smoke
     *  test to verify all agents produced identical random initial sequences for the same (scenario,
     *  visit) seed — the paired-comparison guarantee. */
    private java.util.List<String> initialRootSequenceForV4 = java.util.Collections.emptyList();

    public java.util.List<String> getInitialRootSequenceForV4() {
        return initialRootSequenceForV4;
    }
    protected ExperimentalEMCTSTreeNode bestNode;
    /** Expansion target: the highest-value node currently IN the live tree (the incumbent).
     *  Purely exploitative selection expands this node on every iteration. Distinct from
     *  {@link #bestNode}: bestNode is the best sequence EVER seen this turn (it survives
     *  pruning and supplies the final answer), while the incumbent is re-anchored onto the
     *  surviving subtree whenever a bridge burn prunes it out of the tree (fallback
     *  promotions and tournament-bred roots), so incumbent.value <= bestNode.value always. */
    protected ExperimentalEMCTSTreeNode incumbent;
    /** Tree-shape check (game lifetime, never reset): max children observed on any single
     *  node. The 2026-08 selection fix makes failed children pile up as siblings under the
     *  incumbent, so this exceeds 1 as soon as any child fails to improve; the pre-fix
     *  selection degenerated the tree into a 1-child-per-node chain (1, plus occasional
     *  crossover grafts). */
    protected int maxChildrenOnANode;
    protected GameState old_gs;

    protected int fmCallsCount;
    protected int fmCallsRun;
    // Per-turn search telemetry (reset each planTurn): nodes scored, value sum (mean), distinct keys.
    protected int nodesEvaluated;
    protected double sumNodeValue;
    protected final java.util.HashSet<String> uniqueSeqKeys = new java.util.HashSet<>();
    // Opt-in forward-model profiling (default OFF => zero overhead in real runs). FmComputeProbe sets
    // PROFILE_FM and reads these accumulators to measure what fraction of planning wall-clock is the
    // forward model (gs.advance()) vs everything else (state copies, heuristic eval, bookkeeping).
    public static volatile boolean PROFILE_FM = false;
    public static long FM_NANOS = 0;
    public static long PLAN_NANOS = 0;
    protected PriorityQueue<ExperimentalEMCTSTreeNode> topNodesQueue;
    protected PriorityQueue<ExperimentalEMCTSTreeNode> globalTopNodesQueue;
    protected int crossoverExpansionCounter = 0;

    // ---- v5 operator telemetry (reset each planTurn). Observation-only: collecting these performs
    // NO advance/copy/eval, so the ComputeMeter budget and the search itself are unaffected.
    protected int mutEvents, mutBestGains, mutChildDuplicates, mutRepairKept, mutRepairReplaced;
    protected double mutBestGainSum;
    protected int xoEvents, xoBestGains, xoChildBeatsBestParent, xoChildWorseThanBothParents,
                  xoChildDuplicates, xoRepairKept, xoRepairReplaced;
    protected double xoBestGainSum, xoChildVsBestParentSum, xoChildVsMeanParentSum, xoParentSimilaritySum;
    // Per-call repair counters, written by repair() (mutation path) and by crossover()'s inline
    // repair (crossover path); the main-loop call sites fold them into the turn totals above.
    protected int lastRepairKept, lastRepairReplaced;       // repair()  — mutation children
    protected int lastXoRepairKept, lastXoRepairReplaced;   // crossover() inline repair

    // ---- v6 realized-spend accounting (reset each planTurn) --------------------------------------
    // Cost of the greedy 1-ply initialization, which v6 measures but does NOT charge to the search
    // budget; and the wall-clock split. See planTurn() for why initialization is excluded.
    protected double initComputeUnits;
    protected long initAdvances, initCopies, initEvals;
    protected int initFmCalls;
    protected long initMillis;

    // ---- Bridge-burn elite-pool occupancy (reset each planTurn) ---------------------------------
    // The crossover bridge-burn tournament is a 3-level bracket over the phase's top EIGHT nodes:
    // 8 -> 4 -> 2 -> 1. It runs whenever the pool holds at least 2, so at a small budget it can fire
    // on a pool of 2 or 3 and degenerate into a single pairing - structurally a different operator
    // from the one the design describes. These counters make that observable, which is what decides
    // whether a budget level is large enough to be testing crossover bridge burning at all.
    protected int bbTournaments;      // burns that actually ran a tournament
    protected int bbPoolSizeSum;      // sum of pool sizes at those tournaments
    protected int bbFullBrackets;     // tournaments that had the full 8

    /** Burns at which a crossover tournament ran (rather than a plain best-child promotion). */
    public int getBbTournaments()  { return bbTournaments; }
    /** Mean number of elites available to those tournaments; 8 is a full bracket. */
    public double getBbMeanPoolSize() { return bbTournaments > 0 ? bbPoolSizeSum / (double) bbTournaments : 0.0; }
    /** Tournaments that had all 8 elites, i.e. the bracket the operator is defined as. */
    public int getBbFullBrackets() { return bbFullBrackets; }

    /** Tree-shape check: max children observed on any single node over this agent's
     *  lifetime (one game in the experiments). 1 = the degenerate pre-fix chain;
     *  > 1 = the search tree really branches. See SelectionFixCheck. */
    public int getMaxBranching() { return maxChildrenOnANode; }


    public ExperimentalEMCTSAgent(long seed, ExperimentalEMCTSParams params) {
        super(seed);
        m_rnd = new Random(seed);
        this.params = params;
        // Multi-child generations and crossover expansion are not defined together: crossover grafts
        // its child onto an elite parent elsewhere in the tree rather than onto the expanded node, so
        // "the best of this node's k offspring" stops describing the generation. Refuse the
        // combination rather than pick an interpretation of it.
        if (params.childrenPerGeneration > 1
                && (params.useCrossoverExpansion || params.useCrossoverBridgeBurning)) {
            throw new IllegalArgumentException(
                    "childrenPerGeneration > 1 requires both crossover operators OFF (got k="
                    + params.childrenPerGeneration + ")");
        }
        if (params.childrenPerGeneration < 1) {
            throw new IllegalArgumentException(
                    "childrenPerGeneration must be >= 1 (got " + params.childrenPerGeneration + ")");
        }
    }

    @Override
    public Action act(GameState gs, ElapsedCpuTimer ect) {
        // The engine calls act() once per action. Plan the ENTIRE turn with a single
        // phased search on the first act() of the turn, then replay the cached best
        // action sequence on subsequent calls (no mid-turn re-search). A change in the
        // game tick marks a new turn - robust even if the engine auto-ends a turn.
        int tick = gs.getTick();
        if (tick != plannedTick) {
            planTurn(gs, ect);
            plannedTick = tick;
        }

        // Tier 1 (Salvage): next action from the cached best macro-sequence.
        Action next = null;
        if (!turnPlan.isEmpty()) {
            Action candidate = turnPlan.remove(0);
            if (candidate != null && checkActionFeasibility(candidate, gs)) {
                next = candidate;
            }
        }
        // Tier 2 (Greedy Random): cached step broke; pick a random valid action.
        if (next == null) {
            ArrayList<Action> availableActions = this.allGoodActions(gs, m_rnd);
            if (availableActions != null && !availableActions.isEmpty()) {
                next = availableActions.get(m_rnd.nextInt(availableActions.size()));
            }
        }
        // Tier 3 (End Turn): nothing valid left.
        if (next == null) {
            next = new core.actions.tribeactions.EndTurn(gs.getActiveTribeID());
        }
        return next;
    }

    /**
     * Plans the whole turn with a single phased (bridge-burning) search and caches the
     * best full action sequence in turnPlan. Records this turn's telemetry:
     * InitialSequenceValue (greedy root, pre-search), FinalSequenceValue (best node,
     * post-search), nodesVisited (search iterations), and bridge-burn / crossover events.
     */
    protected void planTurn(GameState gs, ElapsedCpuTimer ect) {
        turnStartTimeMillis = System.currentTimeMillis();
        long __planStart = PROFILE_FM ? System.nanoTime() : 0L;

        this.fmCallsCount = 0;
        this.nodesEvaluated = 0;
        this.sumNodeValue = 0.0;
        this.uniqueSeqKeys.clear();
        // v5 operator telemetry — fresh per planning episode.
        mutEvents = mutBestGains = mutChildDuplicates = mutRepairKept = mutRepairReplaced = 0;
        mutBestGainSum = 0.0;
        xoEvents = xoBestGains = xoChildBeatsBestParent = xoChildWorseThanBothParents = 0;
        xoChildDuplicates = xoRepairKept = xoRepairReplaced = 0;
        xoBestGainSum = xoChildVsBestParentSum = xoChildVsMeanParentSum = xoParentSimilaritySum = 0.0;
        bbTournaments = bbPoolSizeSum = bbFullBrackets = 0;
        core.game.ComputeMeter.reset(); // deterministic compute-unit budget (STOP_COMPUTE)
        // Price this turn with THIS configuration's measured weights. Worker threads are reused across
        // configurations, so this is set every episode - including to null, which restores the global
        // weights rather than leaving the previous turn's profile in place.
        core.game.ComputeMeter.useProfile(params.computeProfile);
        this.heuristic = params.getStateHeuristic(playerID, allPlayerIDs);

        old_gs = gs.copy();

        // Player score at the very start of the turn (for the score-progression column).
        int scoreAtTurnStart = gs.getScore(playerID);

        // Initialize root with 1-ply greedy initialization
        root = randomActions(gs.copy());
        // v4 paired-guarantee verification hook: snapshot the initial root sequence as toString()s
        // BEFORE search modifies it. Cheap (one ArrayList allocation + toString per action).
        {
            java.util.ArrayList<String> snap = new java.util.ArrayList<>(root.getSequence() == null ? 0 : root.getSequence().size());
            if (root.getSequence() != null)
                for (core.actions.Action a : root.getSequence()) snap.add(a == null ? "_" : a.toString());
            this.initialRootSequenceForV4 = java.util.Collections.unmodifiableList(snap);
        }
        eval(gs.copy(), root);

        double initialValue = root.getValue();

        // ---- v6: the greedy initialization is NOT charged to the search budget --------------------
        // The initial sequence is produced by identical code in every agent and is shared across all
        // configs for a given (scenario, visit), so its cost is a common constant that carries no
        // information about the operators under study. Charging it would also bias the comparison
        // along the primary covariate: greedy init evaluates EVERY available action at EVERY sequence
        // position, so its cost scales with branching factor — and branching factor differs
        // systematically between single and dual scenarios (v4: BF mean 452 vs 290). Charging it would
        // therefore hand single scenarios systematically less SEARCH budget than dual, along the exact
        // axis the single-vs-dual analysis measures.
        //
        // Bridge burning (standard and crossover) is deliberately NOT excluded: the crossover
        // tournament runs inside the phase loop below, after this reset, and its advance/copy/eval
        // calls are counted like any other search work. Paying for the tournament out of the shared
        // budget is the whole point — it is the efficiency cost under study.
        initComputeUnits = core.game.ComputeMeter.computeUnits();
        initAdvances = core.game.ComputeMeter.advances();
        initCopies   = core.game.ComputeMeter.copies();
        initEvals    = core.game.ComputeMeter.evals();
        initFmCalls  = this.fmCallsCount;
        initMillis   = System.currentTimeMillis() - turnStartTimeMillis;
        core.game.ComputeMeter.reset();   // phase ceilings below bound SEARCH only

        bestNode = root;
        incumbent = root;
        fmCallsRun = 0;

        // Initialize PriorityQueue for tracking top nodes (min heap by value to keep the top K elements)
        topNodesQueue = new PriorityQueue<>((n1, n2) -> Double.compare(n1.getValue(), n2.getValue()));
        topNodesQueue.offer(root);

        globalTopNodesQueue = new PriorityQueue<>((n1, n2) -> Double.compare(n1.getValue(), n2.getValue()));
        globalTopNodesQueue.offer(root);
        crossoverExpansionCounter = 0;

        // ---- Phase-based bridge burning (FIXED burn count for every agent) ----
        // The turn's search is split into params.depth (=21) phases; a bridge burn fires at each
        // of the first depth-1 (=20) phase boundaries. The number of burns is therefore FIXED at
        // 20 for EVERY agent - standard and crossover alike - so cross-model variance can never be
        // attributed to one family pruning (burning) more or less than another.
        //
        // Each phase is allotted an equal CUMULATIVE slice of the budget: a phase runs MCTS
        // iterations until the running total reaches that phase's ceiling = (phase+1)*budget/depth.
        // Because the ceiling is cumulative, the budget a crossover bridge-burn tournament spends
        // is absorbed by the NEXT phase doing proportionally fewer iterations.
        // Net effect: every agent consumes the SAME total budget (compute-units under STOP_COMPUTE,
        // which is what v5 uses; fmcalls / iterations / time under the other stop types) AND
        // performs the SAME 20 burns; crossover agents simply evaluate fewer distinct sequences
        // (the cost of their tournaments) - which is the effect under study, not a difference in
        // the amount of pruning.
        // Under STOP_TIME an explicit params.num_timeMillis (> 0) gives the search a fixed slice that
        // does not shrink by however long the greedy initialization took — required by the v6
        // calibration, which asks "what does this agent do in exactly T ms of SEARCH". 0 keeps the
        // historical behaviour (whatever the engine's turn timer has left).
        long turnBudgetTime = params.num_timeMillis > 0 ? params.num_timeMillis : ect.remainingTimeMillis();
        ElapsedCpuTimer turnTimer = new ElapsedCpuTimer();
        long fmPerPhase      = Math.max(1, params.num_fmcalls      / Math.max(1, params.depth));
        long computePerPhase = Math.max(1, params.num_computeUnits / Math.max(1, params.depth));
        long itersPerPhase   = Math.max(1, params.num_iterations   / Math.max(1, params.depth));
        long timePerPhase    = Math.max(1, turnBudgetTime          / Math.max(1, params.depth));
        final int MAX_ITERS_PER_PHASE = 1_000_000; // safety net against a no-progress stall
        int numIters = 0;

        for (int currentPhase = 0; currentPhase < params.depth; currentPhase++) {
            // Cumulative budget ceiling at the end of this phase's MCTS iterations.
            long fmCeiling        = (long) (currentPhase + 1) * fmPerPhase;
            double computeCeiling = (double) (currentPhase + 1) * computePerPhase;
            long iterCeiling      = (long) (currentPhase + 1) * itersPerPhase;
            long timeCeiling      = (long) (currentPhase + 1) * timePerPhase;
            int phaseStartIters = numIters;

            // Run iterations until this phase's cumulative budget ceiling is reached. We only
            // force an extra iteration PAST the ceiling when a non-final phase still has no
            // promotable child for its upcoming burn. Agents whose burn inherits a subtree (the
            // common case) therefore do ZERO wasted iterations once over budget, so total spend
            // stays at num_fmcalls / turn time while all 20 burns are still guaranteed.
            while (true) {
                boolean ceilingReached;
                if (params.stop_type == params.STOP_TIME) {
                    ceilingReached = turnTimer.elapsedMillis() >= timeCeiling;
                } else if (params.stop_type == params.STOP_ITERATIONS) {
                    ceilingReached = numIters >= iterCeiling;
                } else if (params.stop_type == params.STOP_COMPUTE) {
                    ceilingReached = core.game.ComputeMeter.computeUnits() >= computeCeiling;
                } else { // STOP_FMCALLS (default)
                    // Relative to the post-initialization baseline, so this stop type excludes the
                    // greedy init exactly as STOP_COMPUTE does.
                    ceilingReached = (fmCallsCount - initFmCalls) >= fmCeiling;
                }
                boolean needChildForBurn = (currentPhase < params.depth - 1) && root.getChildren().isEmpty();
                if (ceilingReached && !needChildForBurn) break;
                if ((numIters - phaseStartIters) >= MAX_ITERS_PER_PHASE) break;

                numIters++;
                fmCallsRun = 0;

                // One GENERATION: expand the selected node k times, then apply the selection
                // rule once to the whole offspring set. At k = 1 with PLUS this reduces exactly to
                // the previous one-child-per-iteration loop — same operations, same order, same RNG
                // draws — which is what keeps every equivalence gate meaningful.
                ExperimentalEMCTSTreeNode toMutate = nodeToExpand();
                final int k = Math.max(1, params.childrenPerGeneration);
                ExperimentalEMCTSTreeNode bestOfGeneration = null;
                ExperimentalEMCTSTreeNode randomOfGeneration = null;
                for (int childIdx = 0; childIdx < k; childIdx++) {
                    if (childIdx > 0) numIters++;
                    fmCallsRun = 0;
                    ExperimentalEMCTSTreeNode born = expandOnce(toMutate, gs);
                    lastCreatedChild = born;
                    if (bestOfGeneration == null || born.getValue() > bestOfGeneration.getValue())
                        bestOfGeneration = born;
                    // Reservoir pick, so COMMA_RAND draws uniformly without a second pass.
                    //
                    // Guarded on k > 1, and that guard is load-bearing: at k = 1 there is nothing to
                    // choose between, and drawing anyway consumes a random number the pre-existing
                    // search never consumed — which shifts the whole stream and silently breaks
                    // bit-identity with the corrected baseline. It did: every ExperimentalEMCTS arm
                    // diverged until this guard was added.
                    //
                    // Drawn for EVERY k > 1 mode rather than only COMMA_RAND, so all multi-child arms
                    // consume one identical RNG stream and differ solely in which child they adopt.
                    if (k == 1) randomOfGeneration = born;
                    else if (m_rnd.nextInt(childIdx + 1) == 0) randomOfGeneration = born;
                }

                adoptAfterGeneration(bestOfGeneration, randomOfGeneration);
            }

            // Bridge burn at every phase boundary except the last: FIXED at depth-1 = 20 burns,
            // independent of the budget. Standard agents promote the best child (free); crossover
            // agents breed a new root via the tournament (which spends forward-model calls).
            if (currentPhase < params.depth - 1 && root.getChildren().size() > 0) {
                ExperimentalEMCTSTreeNode newRoot;
                if (params.useCrossoverBridgeBurning && topNodesQueue.size() >= 2) {
                    bbTournaments++;
                    bbPoolSizeSum += topNodesQueue.size();
                    if (topNodesQueue.size() >= 8) bbFullBrackets++;
                    newRoot = performCrossoverBridgeBurning(gs.copy(), currentPhase, turnStartTimeMillis);
                } else {
                    newRoot = standardBurnTarget();
                }

                if (newRoot != null && newRoot != root) {
                    // Unlink old root branches except the new root
                    for (ExperimentalEMCTSTreeNode child : root.getChildren()) {
                        if (child != newRoot) {
                            unlinkTree(child, newRoot);
                        }
                    }

                    newRoot.disconnectParent();  // Clears parent link
                    root = newRoot;

                    // Clear and reset queue for next phase
                    topNodesQueue.clear();
                    topNodesQueue.offer(root);

                    // Re-anchor the incumbent if this burn pruned it out of the live tree:
                    // possible when the fallback promoted a non-incumbent branch (the
                    // incumbent was the old root) and when a crossover tournament bred a
                    // fresh root (which discards the entire old tree by design).
                    if (!isInLiveTree(incumbent)) {
                        incumbent = subtreeMax(root);
                    }

                    // Purge crossover-expansion parents that this burn just pruned: a child
                    // grafted onto a node outside the live tree would be invisible to both
                    // selection and bridge burning.
                    if (params.useCrossoverExpansion) {
                        purgeDetachedTopNodes();
                    }

                    // Record the bridge-burn trajectory point (1-indexed phase number), logging the
                    // best sequence value found SO FAR (bestNode) - the monotonic expansion curve.
                    recordBridgeBurn(currentPhase + 1, bestNode.getValue(), turnStartTimeMillis, gs.getTick());
                }
                // If no promotable child exists (only possible on a degenerate short turn), we skip
                // this single burn; that limit applies identically to every agent, so it is unbiased.
            }
        }

        // Telemetry for this single turn-planning episode:
        // Initial = greedy root (pre-search); Final = best node found (post-search);
        // nodesVisited = forward-model calls this turn (the computational-workload metric;
        // dominated by sequence evaluations + greedy repair, in the thousands at real budgets).
        double finalValue = bestNode.getValue();
        double avgNodeValue = nodesEvaluated > 0 ? sumNodeValue / nodesEvaluated : 0.0;
        turnDataList.add(new TurnData(
                -1, gs.getTick(), scoreAtTurnStart, initialValue, finalValue, avgNodeValue,
                this.fmCallsCount, nodesEvaluated, uniqueSeqKeys.size(),
                // v6 realized SEARCH spend (the meter was reset after initialization, so these are
                // post-init) + the separately-measured initialization spend + the wall-clock split.
                core.game.ComputeMeter.computeUnits(), core.game.ComputeMeter.advances(),
                core.game.ComputeMeter.copies(), core.game.ComputeMeter.evals(),
                initComputeUnits, initAdvances, initCopies, initEvals, initFmCalls,
                System.currentTimeMillis() - turnStartTimeMillis, initMillis
        ));

        // Cache the best full action sequence; act() replays it over the turn.
        ArrayList<Action> bestSeq = bestNode.getSequence();
        turnPlan = (bestSeq != null) ? new ArrayList<>(bestSeq) : new ArrayList<>();
        if (PROFILE_FM) PLAN_NANOS += System.nanoTime() - __planStart;
    }

    @Override
    public Agent copy() {
        return null;
    }

    public void eval(GameState gs, ExperimentalEMCTSTreeNode node) {
        if(node.getGs() == null) {
            for (Action move : node.getSequence()) {
                advance(gs, move);
            }
            node.setGs(gs);
            node.setValue(heuristic.evaluateState(old_gs, gs));
        }else{
            node.setValue(heuristic.evaluateState(old_gs, node.getGs()));
        }
        // Telemetry: each eval() scores one candidate node (main-loop children, the initial root,
        // and bridge-burn tournament children). Count it (duplicates included), accumulate its value
        // (for the per-turn mean), and record its sequence key (distinct-sequence count).
        // Keep score == value. Nothing reads score under the corrected policies, so this is inert
        // for them; SelectionMode.LEGACY ranks by score, and without this its descent silently
        // degrades from "best offspring" to "first offspring". Restoring it here is exactly what the
        // pre-fix backpropagation did.
        node.refreshScore(params.bias);
        nodesEvaluated++;
        sumNodeValue += node.getValue();
        uniqueSeqKeys.add(seqKey(node.getSequence()));
    }

    /** Canonical key for an action sequence (for de-duplication), composed from each action's
     *  content toString(). */
    protected String seqKey(ArrayList<Action> seq) {
        if (seq == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Action a : seq) sb.append(a == null ? "_" : a.toString()).append('|');
        return sb.toString();
    }

    /** Fraction of aligned positions (up to the shorter length) where both sequences hold the same
     *  action (by content toString). 1.0 = the shorter parent is a positional prefix-clone of the
     *  longer. Observation-only v5 telemetry: no forward-model calls, no ComputeMeter impact. */
    protected double sequenceSimilarity(ArrayList<Action> a, ArrayList<Action> b) {
        if (a == null || b == null) return 0.0;
        int m = Math.min(a.size(), b.size());
        if (m == 0) return 0.0;
        int same = 0;
        for (int i = 0; i < m; i++) {
            Action x = a.get(i), y = b.get(i);
            String sx = (x == null) ? "_" : x.toString();
            String sy = (y == null) ? "_" : y.toString();
            if (sx.equals(sy)) same++;
        }
        return same / (double) m;
    }

    @Override
    public core.data.dto.OperatorTelemetryData getOperatorTelemetry() {
        return new core.data.dto.OperatorTelemetryData(
                mutEvents, mutBestGains, mutBestGainSum, mutChildDuplicates,
                mutRepairKept, mutRepairReplaced,
                xoEvents, xoBestGains, xoBestGainSum,
                xoChildVsBestParentSum, xoChildVsMeanParentSum,
                xoChildBeatsBestParent, xoChildWorseThanBothParents,
                xoParentSimilaritySum, xoChildDuplicates, xoRepairKept, xoRepairReplaced);
    }


    /** The most recently created child, which is what {@link SelectionMode#DRIFT} expands next. */
    protected ExperimentalEMCTSTreeNode lastCreatedChild;

    /**
     * One child: expand {@code toMutate} (by mutation, or by crossover when that is this iteration's
     * turn), evaluate it, and fold it into the elitist archive and the telemetry. Lifted verbatim out
     * of the search loop so a generation can call it k times; the only thing removed is the selection
     * decision, which is now made once per generation by {@link #adoptAfterGeneration}.
     */
    private ExperimentalEMCTSTreeNode expandOnce(ExperimentalEMCTSTreeNode toMutate, GameState gs) {
        ExperimentalEMCTSTreeNode child = null;
        // v5 telemetry: which operator generated this child + the crossover parents' stats.
        boolean usedCrossover = false;
        double xoP1 = 0.0, xoP2 = 0.0;

                    if (params.useCrossoverExpansion && globalTopNodesQueue.size() >= 2) {
                        crossoverExpansionCounter++;
                        if (crossoverExpansionCounter >= params.mutationExpansionRatio) {
                            crossoverExpansionCounter = 0;

                            ArrayList<ExperimentalEMCTSTreeNode> topNodesList = new ArrayList<>(globalTopNodesQueue);
                            ExperimentalEMCTSTreeNode p1 = topNodesList.get(0);
                            ExperimentalEMCTSTreeNode p2 = topNodesList.get(1);

                            usedCrossover = true;
                            xoP1 = p1.getValue(); xoP2 = p2.getValue();
                            xoParentSimilaritySum += sequenceSimilarity(p1.getSequence(), p2.getSequence());

                            // crossover() copies the state internally exactly once and never
                            // mutates its gs argument, so no pre-copy (one charged copy, not two).
                            ArrayList<Action> crossoverSeq = crossover(p1, p2, gs);

                            ExperimentalEMCTSTreeNode strongerParent = (p1.getValue() > p2.getValue()) ? p1 : p2;
                            child = new ExperimentalEMCTSTreeNode(crossoverSeq, strongerParent);
                            strongerParent.addChild(child);
                            int nGraftChildren = strongerParent.getChildren().size();
                            if (nGraftChildren > maxChildrenOnANode) maxChildrenOnANode = nGraftChildren;
                            xoEvents++;
                            xoRepairKept += lastXoRepairKept; xoRepairReplaced += lastXoRepairReplaced;
                        } else {
                            child = mutate(toMutate, gs.copy());
                            mutEvents++;
                            mutRepairKept += lastRepairKept; mutRepairReplaced += lastRepairReplaced;
                        }
                    } else {
                        child = mutate(toMutate, gs.copy());
                        mutEvents++;
                        mutRepairKept += lastRepairKept; mutRepairReplaced += lastRepairReplaced;
                    }

                    // Duplicate check BEFORE eval (eval registers the child's key in uniqueSeqKeys).
                    boolean childDuplicate = uniqueSeqKeys.contains(seqKey(child.getSequence()));
                    double prevBest = bestNode.getValue();
                    eval(gs.copy(), child);

                    if (usedCrossover) {
                        double cv = child.getValue();
                        double pBest = Math.max(xoP1, xoP2), pMean = 0.5 * (xoP1 + xoP2);
                        xoChildVsBestParentSum += cv - pBest;
                        xoChildVsMeanParentSum += cv - pMean;
                        if (cv > pBest) xoChildBeatsBestParent++;
                        if (cv < Math.min(xoP1, xoP2)) xoChildWorseThanBothParents++;
                        if (childDuplicate) xoChildDuplicates++;
                    } else if (childDuplicate) {
                        mutChildDuplicates++;
                    }

                    if (child.getValue() > prevBest) {
                        if (usedCrossover) { xoBestGains++;  xoBestGainSum  += child.getValue() - prevBest; }
                        else               { mutBestGains++; mutBestGainSum += child.getValue() - prevBest; }
                        bestNode = child;
                    }

                    child.visited();

                    // Track top nodes for crossover bridge burning
                    topNodesQueue.offer(child);
                    if (topNodesQueue.size() > 8) {
                        topNodesQueue.poll();
                    }

                    globalTopNodesQueue.offer(child);
                    if (globalTopNodesQueue.size() > 2) {
                        globalTopNodesQueue.poll();
                    }

        return child;
    }

    /**
     * The selection rule, applied once per generation.
     *
     * <p>This is the only line that differs between the policies, which is the point: comma and plus
     * run identical operators over identical offspring and disagree solely about whether to move when
     * nothing improved.
     */
    private void adoptAfterGeneration(ExperimentalEMCTSTreeNode bestOfGeneration,
                                      ExperimentalEMCTSTreeNode randomOfGeneration) {
        if (bestOfGeneration == null) return;
        switch (params.selectionMode) {
            case COMMA:
                // (1,k): relocate every generation, improvement or not. bestNode still holds the
                // best sequence ever seen, so non-elitist SELECTION does not lose the answer.
                incumbent = bestOfGeneration;
                break;
            case COMMA_RAND:
                incumbent = randomOfGeneration != null ? randomOfGeneration : bestOfGeneration;
                break;
            case DRIFT:
                incumbent = lastCreatedChild;
                break;
            case LEGACY:
                incumbent = legacyDescendToLeaf();
                break;
            case PLUS:
            default:
                // Purely exploitative: a strictly better child becomes the new expansion target;
                // otherwise the incumbent stays where it is and the generation is discarded. (No UCB
                // backpropagation: with a zero exploration term a node's score IS its heuristic
                // value, so there is nothing to propagate.) Crossover-expansion children are grafted
                // onto live parents (see purgeDetachedTopNodes), so any child that wins here is
                // reachable by the burn-lineage walk.
                if (bestOfGeneration.getValue() > incumbent.getValue()) incumbent = bestOfGeneration;
                break;
        }
    }

    /**
     * The pre-fix policy, reproduced exactly: descend from the root taking the highest-SCORE child
     * until a node with no children.
     *
     * <p><b>It ranks by score, not value, and that detail once cost a silently wrong result.</b> The
     * selection fix deleted the backpropagation loop that kept {@code score == value}; with scores
     * left at 0.0 the strict {@code >} below keeps the FIRST child, turning "expand the best
     * offspring" into "expand an arbitrary offspring" — invisible at k = 1, where there is only one.
     * {@link #eval} therefore calls {@code refreshScore}, which restores exactly what the pre-fix
     * backpropagation did and is invisible to every other arm because nothing else reads score.
     */
    protected ExperimentalEMCTSTreeNode legacyDescendToLeaf() {
        ExperimentalEMCTSTreeNode node = root;
        while (node.getChildren().size() > 0) {
            ExperimentalEMCTSTreeNode best = null;
            for (ExperimentalEMCTSTreeNode c : node.getChildren())
                if (best == null || c.getScore() > best.getScore()) best = c;
            node = best;
        }
        return node;
    }

    /**
     * Purely exploitative selection: the node expanded on every iteration is the
     * highest-value node currently in the live tree (the incumbent) - UCB with a zero
     * exploration term. The incumbent is maintained incrementally (updated when a child
     * improves on it; re-anchored when a bridge burn prunes it) rather than recovered by
     * greedy root-to-leaf descent, for two reasons. First, a descent that always follows
     * the best child cannot stop at an interior node - the pre-fix bug: every new child,
     * better or worse, became the next expansion target, so the search degenerated into an
     * unguided random walk along a single mutation chain. Second, crossover expansion
     * grafts children onto elite parents elsewhere in the tree, and a best-child descent
     * is blind to a maximum sitting below a weaker ancestor; tracking the incumbent by
     * reference finds it regardless of where it was grafted.
     */
    protected ExperimentalEMCTSTreeNode nodeToExpand() {
        return incumbent != null ? incumbent : root;
    }

    /** Standard bridge-burn target: the root child on the incumbent's path (keeps the best
     *  live sequence in the tree), falling back to the best immediate child when the
     *  incumbent is the root itself - no child improved on it yet, but the burn count is
     *  fixed, so the phase still promotes (and the incumbent re-anchors after promotion). */
    protected ExperimentalEMCTSTreeNode standardBurnTarget() {
        ExperimentalEMCTSTreeNode onIncumbentPath = childOfRootOnPathTo(incumbent);
        return onIncumbentPath != null ? onIncumbentPath : findBestChild(root);
    }

    /** The direct child of {@code root} on the path from the root down to {@code node};
     *  null when {@code node} is the root itself or is not in the live tree. */
    protected ExperimentalEMCTSTreeNode childOfRootOnPathTo(ExperimentalEMCTSTreeNode node) {
        ExperimentalEMCTSTreeNode current = node;
        while (current != null && current.getParent() != root) {
            current = current.getParent();
        }
        return current;
    }

    /** True when {@code node}'s parent chain reaches the current root. */
    protected boolean isInLiveTree(ExperimentalEMCTSTreeNode node) {
        for (ExperimentalEMCTSTreeNode current = node; current != null; current = current.getParent()) {
            if (current == root) return true;
        }
        return false;
    }

    /** Highest-value node in the subtree rooted at {@code node} (iterative, O(subtree)).
     *  Only called after a bridge burn that pruned the incumbent, so the cost is rare and
     *  bounded by the surviving subtree. */
    protected ExperimentalEMCTSTreeNode subtreeMax(ExperimentalEMCTSTreeNode node) {
        ExperimentalEMCTSTreeNode best = node;
        ArrayDeque<ExperimentalEMCTSTreeNode> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            ExperimentalEMCTSTreeNode current = pending.pop();
            if (current.getValue() > best.getValue()) best = current;
            ArrayList<ExperimentalEMCTSTreeNode> children = current.getChildren();
            if (children != null) {
                for (ExperimentalEMCTSTreeNode c : children) {
                    if (c != null) pending.push(c);
                }
            }
        }
        return best;
    }

    /** Drops globalTopNodesQueue entries that are no longer in the live tree (their branch
     *  was just burned). Crossover-expansion children are grafted onto the stronger parent,
     *  and a graft below a detached node would be unreachable for selection and burning, so
     *  parents must be live. Reseeds with the current root when nothing survives, so the
     *  size >= 2 crossover gate re-opens as new children arrive. */
    protected void purgeDetachedTopNodes() {
        ArrayList<ExperimentalEMCTSTreeNode> survivors = new ArrayList<>();
        for (ExperimentalEMCTSTreeNode n : globalTopNodesQueue) {
            if (isInLiveTree(n)) survivors.add(n);
        }
        if (survivors.size() < globalTopNodesQueue.size()) {
            globalTopNodesQueue.clear();
            for (ExperimentalEMCTSTreeNode n : survivors) globalTopNodesQueue.offer(n);
        }
        if (globalTopNodesQueue.isEmpty()) {
            globalTopNodesQueue.offer(root);
        }
    }

    /**
     * 1-ply greedy initialization: for each action in the sequence,
     * evaluate all available actions and select the one with the highest heuristic score.
     */
    protected ExperimentalEMCTSTreeNode randomActions(GameState gs) {
        ArrayList<Action> individual = new ArrayList<>();
        
        while ((!gs.isGameOver() && (gs.getActiveTribeID() == getPlayerID())) && 
               (individual.size() < (params.NODE_SIZE - 1))) {
            
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            if (allAvailableActions.isEmpty()) {
                break;
            }

            Action bestAction = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            GameState bestState = null;
            int numTies = 0;

            if (params.randomInitialSequence) {
                // v4 random-initial mode: pick uniformly at random from the non-END_TURN actions
                // (or END_TURN if it's the only option), so all agents starting from the same
                // (scenario, visit) seed produce the same — and intentionally worse-than-greedy —
                // starting sequence. This widens the room-for-improvement during search.
                ArrayList<Action> picks = new ArrayList<>();
                for (Action a : allAvailableActions) {
                    if (a.getActionType() != Types.ACTION.END_TURN || allAvailableActions.size() == 1) {
                        picks.add(a);
                    }
                }
                if (!picks.isEmpty()) {
                    bestAction = picks.get(m_rnd.nextInt(picks.size()));
                }
            } else {
                // Greedy evaluation: try each action and pick the highest-scoring one
                for (Action candidateAction : allAvailableActions) {
                    // Skip END_TURN unless it's the only option
                    if (candidateAction.getActionType() == Types.ACTION.END_TURN &&
                        allAvailableActions.size() > 1) {
                        continue;
                    }

                    GameState testGs = gs.copy();
                    advance(testGs, candidateAction);
                    double score = heuristic.evaluateState(old_gs, testGs);

                    if (score > bestScore) {
                        bestScore = score;
                        bestAction = candidateAction;
                        bestState = testGs;
                        numTies = 1;
                    } else if (score == bestScore) {
                        numTies++;
                        if (m_rnd.nextInt(numTies) == 0) {
                            bestAction = candidateAction;
                            bestState = testGs;
                        }
                    }
                }
            }

            if (bestAction != null) {
                advance(gs, bestAction);
                individual.add(bestAction);

                // If we added END_TURN, we're done
                if (bestAction.getActionType() == Types.ACTION.END_TURN) {
                    break;
                }
            } else {
                break;
            }
        }

        // Ensure sequence ends with END_TURN if not already present
        if (!individual.isEmpty() && 
            individual.get(individual.size() - 1).getActionType() != Types.ACTION.END_TURN) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            Action endAction = null;
            for (Action a : allAvailableActions) {
                if (a.getActionType() == Types.ACTION.END_TURN) {
                    endAction = a;
                    break;
                }
            }
            if (endAction != null) {
                individual.add(endAction);
            }
        }

        ExperimentalEMCTSTreeNode node = new ExperimentalEMCTSTreeNode(individual, null);
        node.setGs(gs.copy());
        return node;
    }


    protected void advance(GameState gs, Action move) {
        this.fmCallsCount++;
        this.fmCallsRun++;
        if (PROFILE_FM) {
            long __t0 = System.nanoTime();
            gs.advance(move, true);
            FM_NANOS += System.nanoTime() - __t0;
        } else {
            gs.advance(move, true);
        }
    }

    /**
     * A turn's action sequence must end at the FIRST END_TURN. In solo (1-tribe) games the
     * active tribe does not change after END_TURN, so the mutate/crossover/repair builders
     * would otherwise keep generating actions PAST it into future turns, producing multi-turn
     * plans the agent never executes (and inflating the final-sequence-value metric). Truncate.
     */
    protected ArrayList<Action> truncateAtEndTurn(ArrayList<Action> seq) {
        ArrayList<Action> out = new ArrayList<>();
        for (Action a : seq) {
            out.add(a);
            if (a != null && a.getActionType() == Types.ACTION.END_TURN) break;
        }
        return out;
    }

    //mutate a random action
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

        if (!allAvailableActions.isEmpty()) {
            Action a = allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
            newSeq.add(a);
        }

        for (int i = moveToMutate + 1; i < seq.size(); i++) {
            newSeq.add(seq.get(i));
        }
        newSeq = truncateAtEndTurn(repair(newSeq, clone));
        ExperimentalEMCTSTreeNode newNode = new ExperimentalEMCTSTreeNode(newSeq, node);
        node.addChild(newNode);
        int nChildren = node.getChildren().size();
        if (nChildren > maxChildrenOnANode) maxChildrenOnANode = nChildren;

        return newNode;
    }

    /**
     * Repair a sequence of actions, ensuring feasibility and validity.
     * Uses 1-ply greedy repair: if an action fails, replace it by selecting
     * the available action with the highest heuristic score.
     */
    protected ArrayList<Action> repair(ArrayList<Action> child, GameState gs) {
        ArrayList<Action> repairedChild = new ArrayList<>();
        lastRepairKept = 0; lastRepairReplaced = 0;   // v5 telemetry (per-call; folded in by caller)

        for (int a = 0; a < child.size(); a++) {
            if (!(gs.getActiveTribeID() == getPlayerID())) {
                return repairedChild;
            }

            try {
                // Feasibility is a read-only predicate (isFeasible has no side effects — allGoodActions
                // already calls it on a shared gs), so check on gs directly instead of a full-state copy.
                boolean done = checkActionFeasibility(child.get(a), gs);

                if (!done) {
                    // Action is infeasible - use greedy selection for replacement.
                    // allGoodActions is read-only (the catch branch below already calls it on gs), so no copy.
                    ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
                    
                    Action bestAction = null;
                    double bestScore = Double.NEGATIVE_INFINITY;
                    int numTies = 0;

                    for (Action candidateAction : allAvailableActions) {
                        GameState testGs = gs.copy();
                        advance(testGs, candidateAction);
                        double score = heuristic.evaluateState(old_gs, testGs);

                        if (score > bestScore) {
                            bestScore = score;
                            bestAction = candidateAction;
                            numTies = 1;
                        } else if (score == bestScore) {
                            numTies++;
                            if (m_rnd.nextInt(numTies) == 0) {
                                bestAction = candidateAction;
                            }
                        }
                    }

                    if (bestAction != null) {
                        advance(gs, bestAction);
                        repairedChild.add(bestAction);
                        lastRepairReplaced++;
                    } else {
                        break;
                    }
                } else {
                    repairedChild.add(child.get(a));
                    advance(gs, child.get(a));
                    lastRepairKept++;
                }
            } catch (Exception e) {
                // Action threw exception - use greedy selection for replacement
                ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);

                Action bestAction = null;
                double bestScore = Double.NEGATIVE_INFINITY;
                int numTies = 0;

                for (Action candidateAction : allAvailableActions) {
                    GameState testGs = gs.copy();
                    advance(testGs, candidateAction);
                    double score = heuristic.evaluateState(old_gs, testGs);

                    if (score > bestScore) {
                        bestScore = score;
                        bestAction = candidateAction;
                        numTies = 1;
                    } else if (score == bestScore) {
                        numTies++;
                        if (m_rnd.nextInt(numTies) == 0) {
                            bestAction = candidateAction;
                        }
                    }
                }

                if (bestAction != null) {
                    advance(gs, bestAction);
                    repairedChild.add(bestAction);
                    lastRepairReplaced++;
                } else {
                    break;
                }
            }
        }

        // Ensure repaired sequence ends with END_TURN
        if (!repairedChild.isEmpty() && 
            repairedChild.get(repairedChild.size() - 1).getActionType() != Types.ACTION.END_TURN) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            Action end = null;
            for (Action a : allAvailableActions) {
                if (a.getActionType() == Types.ACTION.END_TURN) {
                    end = a;
                    break;
                }
            }
            if (end != null) {
                repairedChild.add(end);
            }
        }

        return repairedChild;
    }

    protected boolean checkActionFeasibility(Action a, GameState gs) {
        if (gs.isGameOver())
            return false;

        if (a instanceof UnitAction) {
            UnitAction ua = (UnitAction) a;
            int unitId = ua.getUnitId();
            Actor act = gs.getActor(unitId);
            if (!(act instanceof Unit) || act.getTribeId() != gs.getActiveTribeID())
                return false;
        } else if (a instanceof CityAction) {
            CityAction ca = (CityAction) a;
            int cityId = ca.getCityId();
            Actor act = gs.getActor(cityId);
            if (!(act instanceof City) || act.getTribeId() != gs.getActiveTribeID())
                return false;
        }

        boolean feasible = false;
        try {
            feasible = a.isFeasible(gs);
        } catch (Exception e) {
        }

        return feasible;
    }

    // try to see of the node can be extended
    protected void extend(ExperimentalEMCTSTreeNode node, GameState gs) {
        GameState clone = gs.copy();
        ArrayList<Action> currentActions = node.getSequence();
        ArrayList<Action> newActions = new ArrayList<>();
        for (int i = 0; i < currentActions.size() - 1; i++) {
            newActions.add(currentActions.get(i));
            advance(gs, currentActions.get(i));
        }

        if (this.allGoodActions(gs, m_rnd).size() > 1) {
            while (!gs.isGameOver() && (gs.getActiveTribeID() == getPlayerID())) {
                ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
                Action a = allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
                if (!(a.getActionType() == Types.ACTION.END_TURN)) {
                    advance(gs, a);
                    newActions.add(a);
                } else if (allAvailableActions.size() == 1) {
                    advance(gs, a);
                    newActions.add(a);
                    break;
                }
            }
        } else {
            return;
        }
        //newActions = repair(newActions, clone.copy());
        ExperimentalEMCTSTreeNode newNode = new ExperimentalEMCTSTreeNode(newActions, node.getParent());
        eval(clone, newNode);
        if (newNode.getValue() > node.getValue()) {
            node.setSequence(newActions);
            node.setValue(newNode.getValue());
        }
        //clear links so that JVM can clear it
        newNode.unlink();
    }

    /**
     * Find the best child of a node based on highest value.
     * Used during bridge burning phase transitions.
     */
    protected ExperimentalEMCTSTreeNode findBestChild(ExperimentalEMCTSTreeNode node) {
        ArrayList<ExperimentalEMCTSTreeNode> children = node.getChildren();
        if (children.isEmpty()) {
            return null;
        }

        ExperimentalEMCTSTreeNode bestChild = children.get(0);
        double bestValue = bestChild.getValue();

        for (ExperimentalEMCTSTreeNode child : children) {
            if (child.getValue() > bestValue) {
                bestValue = child.getValue();
                bestChild = child;
            }
        }

        return bestChild;
    }

    /**
     * Unlink a tree node and all its descendants to allow garbage collection, skipping
     * {@code protectedNode} and everything beneath it.
     * Called during bridge burning to free memory from abandoned branches.
     *
     * <p><b>Iterative, with an explicit stack, and it has to be.</b> This was a recursive descent, and
     * its recursion depth is the depth of the search tree — which grows with the number of accepted
     * mutations, i.e. with the compute budget. At 40,000 and 80,000 units it is fine; at 400,000 it
     * overflowed the default 1 MB thread stack, and because the error propagates out of planTurn and
     * act() the whole game died. In the v6 full-games tournament that was worse than a crash: the
     * runner caught it, re-seeded and replayed the game, so the surviving sample was silently
     * conditioned on "no turn built a deep tree" — a condition correlated with how hard the agent had
     * searched, and therefore with the agent itself.
     *
     * <p>Rewriting it cannot change a result: {@link ExperimentalEMCTSTreeNode#unlink()} only sets
     * {@code parent = null}, so the operation is idempotent and order-independent, and the protected
     * subtree is skipped exactly as before (it is never pushed). The heap traffic is identical; only
     * the stack depth changes, from O(tree depth) to O(1).
     */
    protected void unlinkTree(ExperimentalEMCTSTreeNode node, ExperimentalEMCTSTreeNode protectedNode) {
        if (node == null || node == protectedNode) {
            return;
        }

        ArrayDeque<ExperimentalEMCTSTreeNode> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            ExperimentalEMCTSTreeNode current = pending.pop();
            ArrayList<ExperimentalEMCTSTreeNode> children = current.getChildren();
            if (children != null) {
                for (ExperimentalEMCTSTreeNode child : children) {
                    if (child != null && child != protectedNode) pending.push(child);
                }
            }
            current.unlink();
        }
    }

    /**
     * Performs hierarchical crossover-based bridge burning to generate a new root.
     * Extracts top 8 nodes, performs 3 levels of random crossover pairings:
     * - Level 1: 8 nodes -> 4 children via crossover
     * - Level 2: 4 children -> 2 children via crossover
     * - Level 3: 2 children -> 1 child via crossover
     * All 7 children are evaluated, and the best is compared with bestNode to determine new root.
     */
    protected ExperimentalEMCTSTreeNode performCrossoverBridgeBurning(GameState gs, int currentPhase, long turnStartTime) {
        // Extract top 8 nodes from the priority queue
        ArrayList<ExperimentalEMCTSTreeNode> topEight = new ArrayList<>(topNodesQueue);
        
        // Ensure we have at least 2 nodes for crossover
        if (topEight.size() < 2) {
            return standardBurnTarget();
        }

        // Shuffle to create random pairings
        Collections.shuffle(topEight, m_rnd);

        ArrayList<ExperimentalEMCTSTreeNode> level1Children = new ArrayList<>();
        ArrayList<ExperimentalEMCTSTreeNode> allCrossoverChildren = new ArrayList<>();

        // Level 1: Create 4 children from top 8 (4 random pairs)
        for (int i = 0; i < Math.min(topEight.size() - 1, 8); i += 2) {
            if (i + 1 < topEight.size()) {
                ArrayList<Action> crossoverSeq = crossover(topEight.get(i), topEight.get(i + 1), gs);
                ExperimentalEMCTSTreeNode child = new ExperimentalEMCTSTreeNode(crossoverSeq, null);
                eval(gs.copy(), child);
                level1Children.add(child);
                allCrossoverChildren.add(child);
            }
        }

        // If we have fewer than 4 children, use standard bridge burning instead
        if (level1Children.size() < 2) {
            return standardBurnTarget();
        }

        ArrayList<ExperimentalEMCTSTreeNode> level2Children = new ArrayList<>();

        // Level 2: Create 2 children from 4 level1 children (2 random pairs)
        Collections.shuffle(level1Children, m_rnd);
        for (int i = 0; i < Math.min(level1Children.size() - 1, 4); i += 2) {
            if (i + 1 < level1Children.size()) {
                ArrayList<Action> crossoverSeq = crossover(level1Children.get(i), level1Children.get(i + 1), gs);
                ExperimentalEMCTSTreeNode child = new ExperimentalEMCTSTreeNode(crossoverSeq, null);
                eval(gs.copy(), child);
                level2Children.add(child);
                allCrossoverChildren.add(child);
            }
        }

        // Level 3: Create 1 child from 2 level2 children (1 pair)
        ExperimentalEMCTSTreeNode level3Child = null;
        if (level2Children.size() >= 2) {
            ArrayList<Action> crossoverSeq = crossover(level2Children.get(0), level2Children.get(1), gs);
            level3Child = new ExperimentalEMCTSTreeNode(crossoverSeq, null);
            eval(gs.copy(), level3Child);
            allCrossoverChildren.add(level3Child);
        }

        // Find the best among all 7 crossover children
        ExperimentalEMCTSTreeNode bestCrossoverChild = allCrossoverChildren.get(0);
        for (ExperimentalEMCTSTreeNode child : allCrossoverChildren) {
            if (child.getValue() > bestCrossoverChild.getValue()) {
                bestCrossoverChild = child;
            }
        }

        // Find the best among the up-to-8 elite parents (crossover MEASUREMENT baseline only).
        ExperimentalEMCTSTreeNode bestParent = topEight.get(0);
        for (ExperimentalEMCTSTreeNode parent : topEight) {
            if (parent.getValue() > bestParent.getValue()) {
                bestParent = parent;
            }
        }

        // New-root selection (per the ALGORITHMS spec): the standard bridge-burning target
        // is the root child on the incumbent's path (falling back to the best immediate
        // child) - identical to EMCTS / CrossoverExpansion. The bred candidate only
        // replaces it if it strictly beats it. This keeps the non-crossover path identical
        // to the baseline, so the experiment isolates the impact of crossover rather than
        // also changing the fallback to "best of 8 elite".
        ExperimentalEMCTSTreeNode standardBestChild = standardBurnTarget();
        ExperimentalEMCTSTreeNode newRootCandidate;
        if (standardBestChild == null) {
            newRootCandidate = bestCrossoverChild;
        } else if (bestCrossoverChild.getValue() > standardBestChild.getValue()) {
            newRootCandidate = bestCrossoverChild;
        } else {
            newRootCandidate = standardBestChild;
        }

        if (newRootCandidate.getValue() > bestNode.getValue()) {
            bestNode = newRootCandidate;
        }

        // bestSequenceBefore: heuristic value of the BEST of the up-to-8 elite parent nodes
        //   selected at the end of the previous search phase (before any breeding).
        // bestSequenceAfter: heuristic value of the BEST of the 7 bred children, measured
        //   directly off the crossover generation and BEFORE the tournament fallback that
        //   may keep the parent — so this isolates breeding efficacy, not the final root.
        double scoreBefore = bestParent.getValue();
        double scoreAfter = bestCrossoverChild.getValue();

        double rawImprovement = scoreAfter - scoreBefore;
        double percentageImprovement = (scoreBefore == 0.0) ? 0.0 : ((rawImprovement) / Math.abs(scoreBefore)) * 100.0;

        long expansionPhaseTimestamp = System.currentTimeMillis() - turnStartTime;
        // Use 1-indexed phase number (currentPhase + 1) so this crossover event aligns
        // with the BridgeBurnEventData recorded for the same promotion (which logs the
        // post-increment phase), letting the two files join on (GameId, TurnNumber, phase).
        crossoverDataList.add(new CrossoverEventData(
                -1, gs.getTick(), expansionPhaseTimestamp, currentPhase + 1, scoreBefore, scoreAfter, rawImprovement, percentageImprovement, this.fmCallsCount
        ));

        return newRootCandidate;
    }

    /**
     * Performs uniform crossover on two parent action sequences.
     * For each position, randomly selects an action from either parent using m_rnd.nextBoolean().
     * The resulting sequence is then repaired using greedy selection to ensure action validity.
     * 
     * @param p1 First parent node
     * @param p2 Second parent node
     * @param gs Turn-start game state for action validation and greedy evaluation; copied
     *           internally exactly once and never mutated, so callers pass it directly
     *           (a pre-copy would double-charge the ComputeMeter budget)
     * @return An ArrayList of actions representing the crossed-over and repaired sequence
     */
    protected ArrayList<Action> crossover(ExperimentalEMCTSTreeNode p1, ExperimentalEMCTSTreeNode p2, GameState gs) {
        ArrayList<Action> seq1 = p1.getSequence();
        ArrayList<Action> seq2 = p2.getSequence();
        ArrayList<Action> crossoverSeq = new ArrayList<>();

        // Determine the length of the crossover sequence (use the shorter parent length)
        int maxLength = Math.min(seq1.size(), seq2.size());

        // Perform uniform crossover: for each position, randomly select from either parent
        for (int i = 0; i < maxLength; i++) {
            if (m_rnd.nextBoolean()) {
                crossoverSeq.add(seq1.get(i));
            } else {
                crossoverSeq.add(seq2.get(i));
            }
        }

        // Repair the crossover sequence using greedy evaluation
        ArrayList<Action> repairedSeq = new ArrayList<>();
        lastXoRepairKept = 0; lastXoRepairReplaced = 0;   // v5 telemetry (per-call; main loop folds in)
        GameState testGs = gs.copy();

        for (int i = 0; i < crossoverSeq.size(); i++) {
            if (!(testGs.getActiveTribeID() == getPlayerID())) {
                // Game state no longer under our control, stop repair
                break;
            }

            Action currentAction = crossoverSeq.get(i);
            boolean isValid = false;

            // Check if the current action is feasible. Feasibility is a read-only predicate
            // (isFeasible has no side effects - the mutation-path repair() already checks on
            // its live state directly), so no full-state copy: under STOP_COMPUTE a copy here
            // would overcharge crossover children relative to mutation children for the exact
            // same repair work.
            try {
                isValid = checkActionFeasibility(currentAction, testGs);
            } catch (Exception e) {
                isValid = false;
            }

            if (isValid) {
                // Action is valid, add it and advance state
                repairedSeq.add(currentAction);
                advance(testGs, currentAction);
                lastXoRepairKept++;
            } else {
                // Action is invalid - use greedy selection to find best replacement.
                // allGoodActions is read-only, so no copy (matches the mutation-path repair()).
                ArrayList<Action> allAvailableActions = this.allGoodActions(testGs, m_rnd);

                if (allAvailableActions.isEmpty()) {
                    // No actions available, stop repair
                    break;
                }

                Action bestAction = null;
                double bestScore = Double.NEGATIVE_INFINITY;
                int numTies = 0;

                for (Action candidateAction : allAvailableActions) {
                    GameState candidateGs = testGs.copy();
                    advance(candidateGs, candidateAction);
                    double score = heuristic.evaluateState(old_gs, candidateGs);

                    if (score > bestScore) {
                        bestScore = score;
                        bestAction = candidateAction;
                        numTies = 1;
                    } else if (score == bestScore) {
                        numTies++;
                        if (m_rnd.nextInt(numTies) == 0) {
                            bestAction = candidateAction;
                        }
                    }
                }

                if (bestAction != null) {
                    repairedSeq.add(bestAction);
                    advance(testGs, bestAction);
                    lastXoRepairReplaced++;
                } else {
                    // No viable action found, stop repair
                    break;
                }
            }
        }

        // Ensure repaired sequence ends with END_TURN
        if (!repairedSeq.isEmpty() && 
            repairedSeq.get(repairedSeq.size() - 1).getActionType() != Types.ACTION.END_TURN) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(testGs, m_rnd);
            Action endAction = null;
            for (Action a : allAvailableActions) {
                if (a.getActionType() == Types.ACTION.END_TURN) {
                    endAction = a;
                    break;
                }
            }
            if (endAction != null) {
                repairedSeq.add(endAction);
            }
        }

        return truncateAtEndTurn(repairedSeq);
    }
}