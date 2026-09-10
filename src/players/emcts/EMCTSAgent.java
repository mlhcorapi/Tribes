package players.emcts;

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
import core.data.dto.TurnData;
import core.data.dto.BridgeBurnEventData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;



public class EMCTSAgent extends Agent {

    private List<TurnData> turnDataLog = new ArrayList<>();
    private List<BridgeBurnEventData> bridgeBurnDataLog = new ArrayList<>();

    private Random m_rnd;
    private StateHeuristic heuristic;
    private EMCTSParams params;

    private EMCTSTreeNode root;

    /** v4 hook: snapshot of the INITIAL root sequence, captured exactly when the root is first built
     *  by randomActions() (before any search / bridge-burn promotion mutates it). Used by the v4 smoke
     *  test to verify all agents produced identical random initial sequences for the same (scenario,
     *  visit) seed — the paired-comparison guarantee. */
    private java.util.List<String> initialRootSequenceForV4 = java.util.Collections.emptyList();

    public java.util.List<String> getInitialRootSequenceForV4() {
        return initialRootSequenceForV4;
    }
    private EMCTSTreeNode bestNode;
    /** Expansion target: the highest-value node currently IN the live tree (the incumbent).
     *  Purely exploitative selection expands this node on every iteration. Distinct from
     *  {@link #bestNode}: bestNode is the best sequence EVER seen this turn (it survives
     *  pruning and supplies the final answer), while the incumbent is re-anchored onto the
     *  surviving subtree whenever a bridge burn prunes it out of the tree, so
     *  incumbent.value <= bestNode.value at all times. */
    private EMCTSTreeNode incumbent;
    /** Tree-shape check (game lifetime, never reset): max children observed on any single
     *  node. The 2026-08 selection fix makes failed mutations pile up as siblings under the
     *  incumbent, so this exceeds 1 as soon as any mutation fails to improve; the pre-fix
     *  selection degenerated the tree into a 1-child-per-node chain (always exactly 1). */
    private int maxChildrenOnANode;
    private GameState old_gs;

    private int fmCallsCount;
    private int fmCallsRun;
    private int totalNodesVisited;
    // Per-turn search telemetry (reset each planTurn): nodes scored, value sum (for the mean),
    // and distinct sequence keys (for the unique-sequence count).
    private int nodesEvaluated;
    private double sumNodeValue;
    private final java.util.HashSet<String> uniqueSeqKeys = new java.util.HashSet<>();
    // v5 operator telemetry (reset each planTurn). EMCTS is mutation-only, so every crossover (xo*)
    // field of the exposed OperatorTelemetryData stays zero — this is the baseline the crossover
    // variants are compared against. Observation-only: no advance/copy/eval, no ComputeMeter impact.
    private int mutEvents, mutBestGains, mutChildDuplicates, mutRepairKept, mutRepairReplaced;
    private double mutBestGainSum;
    private int lastRepairKept, lastRepairReplaced;   // per-repair() call; folded in by the main loop

    // ---- v6 realized-spend accounting (reset each planTurn); identical to the experimental agent's,
    // so the baseline and the crossover variants are measured by the same code. ----
    private double initComputeUnits;
    private long initAdvances, initCopies, initEvals;
    private int initFmCalls;
    private long initMillis;
    // Per-turn planning state: the engine calls act() once per action, so we plan the
    // entire turn once (a single phased search) and replay the cached best action
    // sequence across the turn's subsequent act() calls instead of re-searching.
    private ArrayList<Action> turnPlan = new ArrayList<>();
    private int plannedTick = -1;
    // Wall-clock baseline for event timestamps: the start of the turn (set when planning).
    private long turnStartTimeMillis;

    public EMCTSAgent(long seed, EMCTSParams params) {
        super(seed);
        m_rnd = new Random(seed);
        this.params = params;
    }

    @Override
    public List<TurnData> getTurnDataLog() {
        return turnDataLog;
    }

    @Override
    public core.data.dto.OperatorTelemetryData getOperatorTelemetry() {
        // Mutation-only baseline: every crossover (xo*) field is zero by construction.
        return new core.data.dto.OperatorTelemetryData(
                mutEvents, mutBestGains, mutBestGainSum, mutChildDuplicates,
                mutRepairKept, mutRepairReplaced,
                0, 0, 0.0, 0.0, 0.0, 0, 0, 0.0, 0, 0, 0);
    }

    @Override
    public List<BridgeBurnEventData> getBridgeBurnDataLog() {
        return bridgeBurnDataLog;
    }

    @Override
    public int getNodesVisited() {
        return totalNodesVisited;
    }

    /** Tree-shape check: max children observed on any single node over this agent's
     *  lifetime (one game in the experiments). 1 = the degenerate pre-fix chain;
     *  > 1 = the search tree really branches. See SelectionFixCheck. */
    public int getMaxBranching() {
        return maxChildrenOnANode;
    }

    @Override
    public Action act(GameState gs, ElapsedCpuTimer ect) {
        // The engine calls act() once per action. Plan the ENTIRE turn with a single
        // phased search on the first act() of the turn, then replay the cached best
        // action sequence on subsequent calls. A change in game tick marks a new turn.
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
     * best full action sequence. Records Initial/Final sequence values, nodesVisited
     * (search iterations), and bridge-burn events for this turn.
     */
    private void planTurn(GameState gs, ElapsedCpuTimer ect) {
        turnStartTimeMillis = System.currentTimeMillis();
        this.fmCallsCount = 0;
        this.totalNodesVisited = 0;
        this.nodesEvaluated = 0;
        this.sumNodeValue = 0.0;
        this.uniqueSeqKeys.clear();
        // v5 operator telemetry — fresh per planning episode.
        mutEvents = mutBestGains = mutChildDuplicates = mutRepairKept = mutRepairReplaced = 0;
        mutBestGainSum = 0.0;
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
        // Identical treatment to ExperimentalEMCTSAgent (see the full rationale there): the initial
        // sequence is shared across every config for a given (scenario, visit), and its cost scales
        // with branching factor, which differs systematically by scenario source. Measured, logged,
        // then excluded so the phase ceilings below bound SEARCH only. Bridge burning stays INSIDE
        // the budget.
        initComputeUnits = core.game.ComputeMeter.computeUnits();
        initAdvances = core.game.ComputeMeter.advances();
        initCopies   = core.game.ComputeMeter.copies();
        initEvals    = core.game.ComputeMeter.evals();
        initFmCalls  = this.fmCallsCount;
        initMillis   = System.currentTimeMillis() - turnStartTimeMillis;
        core.game.ComputeMeter.reset();

        bestNode = root;
        incumbent = root;
        fmCallsRun = 0;

        // ---- Phase-based bridge burning (FIXED burn count for every agent) ----
        // Identical schedule to the experimental agents: the turn's search is split into
        // params.depth (=21) phases and a bridge burn fires at each of the first depth-1 (=20)
        // phase boundaries, so the burn count is FIXED at 20 regardless of agent or budget. Each
        // phase gets an equal CUMULATIVE slice of the budget. Standard bridge burning (promote the
        // best child) spends no forward-model calls, so this baseline fills the whole budget with
        // search and performs all 20 burns.
        // Explicit params.num_timeMillis (> 0) gives the search a fixed slice independent of how long
        // initialization took — see AlgParams.num_timeMillis and the v6 calibration runner.
        long turnBudgetTime = params.num_timeMillis > 0 ? params.num_timeMillis : ect.remainingTimeMillis();
        ElapsedCpuTimer turnTimer = new ElapsedCpuTimer();
        long fmPerPhase      = Math.max(1, params.num_fmcalls      / Math.max(1, params.depth));
        long computePerPhase = Math.max(1, params.num_computeUnits / Math.max(1, params.depth));
        long itersPerPhase   = Math.max(1, params.num_iterations   / Math.max(1, params.depth));
        long timePerPhase    = Math.max(1, turnBudgetTime          / Math.max(1, params.depth));
        final int MAX_ITERS_PER_PHASE = 1_000_000; // safety net against a no-progress stall
        int numIters = 0;
        int bridgeBurningPhaseNumber = 0;

        for (int currentPhase = 0; currentPhase < params.depth; currentPhase++) {
            long fmCeiling        = (long) (currentPhase + 1) * fmPerPhase;
            double computeCeiling = (double) (currentPhase + 1) * computePerPhase;
            long iterCeiling      = (long) (currentPhase + 1) * itersPerPhase;
            long timeCeiling      = (long) (currentPhase + 1) * timePerPhase;
            int phaseStartIters = numIters;

            // Run iterations until this phase's cumulative budget ceiling is reached. We only
            // force an extra iteration PAST the ceiling when a non-final phase still has no
            // promotable child for its upcoming burn (standard burning normally inherits a
            // subtree), so the baseline simply fills the budget and performs all 20 burns.
            while (true) {
                boolean ceilingReached;
                if (params.stop_type == params.STOP_TIME) {
                    ceilingReached = turnTimer.elapsedMillis() >= timeCeiling;
                } else if (params.stop_type == params.STOP_ITERATIONS) {
                    ceilingReached = numIters >= iterCeiling;
                } else if (params.stop_type == params.STOP_COMPUTE) {
                    ceilingReached = core.game.ComputeMeter.computeUnits() >= computeCeiling;
                } else { // STOP_FMCALLS (default)
                    // Relative to the post-initialization baseline (see STOP_COMPUTE above).
                    ceilingReached = (fmCallsCount - initFmCalls) >= fmCeiling;
                }
                boolean needChildForBurn = (currentPhase < params.depth - 1) && root.getChildren().isEmpty();
                if (ceilingReached && !needChildForBurn) break;
                if ((numIters - phaseStartIters) >= MAX_ITERS_PER_PHASE) break;

                numIters++;
                fmCallsRun = 0;

                // Search iteration: expand the incumbent (purely exploitative selection),
                // mutate once, evaluate, update the incumbent.
                EMCTSTreeNode toMutate = nodeToExpand();
                EMCTSTreeNode child = mutate(toMutate, gs.copy());
                // v5 telemetry: operator stats (duplicate check BEFORE eval registers the key).
                mutEvents++;
                mutRepairKept += lastRepairKept; mutRepairReplaced += lastRepairReplaced;
                boolean childDuplicate = uniqueSeqKeys.contains(seqKey(child.getSequence()));
                double prevBest = bestNode.getValue();
                eval(gs.copy(), child);
                if (childDuplicate) mutChildDuplicates++;

                if (child.getValue() > prevBest) {
                    mutBestGains++; mutBestGainSum += child.getValue() - prevBest;
                    bestNode = child;
                }

                child.visited();

                // Purely exploitative incumbent update: a strictly better child becomes the
                // new expansion target; otherwise it stays a leaf under the incumbent. (No
                // UCB backpropagation: with a zero exploration term a node's score IS its
                // heuristic value, so there is nothing to propagate.)
                if (child.getValue() > incumbent.getValue()) {
                    incumbent = child;
                }
            }

            // Bridge burn at every phase boundary except the last: FIXED at depth-1 = 20 burns.
            if (currentPhase < params.depth - 1 && root.getChildren().size() > 0) {
                // Promote the root child on the incumbent's path, so the burn keeps the best
                // live sequence in the tree and search stays anchored on it. (In this
                // mutation-only tree that child is also the best immediate child: siblings
                // off the incumbent's path never exceeded the incumbent when created.) When
                // the incumbent IS the root - no mutation improved on it this turn so far -
                // fall back to the best immediate child: the burn count is fixed, so the
                // phase still promotes, and the incumbent re-anchors below.
                EMCTSTreeNode bestChild = childOfRootOnPathTo(incumbent);
                if (bestChild == null) bestChild = findBestChild(root);

                if (bestChild != null && bestChild != root) {
                    // Unlink old root branches except best path
                    for (EMCTSTreeNode child : root.getChildren()) {
                        if (child != bestChild) {
                            unlinkTree(child);
                        }
                    }

                    bestChild.unlink();  // Clears parent link safely
                    root = bestChild;

                    // Re-anchor the incumbent if this burn pruned it out of the live tree
                    // (only possible via the fallback above, i.e. incumbent == old root).
                    if (!isInLiveTree(incumbent)) {
                        incumbent = subtreeMax(root);
                    }

                    bridgeBurningPhaseNumber++;
                    // Log the best sequence value found SO FAR (bestNode), not the promoted root's
                    // value (which degrades as the search dives) - the monotonic expansion curve.
                    long expansionPhaseTimestamp = System.currentTimeMillis() - turnStartTimeMillis;
                    bridgeBurnDataLog.add(new BridgeBurnEventData(-1, gs.getTick(), expansionPhaseTimestamp, bridgeBurningPhaseNumber, bestNode.getValue(), this.fmCallsCount,
                            core.game.ComputeMeter.computeUnits()));   // v6 per-phase SEARCH spend
                }
            }
        }

        // Telemetry for this single turn-planning episode:
        // Initial = greedy root (pre-search); Final = best node found (post-search);
        // nodesVisited = number of search iterations this turn.
        // nodesVisited = forward-model calls this turn (the computational-workload metric;
        // dominated by sequence evaluations + greedy repair, in the thousands at real budgets).
        this.totalNodesVisited = this.fmCallsCount;
        double finalValue = bestNode.getValue();
        double avgNodeValue = nodesEvaluated > 0 ? sumNodeValue / nodesEvaluated : 0.0;
        turnDataLog.add(new TurnData(-1, gs.getTick(), scoreAtTurnStart, initialValue, finalValue, avgNodeValue,
                this.fmCallsCount, nodesEvaluated, uniqueSeqKeys.size(),
                // v6 realized SEARCH spend (post-init) + initialization spend + wall-clock split.
                core.game.ComputeMeter.computeUnits(), core.game.ComputeMeter.advances(),
                core.game.ComputeMeter.copies(), core.game.ComputeMeter.evals(),
                initComputeUnits, initAdvances, initCopies, initEvals, initFmCalls,
                System.currentTimeMillis() - turnStartTimeMillis, initMillis));

        // Cache the best full action sequence; act() replays it over the turn.
        ArrayList<Action> bestSeq = bestNode.getSequence();
        turnPlan = (bestSeq != null) ? new ArrayList<>(bestSeq) : new ArrayList<>();
    }

    @Override
    public Agent copy() {
        return null;
    }

    public void eval(GameState gs, EMCTSTreeNode node) {
        if(node.getGs() == null) {
            for (Action move : node.getSequence()) {
                advance(gs, move);
            }
            node.setGs(gs);
            node.setValue(heuristic.evaluateState(old_gs, gs));
        }else{
            node.setValue(heuristic.evaluateState(old_gs, node.getGs()));
        }
        // Telemetry: each eval() scores one candidate node. Count it (duplicates included),
        // accumulate its value (for the per-turn mean), and record its sequence key (for the
        // distinct-sequence count). Independent of bridge burning.
        nodesEvaluated++;
        sumNodeValue += node.getValue();
        uniqueSeqKeys.add(seqKey(node.getSequence()));
    }

    /** Canonical key for an action sequence (for de-duplication), composed from each action's
     *  content toString(). */
    private String seqKey(ArrayList<Action> seq) {
        if (seq == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Action a : seq) sb.append(a == null ? "_" : a.toString()).append('|');
        return sb.toString();
    }

    /**
     * Purely exploitative selection: the node expanded on every iteration is the
     * highest-value node currently in the live tree (the incumbent) - UCB with a zero
     * exploration term. The incumbent is maintained incrementally (updated when a child
     * improves on it; re-anchored when a bridge burn prunes it) rather than recovered by
     * greedy root-to-leaf descent: a descent that always follows the best child cannot
     * stop at an interior node, which is precisely the pre-fix bug - every new child,
     * better or worse, became the next expansion target, so the search degenerated into
     * an unguided random walk along a single mutation chain.
     */
    private EMCTSTreeNode nodeToExpand() {
        return incumbent != null ? incumbent : root;
    }

    /** The direct child of {@code root} on the path from the root down to {@code node};
     *  null when {@code node} is the root itself or is not in the live tree. */
    private EMCTSTreeNode childOfRootOnPathTo(EMCTSTreeNode node) {
        EMCTSTreeNode current = node;
        while (current != null && current.getParent() != root) {
            current = current.getParent();
        }
        return current;
    }

    /** True when {@code node}'s parent chain reaches the current root. */
    private boolean isInLiveTree(EMCTSTreeNode node) {
        for (EMCTSTreeNode current = node; current != null; current = current.getParent()) {
            if (current == root) return true;
        }
        return false;
    }

    /** Highest-value node in the subtree rooted at {@code node} (iterative, O(subtree)).
     *  Only called after a bridge burn that pruned the incumbent, so the cost is rare and
     *  bounded by the surviving subtree. */
    private EMCTSTreeNode subtreeMax(EMCTSTreeNode node) {
        EMCTSTreeNode best = node;
        ArrayDeque<EMCTSTreeNode> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            EMCTSTreeNode current = pending.pop();
            if (current.getValue() > best.getValue()) best = current;
            ArrayList<EMCTSTreeNode> children = current.getChildren();
            if (children != null) {
                for (EMCTSTreeNode c : children) {
                    if (c != null) pending.push(c);
                }
            }
        }
        return best;
    }

    /**
     * 1-ply greedy initialization: for each action in the sequence,
     * evaluate all available actions and select the one with the highest heuristic score.
     */
    private EMCTSTreeNode randomActions(GameState gs) {
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

        EMCTSTreeNode node = new EMCTSTreeNode(individual, null);
        node.setGs(gs.copy());
        return node;
    }


    private void advance(GameState gs, Action move) {
        this.fmCallsCount++;
        this.fmCallsRun++;
        gs.advance(move, true);
    }

    /** Truncate a sequence at the first END_TURN (single-turn plan). In solo games the active
     *  tribe does not change after END_TURN, so builders would otherwise run into future turns. */
    private ArrayList<Action> truncateAtEndTurn(ArrayList<Action> seq) {
        ArrayList<Action> out = new ArrayList<>();
        for (Action a : seq) {
            out.add(a);
            if (a != null && a.getActionType() == Types.ACTION.END_TURN) break;
        }
        return out;
    }

    //mutate a random action
    private EMCTSTreeNode mutate(EMCTSTreeNode node, GameState gs) {
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
        EMCTSTreeNode newNode = new EMCTSTreeNode(newSeq, node);
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
    private ArrayList<Action> repair(ArrayList<Action> child, GameState gs) {
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

    private boolean checkActionFeasibility(Action a, GameState gs) {
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
    private void extend(EMCTSTreeNode node, GameState gs) {
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
        EMCTSTreeNode newNode = new EMCTSTreeNode(newActions, node.getParent());
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
    private EMCTSTreeNode findBestChild(EMCTSTreeNode node) {
        ArrayList<EMCTSTreeNode> children = node.getChildren();
        if (children.isEmpty()) {
            return null;
        }

        EMCTSTreeNode bestChild = children.get(0);
        double bestValue = bestChild.getValue();

        for (EMCTSTreeNode child : children) {
            if (child.getValue() > bestValue) {
                bestValue = child.getValue();
                bestChild = child;
            }
        }

        return bestChild;
    }

    /**
     * Unlink a tree node and all its descendants to allow garbage collection.
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
     * <p>Rewriting it cannot change a result: {@link EMCTSTreeNode#unlink()} only sets
     * {@code parent = null}, so the operation is idempotent and order-independent. The heap traffic is
     * identical; only the stack depth changes, from O(tree depth) to O(1).
     */
    private void unlinkTree(EMCTSTreeNode node) {
        if (node == null) {
            return;
        }

        ArrayDeque<EMCTSTreeNode> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            EMCTSTreeNode current = pending.pop();
            ArrayList<EMCTSTreeNode> children = current.getChildren();
            if (children != null) {
                for (EMCTSTreeNode child : children) {
                    if (child != null) pending.push(child);
                }
            }
            current.unlink();
        }
    }
}