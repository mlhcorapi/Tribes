package players.tbets;

import core.Types;
import core.actions.Action;
import core.actions.cityactions.Build;
import core.actions.cityactions.CityAction;
import core.actions.cityactions.Spawn;
import core.actions.tribeactions.EndTurn;
import core.actions.tribeactions.ResearchTech;
import core.actions.unitactions.UnitAction;
import core.actors.Actor;
import core.actors.City;
import core.actors.units.Unit;
import core.data.dto.TurnData;
import core.game.ComputeMeter;
import core.game.GameState;
import players.Agent;
import players.heuristics.StateHeuristic;
import utils.ElapsedCpuTimer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * TBETS (Turn-Based Evolutionary Tree Search) for Tribes, ported from the C# implementation written
 * for TUBSTAP ({@code AI/TBETS/AI_TBETS.cs} and friends in the TUBSTAP_Taylor repository).
 *
 * <h2>What TBETS is</h2>
 * TBETS is a tree search whose unit of search is a WHOLE TURN, and whose expansion operators are
 * evolutionary. Every node holds one complete turn (an action sequence ending in END_TURN) plus the
 * state it produces; the children of a node are candidate turns for whoever plays next, so the tree
 * alternates between the agent's turns and the opponent's and deepens across turns rather than
 * within one. The search loop is:
 *
 * <ol>
 *   <li><b>Seed</b> the root with {@code numInit} independently generated candidate turns.</li>
 *   <li><b>Select</b> a node by descending from the root: at each level, stop with high probability
 *       while the node is under-expanded by a progressive-widening test
 *       ({@code children < k * descendants^alpha}), otherwise take the UCB-best child.</li>
 *   <li><b>Expand</b>. If the selected node has never been expanded, generate {@code startingPop}
 *       fresh candidate turns for it ("exploit" in the C#). If it has, EVOLVE its existing children:
 *       with probability {@code mutationRatio} mutate one tournament-selected child, otherwise
 *       uniformly cross over two of them. Either way the offspring is greedily repaired against the
 *       real forward model, because a turn is deeply path-dependent and a spliced sequence is
 *       usually not replayable as-is.</li>
 *   <li><b>Evaluate</b> the new child and <b>back up</b> a MINIMAX value: a node whose children are
 *       the agent's turns takes their maximum, a node whose children are the opponent's takes their
 *       minimum. (Values are always in the agent's frame.)</li>
 * </ol>
 *
 * <p>Contrast with the EMCTS family already in this codebase: EMCTS searches WITHIN one turn, splits
 * the turn budget into phases and commits to a prefix at each phase boundary (bridge burning). TBETS
 * never commits mid-turn; it keeps a population of complete turns and improves them, and its depth
 * dimension is future turns rather than later actions of this one.
 *
 * <h2>Two deliberate departures from the original</h2>
 *
 * <h3>1. Rollouts are off by default ({@link TBETSParams#useRollouts})</h3>
 * TUBSTAP's TBETS never evaluates a state directly. It scores a node by attaching two "phantom"
 * children - a scripted opponent turn, then a scripted turn of its own - and evaluating the state
 * two turns later ({@code TBETSStateEvaluator.EvaluateNodeFitness}). That is a rollout in all but
 * name, and it does not survive the move to Tribes:
 *
 * <ul>
 *   <li><b>Cost.</b> One scripted turn is tens of {@code advance()} calls, and the greedy variant
 *       adds a {@code copy() + advance() + evaluateState()} per candidate action per step. Measured:
 *       ~35x the root-seeding cost, and ~180 distinct sequences evaluated per turn instead of ~2200
 *       at the same compute budget.</li>
 *   <li><b>Observability.</b> Tribes hands each agent a fog-reduced state ({@code gs.copy(playerIdx)}
 *       in {@code Game}), so the opponent half of the rollout is played out over units the agent
 *       cannot see. TUBSTAP is fully observable.</li>
 *   <li><b>Applicability.</b> In a solo scenario the active tribe does not change on END_TURN, so
 *       there is no opponent turn to script at all.</li>
 * </ul>
 *
 * With rollouts off, a node is scored by {@code heuristic.evaluateState(rootState, node.state)} -
 * the same one-shot evaluation EMCTS and OEP use here, which is what makes a TBETS-vs-EMCTS
 * comparison at a fixed budget meaningful. On the scenario portfolio this default BEATS EMCTS at a
 * matched compute budget (+217 at 40k units, +251 at 80k, p&lt;0.001, 73-80% of scenarios), while
 * turning rollouts on LOSES to EMCTS (-145 / -162) despite producing the highest search values of
 * any arm - it optimises a two-turns-ahead value under a scripted continuation, which is a poor
 * proxy for the quality of the turn it commits to. See {@code analysis/TBETS_VS_EMCTS.md}. Set
 * {@code useRollouts = true} to restore the original behaviour; {@link TBETSParams#rolloutTurns}, {@link TBETSParams#rolloutMaxActionsPerTurn} and
 * {@link TBETSParams#rolloutGreedy} control it, and the opponent's scripted turn minimises the
 * agent's heuristic rather than maximising it (a single-perspective heuristic has no other sensible
 * reading of "the opponent plays well").
 *
 * <h3>2. The attack bias is a pluggable policy ({@link TBETSParams.Bias})</h3>
 * TUBSTAP's TBETS draws every random action through an 80% bias towards ATTACK actions
 * ({@code ATK_BIAS}, used by {@code GenerateRandomActions}, {@code GenerateBiasedAction} and hence by
 * mutation and repair). That constant is doing real work there and most of it does not transfer: in
 * TUBSTAP every unit acts every turn, the action space is (move, move-and-attack), and the score is
 * damage-driven, so "attack when you can" is close to a correct prior. In Tribes, ATTACK is one of
 * ~25 action types, it is feasible only when an enemy is adjacent and visible, and the score is
 * driven by cities, population, technology and territory. Measured over 8 scenarios, the fraction of
 * biased draws that found any feasible ATTACK was 4.3% in 10-turn games, 7.2% in 20 and 10.5% in 30:
 * an 80% attack prior therefore shifts the action distribution by only a few percent, mostly late,
 * and every other draw falls through to uniform.
 *
 * <p>Rather than hard-code a replacement, the single point where an action is drawn -
 * {@link #pickBiasedAction} - is a policy chosen by {@link TBETSParams#biasMode}, and every draw in
 * the agent (turn generation, mutation, repair after crossover) goes through it. See the enum for
 * the available policies; the default is {@link TBETSParams.Bias#NONE}, because no candidate
 * replacement has yet been shown to beat uniform on a sample size that could resolve it.
 *
 * <h2>Not ported</h2>
 * The C# agent caches its tree ACROSS engine calls ({@code savedTree}), returning one action per
 * call and, at the start of the next turn, trying to match the observed state against a predicted
 * opponent-turn child to reuse that subtree. This port plans the whole turn once per tick and
 * replays it (the convention every plan-based agent here follows, and what makes the per-turn budget
 * accounting comparable across agents). Cross-turn reuse is left out because the cache-hit test it
 * depends on - "did the opponent play the turn I predicted?" - cannot be evaluated under fog of war,
 * and at the default {@code maxPlies = 1} there is no predicted opponent turn to match in any case.
 */
public class TBETSAgent extends Agent {

    // ------------------------------------------------------------------ configuration

    private final Random m_rnd;
    private final TBETSParams params;
    private StateHeuristic heuristic;

    /** Effective tournament size; see {@link #tournamentSelect}. */
    private final int effTournamentSize;

    // ------------------------------------------------------------------ per-turn search state

    /** State at the start of the turn; the "old state" argument of every heuristic call. */
    private GameState rootState;
    private TBETSNode root;

    /** Cached plan for the current turn, replayed by {@link #act}. */
    private ArrayList<Action> turnPlan = new ArrayList<>();
    private int plannedTick = -1;
    private long turnStartTimeMillis;

    // ------------------------------------------------------------------ telemetry

    private final List<TurnData> turnDataLog = new ArrayList<>();
    private int fmCallsCount;
    private int totalNodesVisited;
    private int nodesEvaluated;
    private double sumNodeValue;
    private final HashSet<String> uniqueSeqKeys = new HashSet<>();
    private int searchIterations;

    /** Spend attributable to root seeding, measured and then (optionally) excluded from the budget. */
    private double seedComputeUnits;
    private long seedAdvances, seedCopies, seedEvals;
    private int seedFmCalls;
    private long seedMillis;

    /** Which bias branch fired, for the action-bias analysis. Cumulative over the game. */
    private long biasUniformPicks, biasGreedyPicks, biasAttackPicks, biasCategoryPicks, biasLearnedPicks;
    /** Draws where the selected policy wanted to fire but had no candidate and fell through. */
    private long biasFellThrough;

    // ------------------------------------------------------------------ learned action values

    private static final int A_MAX = Types.ACTION.values().length;
    private static final int Q_MAX = 80;
    private final double[][] sumDelta = new double[A_MAX][Q_MAX];
    private final int[][] nObs = new int[A_MAX][Q_MAX];
    private double globalSum;
    private long globalN;

    // ------------------------------------------------------------------ action categories

    /**
     * Coarse functional grouping of {@link Types.ACTION}, used by
     * {@link TBETSParams.Bias#CATEGORY_WEIGHTS}. This is the generalisation of TUBSTAP's
     * attack/move split to a domain where "attack" is not the only thing worth biasing towards.
     */
    public enum ActionCategory { AGGRESSION, EXPANSION, ECONOMY, TECH, UTILITY }

    public static ActionCategory categoryOf(Action a) {
        switch (a.getActionType()) {
            case ATTACK:
            case CONVERT:
                return ActionCategory.AGGRESSION;
            case CAPTURE:
            case EXAMINE:
            case MOVE:
            case CLIMB_MOUNTAIN:
                return ActionCategory.EXPANSION;
            case BUILD:
            case RESOURCE_GATHERING:
            case LEVEL_UP:
            case SPAWN:
            case BUILD_ROAD:
            case CLEAR_FOREST:
            case GROW_FOREST:
            case BURN_FOREST:
            case DESTROY:
                return ActionCategory.ECONOMY;
            case RESEARCH_TECH:
                return ActionCategory.TECH;
            default:
                return ActionCategory.UTILITY;
        }
    }

    // ------------------------------------------------------------------ construction

    public TBETSAgent(long seed, TBETSParams params) {
        super(seed);
        this.m_rnd = new Random(seed);
        this.params = params;

        // TUBSTAP runs a tournament of `tournamentSize` draws over the TOP `tournamentTopFraction`
        // of the fitness-SORTED sibling list. Sorting every sibling list on every iteration is O(m log m)
        // in the number of children, and at this project's larger budgets a node accumulates thousands
        // of children, so that sort would dominate the search. A plain tournament over the unsorted
        // list has the same selection pressure for an equivalent size: the expected rank of the winner
        // is m/(s+1) for a tournament of s, and topFraction*m/(tournamentSize+1) for TUBSTAP's scheme,
        // so s = (tournamentSize+1)/topFraction - 1 matches them. With the defaults (3, 0.2) that is 19.
        double frac = params.tournamentTopFraction > 0 ? params.tournamentTopFraction : 1.0;
        this.effTournamentSize = Math.max(2, (int) Math.round((params.tournamentSize + 1) / frac) - 1);
    }

    // ------------------------------------------------------------------ Agent interface

    @Override
    public Action act(GameState gs, ElapsedCpuTimer ect) {
        int tick = gs.getTick();
        if (tick != plannedTick) {
            planTurn(gs, ect);
            plannedTick = tick;
        }

        // Tier 1: next action of the planned turn, if it is still legal. Unlike EMCTSAgent, which
        // abandons the plan at the first infeasible step, this scans forward: a plan can contain one
        // step the real state invalidated (the engine's own RNG resolves Examine and LevelUp) while
        // the rest stays playable, and there is no reason to throw the rest away.
        Action next = null;
        while (next == null && !turnPlan.isEmpty()) {
            Action candidate = turnPlan.remove(0);
            if (candidate != null && checkActionFeasibility(candidate, gs)) next = candidate;
        }
        // Tier 2: the plan broke; fall back to a random legal action.
        if (next == null) {
            ArrayList<Action> available = this.allGoodActions(gs, m_rnd);
            if (available != null && !available.isEmpty()) {
                next = available.get(m_rnd.nextInt(available.size()));
            }
        }
        // Tier 3: nothing legal left.
        if (next == null) next = new EndTurn(gs.getActiveTribeID());
        return next;
    }

    @Override
    public Agent copy() {
        return null;
    }

    @Override public List<TurnData> getTurnDataLog()      { return turnDataLog; }
    @Override public int getNodesVisited()                { return totalNodesVisited; }

    public long getBiasUniformPicks()  { return biasUniformPicks; }
    public long getBiasGreedyPicks()   { return biasGreedyPicks; }
    public long getBiasAttackPicks()   { return biasAttackPicks; }
    public long getBiasCategoryPicks() { return biasCategoryPicks; }
    public long getBiasLearnedPicks()  { return biasLearnedPicks; }
    public long getBiasFellThrough()   { return biasFellThrough; }
    public int  getSearchIterations()  { return searchIterations; }

    // ------------------------------------------------------------------ turn planning

    /**
     * Runs one full TBETS search and caches the best candidate turn. Called once per tick; the
     * budget bookkeeping mirrors {@code EMCTSAgent.planTurn} so a TBETS row and an EMCTS row in the
     * same experiment are priced in the same currency.
     */
    private void planTurn(GameState gs, ElapsedCpuTimer ect) {
        turnStartTimeMillis = System.currentTimeMillis();
        fmCallsCount = 0;
        nodesEvaluated = 0;
        sumNodeValue = 0.0;
        searchIterations = 0;
        uniqueSeqKeys.clear();
        resetLearnedStats();
        ComputeMeter.reset();
        // Restores the global weights if a v6 agent ran on this thread before us (TBETS has no
        // calibrated profile of its own, so params.computeProfile is null).
        ComputeMeter.useProfile(params.computeProfile);

        heuristic = params.getStateHeuristic(playerID, allPlayerIDs);
        rootState = gs.copy();
        int scoreAtTurnStart = gs.getScore(playerID);

        root = new TBETSNode(new ArrayList<>(), null, 0);
        root.setState(gs.copy());

        // ---- Seed the root with numInit independently generated candidate turns. -----------------
        exploit(root, params.numInit);
        double initialValue = bestChildValue(root);

        // The seeding cost scales with the branching factor, which varies systematically by scenario;
        // charging it to the budget would make one budget mean different amounts of SEARCH on
        // different maps. Measured either way, excluded by default - the same treatment
        // EMCTSAgent/ExperimentalEMCTSAgent give their greedy initialization.
        seedComputeUnits = ComputeMeter.computeUnits();
        seedAdvances = ComputeMeter.advances();
        seedCopies = ComputeMeter.copies();
        seedEvals = ComputeMeter.evals();
        seedFmCalls = fmCallsCount;
        seedMillis = System.currentTimeMillis() - turnStartTimeMillis;
        // When seeding is excluded the meter restarts, so what it reads at the end IS the search
        // spend. When it is charged the meter keeps running, so the seeding portion has to be
        // subtracted out before logging - otherwise the "search" and "init" columns of TurnData would
        // overlap here but be disjoint for every other agent, and no cross-agent sum would be valid.
        if (params.excludeSeedingFromBudget) ComputeMeter.reset();

        // ---- Search ------------------------------------------------------------------------------
        long turnBudgetTime = params.num_timeMillis > 0 ? params.num_timeMillis : ect.remainingTimeMillis();
        // The timer starts here, i.e. AFTER seeding, so when seeding is charged its wall-clock cost
        // has to come off the ceiling explicitly - otherwise STOP_TIME would be the one budget mode
        // that quietly exempted seeding while the other three charged it.
        ElapsedCpuTimer turnTimer = new ElapsedCpuTimer();
        long timeCeiling = params.excludeSeedingFromBudget
                ? turnBudgetTime
                : Math.max(1, turnBudgetTime - seedMillis);
        int baseFmCalls = params.excludeSeedingFromBudget ? seedFmCalls : 0;

        while (searchIterations < params.maxIterations) {
            if (budgetExhausted(turnTimer, timeCeiling, baseFmCalls)) break;
            searchIterations++;

            TBETSNode selected = selectNode();
            if (selected == null) break;

            if (!selected.isExplored() && expandable(selected)) {
                exploit(selected, params.startingPop);
            } else if (!selected.getChildren().isEmpty()) {
                evolve(selected);
            } else {
                // Neither expandable nor evolvable: the tree cannot grow any further this turn.
                break;
            }
        }

        // ---- Commit ------------------------------------------------------------------------------
        TBETSNode best = bestChild(root);
        turnPlan = (best != null && best.getSequence() != null)
                ? new ArrayList<>(best.getSequence())
                : new ArrayList<>();
        totalNodesVisited = fmCallsCount;

        double finalValue = best != null ? best.getFitness() : initialValue;
        double avgNodeValue = nodesEvaluated > 0 ? sumNodeValue / nodesEvaluated : 0.0;
        double searchUnits = ComputeMeter.computeUnits();
        long searchAdvances = ComputeMeter.advances();
        long searchCopies = ComputeMeter.copies();
        long searchEvals = ComputeMeter.evals();
        if (!params.excludeSeedingFromBudget) {
            searchUnits -= seedComputeUnits;
            searchAdvances -= seedAdvances;
            searchCopies -= seedCopies;
            searchEvals -= seedEvals;
        }

        turnDataLog.add(new TurnData(-1, gs.getTick(), scoreAtTurnStart, initialValue, finalValue,
                avgNodeValue, fmCallsCount, nodesEvaluated, uniqueSeqKeys.size(),
                searchUnits, searchAdvances, searchCopies, searchEvals,
                seedComputeUnits, seedAdvances, seedCopies, seedEvals, seedFmCalls,
                System.currentTimeMillis() - turnStartTimeMillis, seedMillis));
    }

    private boolean budgetExhausted(ElapsedCpuTimer timer, long timeCeiling, int baseFmCalls) {
        if (params.stop_type == params.STOP_TIME)       return timer.elapsedMillis() >= timeCeiling;
        if (params.stop_type == params.STOP_ITERATIONS) return searchIterations >= params.num_iterations;
        // Charged mode intentionally lets the meter include seeding: that is what charging means, and
        // it is why a config with expensive seeding gets fewer search iterations rather than free ones.
        if (params.stop_type == params.STOP_COMPUTE)    return ComputeMeter.computeUnits() >= params.num_computeUnits;
        return (fmCallsCount - baseFmCalls) >= params.num_fmcalls;   // STOP_FMCALLS
    }

    // ------------------------------------------------------------------ selection

    /**
     * Descends from the root, stopping either where progressive widening says the node is
     * under-expanded or where the UCB-best child is a frontier node the tree is not allowed to grow
     * past. Mirrors {@code TBETSNodeSelector.SelectNode}.
     */
    private TBETSNode selectNode() {
        TBETSNode current = root;
        while (current.getChildren().size() > 1) {
            // Depth is uniform across siblings, so if the ply below this node is past maxPlies then
            // NO child can be expanded and there is nothing to descend into. Checking that first
            // matters: without it, every iteration at the default maxPlies = 1 would score all of
            // the root's children through ucbChild and then throw the answer away, and the root
            // accumulates one child per iteration - O(iterations^2) of pure waste per turn.
            if (current.getDepth() + 1 >= params.maxPlies) break;
            if (underExpanded(current) && m_rnd.nextDouble() < params.wideningStopProb) break;

            TBETSNode next = ucbChild(current);
            if (next == null || !expandable(next)) break;
            current = next;
        }
        return current;
    }

    /** {@code children < k * descendants^alpha}: too few children for how much work went below here. */
    private boolean underExpanded(TBETSNode node) {
        double threshold = params.wideningK * Math.pow(Math.max(1, node.getDescendants()), params.wideningAlpha);
        return node.getChildren().size() < threshold;
    }

    /**
     * Whether the tree may grow children under this node. Beyond the leaf and depth tests, the
     * state's active tribe must agree with the alternation the tree assumes: if it does not - a solo
     * scenario, where END_TURN does not pass control, or a 3+ tribe game past the first opponent -
     * then a child here would not be the turn the minimax backup thinks it is, so the tree stops.
     */
    private boolean expandable(TBETSNode node) {
        if (node.isLeaf() || node.getState() == null) return false;
        if (node.getDepth() >= params.maxPlies) return false;
        boolean activeIsMe = node.getState().getActiveTribeID() == playerID;
        return activeIsMe == node.childrenAreMine();
    }

    /**
     * UCB over children, oriented so the agent's turns maximise and the opponent's minimise.
     *
     * <p>The exploitation term is normalised to [0,1] against the sibling range before the
     * exploration term is added, the same treatment {@code players.mcts.SingleTreeNode} gives its
     * bounds. This is NOT cosmetic. TUBSTAP's evaluator returns a value already in [0,1], so its
     * {@code C = sqrt(2)} is correctly scaled; the Tribes heuristics return unbounded differences
     * that ran from single digits to over 150 in the probe runs, against an exploration term of
     * order 1. Used raw, the exploration term would be numerically irrelevant and UCB would
     * silently degenerate into greedy selection - with the constant still sitting there looking
     * like it did something.
     */
    private TBETSNode ucbChild(TBETSNode node) {
        double sign = node.childrenAreMine() ? 1.0 : -1.0;
        double parentVisits = node.getDescendants() + 1.0;

        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (TBETSNode child : node.getChildren()) {
            double v = sign * child.getFitness();
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        double range = hi - lo;

        TBETSNode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;

        for (TBETSNode child : node.getChildren()) {
            double childVisits = child.getDescendants() + 1.0;
            // All siblings equal: exploitation carries no information, so leave it flat and let the
            // exploration term decide.
            double exploit = range > params.epsilon ? (sign * child.getFitness() - lo) / range : 0.0;
            double score = exploit + params.ucbC * Math.sqrt(Math.log(parentVisits) / childVisits);
            if (score > bestScore) {
                bestScore = score;
                best = child;
                numTies = 1;
            } else if (score == bestScore) {
                numTies++;
                if (m_rnd.nextInt(numTies) == 0) best = child;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ expansion

    /** {@code Exploit}: give an unexpanded node its initial population of candidate turns. */
    private void exploit(TBETSNode node, int numChildren) {
        for (int i = 0; i < numChildren; i++) {
            GameState gs = node.getState().copy();
            ArrayList<Action> seq = generateTurn(gs);
            addChild(node, seq, gs);
        }
        node.setExplored(true);
    }

    /** Evolves an already-expanded node: mutate one child, or cross two over. */
    private void evolve(TBETSNode node) {
        ArrayList<TBETSNode> kids = node.getChildren();
        if (kids.isEmpty()) return;

        boolean mutateInstead = m_rnd.nextDouble() < params.mutationRatio;
        if (mutateInstead || kids.size() == 1) {
            TBETSNode template = tournamentSelect(node, null);
            if (template != null) mutate(node, template);
        } else {
            TBETSNode p1 = tournamentSelect(node, null);
            TBETSNode p2 = tournamentSelect(node, p1);
            if (p1 != null && p2 != null) crossover(node, p1, p2);
        }
    }

    /**
     * Tournament selection among a node's children, oriented like {@link #ucbChild}. See the
     * constructor for why this is a plain tournament of {@link #effTournamentSize} rather than
     * TUBSTAP's "sort, then draw from the top fraction".
     */
    private TBETSNode tournamentSelect(TBETSNode node, TBETSNode exclude) {
        ArrayList<TBETSNode> kids = node.getChildren();
        if (kids.isEmpty()) return null;
        double sign = node.childrenAreMine() ? 1.0 : -1.0;

        TBETSNode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < effTournamentSize; i++) {
            TBETSNode c = kids.get(m_rnd.nextInt(kids.size()));
            if (c == exclude) continue;
            double score = sign * c.getFitness();
            if (best == null || score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (best == null) {
            // Every draw hit the excluded node; take any other child.
            for (TBETSNode c : kids) if (c != exclude) return c;
        }
        return best;
    }

    /**
     * {@code MutateNode}: replay the template turn from the parent's state, resampling each action
     * with probability {@code mutationRate} and repairing any action that is no longer legal because
     * an earlier one changed.
     */
    private void mutate(TBETSNode node, TBETSNode template) {
        GameState gs = node.getState().copy();
        int owner = gs.getActiveTribeID();
        ArrayList<Action> seq = new ArrayList<>();

        for (Action orig : template.getSequence()) {
            if (gs.isGameOver() || gs.getActiveTribeID() != owner || seq.size() >= params.nodeSize) break;

            // Two different things land in the same else-branch, and they are drawn differently:
            // resample = a deliberate mutation (generation), otherwise the original action is no
            // longer legal and this is a repair.
            boolean resample = m_rnd.nextDouble() < params.mutationRate;
            Action chosen;
            if (!resample && checkActionFeasibility(orig, gs)) {
                chosen = orig;
            } else {
                ArrayList<Action> feasible = this.allGoodActions(gs, m_rnd);
                if (feasible.isEmpty()) break;
                chosen = resample ? pickBiasedAction(gs, feasible) : pickRepairAction(gs, feasible);
            }
            if (chosen == null) break;

            advance(gs, chosen);
            seq.add(chosen);
            if (chosen.getActionType() == Types.ACTION.END_TURN) break;
        }

        appendEndTurn(seq, gs, owner);
        addChild(node, seq, gs);
    }

    /**
     * {@code CrossoverNodes}: uniform per-position crossover of two sibling turns, then a repair pass
     * against the real forward model. Every action the spliced prefix has invalidated is redrawn
     * through {@link #pickRepairAction} - the whole sequence is a repair, none of it is mutation.
     */
    private void crossover(TBETSNode node, TBETSNode a, TBETSNode b) {
        ArrayList<Action> s1 = a.getSequence();
        ArrayList<Action> s2 = b.getSequence();
        int n = Math.min(s1.size(), s2.size());

        ArrayList<Action> crossed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) crossed.add(m_rnd.nextBoolean() ? s1.get(i) : s2.get(i));

        GameState gs = node.getState().copy();
        int owner = gs.getActiveTribeID();
        ArrayList<Action> seq = new ArrayList<>();

        for (Action act : crossed) {
            if (gs.isGameOver() || gs.getActiveTribeID() != owner || seq.size() >= params.nodeSize) break;

            Action chosen;
            if (checkActionFeasibility(act, gs)) {
                chosen = act;
            } else {
                ArrayList<Action> feasible = this.allGoodActions(gs, m_rnd);
                if (feasible.isEmpty()) break;
                chosen = pickRepairAction(gs, feasible);   // a splice broke it: repair, not mutation
            }
            if (chosen == null) break;

            advance(gs, chosen);
            seq.add(chosen);
            if (chosen.getActionType() == Types.ACTION.END_TURN) break;
        }

        appendEndTurn(seq, gs, owner);
        addChild(node, seq, gs);
    }

    /**
     * Builds one complete turn for whoever is active in {@code gs}, advancing {@code gs} through it.
     * Every action is drawn through {@link #pickBiasedAction}.
     */
    private ArrayList<Action> generateTurn(GameState gs) {
        ArrayList<Action> seq = new ArrayList<>();
        int owner = gs.getActiveTribeID();

        while (!gs.isGameOver() && gs.getActiveTribeID() == owner && seq.size() < params.nodeSize) {
            ArrayList<Action> feasible = this.allGoodActions(gs, m_rnd);
            if (feasible.isEmpty()) break;

            Action a = pickBiasedAction(gs, feasible);
            if (a == null) break;

            advance(gs, a);
            seq.add(a);
            if (a.getActionType() == Types.ACTION.END_TURN) break;
        }

        appendEndTurn(seq, gs, owner);
        return seq;
    }

    /** Closes a turn with END_TURN (and advances into it) unless it already ended or the game is over. */
    private void appendEndTurn(ArrayList<Action> seq, GameState gs, int owner) {
        if (!seq.isEmpty() && seq.get(seq.size() - 1).getActionType() == Types.ACTION.END_TURN) return;
        if (gs.isGameOver() || gs.getActiveTribeID() != owner) return;

        EndTurn end = new EndTurn(owner);
        if (checkActionFeasibility(end, gs)) {
            advance(gs, end);
            seq.add(end);
        }
    }

    /**
     * Registers a freshly built turn as a child of {@code parent}: rejects an action-identical
     * sibling (as {@code AddNode} does), evaluates it, and backs its value up the tree.
     */
    private void addChild(TBETSNode parent, ArrayList<Action> seq, GameState resultingState) {
        if (seq == null || seq.isEmpty()) return;

        String key = seqKey(seq);
        TBETSNode child = new TBETSNode(seq, parent, parent.getDepth() + 1);
        child.setState(resultingState);
        if (!parent.addChild(child, key)) return;   // action-identical sibling already present
        child.incDescendants();

        evaluate(child);

        // Release the state of any node that can never be expanded. Nothing reads it again: exploit,
        // mutate and crossover all replay from the PARENT's state and only need a sibling's action
        // SEQUENCE, and this node's own evaluation has just happened.
        //
        // This is not an optimisation, it is what makes the agent runnable. A GameState is the
        // heaviest object here, the root gains one child per search iteration, and at the default
        // maxPlies = 1 no child is ever expanded - so holding them kept O(iterations) live states per
        // searching thread. At 40k compute-units (~3000 iterations) across 12 parallel scenarios that
        // filled a 12 GB heap and drove the run into GC thrash. Now it is O(1).
        if (child.isLeaf() || child.getDepth() >= params.maxPlies) {
            child.setState(null);
        }

        backup(child.getParent());
    }

    /** Minimax backup from a node up to the root, stopping as soon as a level is unchanged. */
    private void backup(TBETSNode from) {
        TBETSNode n = from;
        while (n != null) {
            if (!n.backup()) break;
            n = n.getParent();
        }
    }

    // ------------------------------------------------------------------ evaluation

    private void evaluate(TBETSNode node) {
        GameState s = node.getState();
        if (s.isGameOver()) node.setLeaf(true);

        double value = (params.useRollouts && !s.isGameOver())
                ? rollout(s)
                : heuristic.evaluateState(rootState, s);

        node.setFitness(value);
        nodesEvaluated++;
        sumNodeValue += value;
        uniqueSeqKeys.add(seqKey(node.getSequence()));

        if (params.biasMode == TBETSParams.Bias.LEARNED_DELTA
                || params.repairBias == TBETSParams.Bias.LEARNED_DELTA) {
            creditSequence(node, value);
        }
    }

    /**
     * TUBSTAP's phantom-node evaluation: play {@code rolloutTurns} scripted turns forward and
     * evaluate the state that results. Off by default - see the class javadoc for why.
     */
    private double rollout(GameState from) {
        GameState s = from.copy();

        for (int t = 0; t < params.rolloutTurns && !s.isGameOver(); t++) {
            int owner = s.getActiveTribeID();
            int acted = 0;

            while (!s.isGameOver() && s.getActiveTribeID() == owner
                    && acted < params.rolloutMaxActionsPerTurn) {
                ArrayList<Action> feasible = this.allGoodActions(s, m_rnd);
                if (feasible.isEmpty()) break;

                ArrayList<Action> pool = withoutEndTurn(feasible);
                Action a;
                if (pool.isEmpty()) {
                    a = endTurnOf(feasible);
                } else if (params.rolloutGreedy) {
                    // The heuristic only has the agent's perspective, so the scripted opponent plays
                    // to minimise it. This is the analogue of TUBSTAP's SimulateTurn, which scripts
                    // both sides with the same damage-based rules read from the acting side.
                    a = greedyBest(s, pool, owner == playerID ? 1.0 : -1.0);
                } else {
                    a = pool.get(m_rnd.nextInt(pool.size()));
                }
                if (a == null) break;

                advance(s, a);
                acted++;
                if (a.getActionType() == Types.ACTION.END_TURN) break;
            }

            if (!s.isGameOver() && s.getActiveTribeID() == owner) {
                EndTurn end = new EndTurn(owner);
                if (checkActionFeasibility(end, s)) advance(s, end);
            }
            // Solo scenario (or the game ended): control never left this tribe, so there is no next
            // turn to script.
            if (s.getActiveTribeID() == owner) break;
        }

        return heuristic.evaluateState(rootState, s);
    }

    // ------------------------------------------------------------------ action bias

    /**
     * The single place an action is drawn - the replacement for TUBSTAP's {@code ATK_BIAS}. Used by
     * turn generation, by mutation's resample step, and by the repair inside mutation and crossover,
     * exactly as {@code GenerateBiasedAction} is in the C#.
     *
     * <p>END_TURN is held back unless it is the only feasible action, so a biased draw cannot end the
     * turn on its first step (the same guard {@code EMCTSAgent.randomActions} uses).
     */
    private Action pickBiasedAction(GameState gs, ArrayList<Action> feasible) {
        return pickAction(gs, feasible, params.biasMode, params.biasStrength);
    }

    /**
     * The draw made when an action has to be REPLACED rather than generated. Same policy as
     * generation unless {@link TBETSParams#repairBias} splits them; see that field for why one might.
     */
    private Action pickRepairAction(GameState gs, ArrayList<Action> feasible) {
        if (params.repairBias == null) return pickBiasedAction(gs, feasible);
        return pickAction(gs, feasible, params.repairBias, params.repairBiasStrength);
    }

    private Action pickAction(GameState gs, ArrayList<Action> feasible, TBETSParams.Bias mode, double strength) {
        ArrayList<Action> pool = withoutEndTurn(feasible);
        if (pool.isEmpty()) return endTurnOf(feasible);

        // At maxPlies >= 2 this also builds the OPPONENT's turns, and the heuristic only speaks in
        // the agent's frame, so the value-driven policies flip sign there: a well-played opponent
        // turn is one that minimises the agent's evaluation.
        double sign = (gs.getActiveTribeID() == playerID) ? 1.0 : -1.0;

        switch (mode) {
            case NONE:
                biasUniformPicks++;
                return pool.get(m_rnd.nextInt(pool.size()));

            case ATTACK: {
                if (m_rnd.nextDouble() < strength) {
                    ArrayList<Action> attacks = new ArrayList<>();
                    for (Action a : pool) if (a.getActionType() == Types.ACTION.ATTACK) attacks.add(a);
                    if (!attacks.isEmpty()) {
                        biasAttackPicks++;
                        return attacks.get(m_rnd.nextInt(attacks.size()));
                    }
                    biasFellThrough++;
                }
                biasUniformPicks++;
                return pool.get(m_rnd.nextInt(pool.size()));
            }

            case CATEGORY_WEIGHTS: {
                if (m_rnd.nextDouble() < strength) {
                    Action a = pickByCategory(pool);
                    if (a != null) { biasCategoryPicks++; return a; }
                    biasFellThrough++;
                }
                biasUniformPicks++;
                return pool.get(m_rnd.nextInt(pool.size()));
            }

            case GREEDY_EPSILON: {
                if (m_rnd.nextDouble() < strength) {
                    biasGreedyPicks++;
                    return greedyBest(gs, pool, sign);
                }
                biasUniformPicks++;
                return pool.get(m_rnd.nextInt(pool.size()));
            }

            case LEARNED_DELTA: {
                if (m_rnd.nextDouble() < strength) {
                    biasLearnedPicks++;
                    return bestByLearnedValue(gs, pool, sign);
                }
                biasUniformPicks++;
                return pool.get(m_rnd.nextInt(pool.size()));
            }
        }
        return pool.get(m_rnd.nextInt(pool.size()));
    }

    /** Roulette over {@link ActionCategory} weights, then uniform inside the drawn category. */
    private Action pickByCategory(ArrayList<Action> pool) {
        int nCats = ActionCategory.values().length;
        double[] weight = new double[nCats];
        double total = 0.0;
        for (Action a : pool) {
            int c = categoryOf(a).ordinal();
            if (weight[c] == 0.0) {
                double w = (c < params.categoryWeights.length) ? params.categoryWeights[c] : 1.0;
                if (w <= 0.0) continue;
                weight[c] = w;
                total += w;
            }
        }
        if (total <= 0.0) return null;

        double r = m_rnd.nextDouble() * total;
        int picked = -1;
        for (int c = 0; c < nCats; c++) {
            if (weight[c] <= 0.0) continue;
            r -= weight[c];
            if (r <= 0.0) { picked = c; break; }
        }
        if (picked < 0) return null;

        ArrayList<Action> bucket = new ArrayList<>();
        for (Action a : pool) if (categoryOf(a).ordinal() == picked) bucket.add(a);
        return bucket.isEmpty() ? null : bucket.get(m_rnd.nextInt(bucket.size()));
    }

    /**
     * 1-ply greedy: copy, advance and evaluate every candidate, keep the best (uniform tie-break).
     * {@code sign} is +1 to maximise the agent's heuristic and -1 to minimise it, so the same routine
     * scripts the opponent inside a rollout.
     *
     * <p>This is the expensive policy: one {@code copy() + advance() + evaluateState()} per candidate
     * action per draw. It is the same greedy used by EMCTS's initialization and repair.
     */
    private Action greedyBest(GameState gs, ArrayList<Action> pool, double sign) {
        Action best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;

        for (Action candidate : pool) {
            GameState test = gs.copy();
            advance(test, candidate);
            double score = sign * heuristic.evaluateState(rootState, test);

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                numTies = 1;
            } else if (score == bestScore) {
                numTies++;
                if (m_rnd.nextInt(numTies) == 0) best = candidate;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ learned action values

    /**
     * Shrunk mean value of an action key: {@code (sum + m*globalMean) / (n + m)}. Same estimator as
     * {@code Stage1BiasedAgent}, so an empty table means every key scores the global mean and the
     * uniform tie-break makes the policy uniform.
     */
    private double qhat(int a, int q) {
        double gm = globalN > 0 ? globalSum / globalN : 0.0;
        return (sumDelta[a][q] + params.learnedShrinkageM * gm) / (nObs[a][q] + params.learnedShrinkageM);
    }

    private Action bestByLearnedValue(GameState gs, ArrayList<Action> pool, double sign) {
        Action best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (Action candidate : pool) {
            double v = sign * qhat(actionOrd(candidate), qualifierOf(candidate, gs));
            if (v > bestScore) {
                bestScore = v;
                best = candidate;
                numTies = 1;
            } else if (v == bestScore) {
                numTies++;
                if (m_rnd.nextInt(numTies) == 0) best = candidate;
            }
        }
        return best;
    }

    /**
     * Credit assignment for {@link TBETSParams.Bias#LEARNED_DELTA}. A TBETS node is a whole turn, so
     * unlike Stage 1 - where a mutation inserts exactly one action and the paired delta belongs to it
     * - there is no single action to credit. Every action key in the sequence therefore receives an
     * equal share of the turn's value. That is a Monte-Carlo credit assignment: noisier per sample
     * than Stage 1's paired delta, but it gets 20 observations per evaluated node instead of one.
     *
     * <p>Values are already differences against the start of the turn (the heuristics take an old
     * state), so no separate baseline is subtracted; the shrinkage centres the estimates on the
     * running global mean.
     */
    private void creditSequence(TBETSNode node, double value) {
        // Only the agent's own turns; an opponent turn's actions are not ones the agent will pick,
        // and its value points the other way.
        if (!node.isMine()) return;

        ArrayList<Action> seq = node.getSequence();
        if (seq == null || seq.isEmpty()) return;

        // Qualifiers are resolved against the parent's state: it is the one state we still hold that
        // the actions were legal in, and it is only a naming key, not a simulation.
        GameState ctx = node.getParent() != null ? node.getParent().getState() : rootState;
        double share = value / seq.size();

        for (Action a : seq) {
            if (a.getActionType() == Types.ACTION.END_TURN) continue;
            int ai = actionOrd(a);
            int qi = ctx != null ? qualifierOf(a, ctx) : 0;
            sumDelta[ai][qi] += share;
            nObs[ai][qi]++;
            globalSum += share;
            globalN++;
        }
    }

    private void resetLearnedStats() {
        for (int a = 0; a < A_MAX; a++) {
            java.util.Arrays.fill(sumDelta[a], 0.0);
            java.util.Arrays.fill(nObs[a], 0);
        }
        globalSum = 0.0;
        globalN = 0;
    }

    private int actionOrd(Action a) {
        int o = a.getActionType().ordinal();
        return o < A_MAX ? o : A_MAX - 1;
    }

    /** Mid-granularity qualifier (actor or tech), matching {@code Stage1BiasedAgent.qualifierOf}. */
    private int qualifierOf(Action a, GameState gs) {
        try {
            int q = 0;
            if (a instanceof UnitAction) {
                Actor act = gs.getActor(((UnitAction) a).getUnitId());
                q = (act instanceof Unit) ? ((Unit) act).getType().ordinal() + 1 : 0;
            } else if (a instanceof Build) {
                q = ((Build) a).getBuildingType().ordinal() + 1;
            } else if (a instanceof Spawn) {
                q = ((Spawn) a).getUnitType().ordinal() + 1;
            } else if (a instanceof ResearchTech) {
                q = ((ResearchTech) a).getTech().ordinal() + 1;
            }
            return (q >= 0 && q < Q_MAX) ? q : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void advance(GameState gs, Action move) {
        fmCallsCount++;
        gs.advance(move, true);
    }

    /** Copy of {@code EMCTSAgent.checkActionFeasibility}: cheap legality test, no state copy. */
    private boolean checkActionFeasibility(Action a, GameState gs) {
        if (gs.isGameOver()) return false;

        if (a instanceof UnitAction) {
            int unitId = ((UnitAction) a).getUnitId();
            Actor act = gs.getActor(unitId);
            if (!(act instanceof Unit) || act.getTribeId() != gs.getActiveTribeID()) return false;
        } else if (a instanceof CityAction) {
            int cityId = ((CityAction) a).getCityId();
            Actor act = gs.getActor(cityId);
            if (!(act instanceof City) || act.getTribeId() != gs.getActiveTribeID()) return false;
        }

        try {
            return a.isFeasible(gs);
        } catch (Exception e) {
            return false;
        }
    }

    private ArrayList<Action> withoutEndTurn(ArrayList<Action> actions) {
        ArrayList<Action> pool = new ArrayList<>(actions.size());
        for (Action a : actions) if (a.getActionType() != Types.ACTION.END_TURN) pool.add(a);
        return pool;
    }

    private Action endTurnOf(ArrayList<Action> actions) {
        for (Action a : actions) if (a.getActionType() == Types.ACTION.END_TURN) return a;
        return null;
    }

    private TBETSNode bestChild(TBETSNode node) {
        TBETSNode best = null;
        for (TBETSNode c : node.getChildren()) {
            if (best == null || c.getFitness() > best.getFitness()) best = c;
        }
        return best;
    }

    private double bestChildValue(TBETSNode node) {
        TBETSNode best = bestChild(node);
        return best != null ? best.getFitness() : 0.0;
    }

    /** Canonical key for an action sequence, used for de-duplication and the unique-sequence count. */
    private String seqKey(ArrayList<Action> seq) {
        if (seq == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Action a : seq) sb.append(a == null ? "_" : a.toString()).append('|');
        return sb.toString();
    }
}
