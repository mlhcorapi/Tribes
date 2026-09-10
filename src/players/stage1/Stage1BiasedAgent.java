package players.stage1;

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
import players.ExperimentalEMCTS.ExperimentalEMCTSTreeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * STAGE 1 of the action-bias line (see {@code analysis/STAGE1_PLAN.md}): EMCTS whose mutation
 * operator LEARNS, within the current turn, which actions pay — and optionally uses the same
 * statistics to shortlist the greedy repair.
 *
 * <p><b>What is learned.</b> The running mean of the paired delta {@code childValue - parentValue},
 * credited to the inserted action and — optionally, negated — to the removed one. Every observation
 * is recorded at all three granularities at once (Coarse: type only; Mid: type + actor/tech
 * qualifier; Fine: full action identity), because they are three aggregations of one credit stream
 * rather than three experiments. Estimates are shrunk toward the running global mean with prior
 * strength {@code shrinkageM}. Statistics reset at the start of every planning turn (Stage-0:
 * per-turn scope; no decay — decay lost in all twelve Stage-0 cells).
 *
 * <p><b>How it is used.</b> Two decisions read the table, at <i>independently configurable</i>
 * granularities ({@code insertKeys}, {@code repairKeys}), because D2 says granularity should track
 * whether a decision is corrected downstream.
 * <ul>
 *   <li><i>Biased insertion</i> (epsilon-greedy): with probability {@code 1 - epsilon} the mutation
 *       inserts the feasible action with the highest Q instead of a uniform draw. Ties (e.g. all
 *       keys unseen, all at the prior) break uniformly, so an empty table degrades to uniform. An
 *       insertion is a proposal the sequence is scored on afterwards, so abstraction generalises.</li>
 *   <li><i>Repair</i> ({@code repairMode}): the base class pays copy+advance+eval for EVERY feasible
 *       candidate (~300-450 at mid-game branching factors, ~69% of the budget).
 *       {@code SHORTLIST_K} ranks by Q (free — no ComputeMeter charge) and pays for only the top K;
 *       {@code ZERO_EVAL} takes the Q-argmax with no forward-model call at all. Under a fixed
 *       compute budget the saving is reinvested as extra search iterations automatically. A
 *       zero-eval choice is a commitment nothing checks, so precision matters.</li>
 * </ul>
 *
 * <p><b>Baseline contract.</b> With every Stage1Params default ({@code epsilon = 1},
 * {@code repairMode = FULL}, {@code shortlistK = 0})
 * the agent consumes the identical RNG stream and makes the identical forward-model calls as plain
 * {@link ExperimentalEMCTSAgent}: the epsilon coin is not flipped at epsilon >= 1, bookkeeping is
 * read-only, and repair delegates to super. {@code Stage1Runner verify} asserts bit-equality before
 * every run. Crossover must stay off (constructor-enforced), so every child comes from
 * {@link #mutate} and is scored by the immediately following {@link #eval} — the pairing the
 * credit assignment relies on.
 */
public class Stage1BiasedAgent extends ExperimentalEMCTSAgent {

    /** Key-space bounds: ACTION ordinal x qualifier ordinal (+1; 0 = none/unknown). */
    private static final int A_MAX = 40, Q_MAX = 80;

    private final Stage1Params p1;

    /**
     * The learned statistics at every granularity at once.
     *
     * <p>All three are maintained from the SAME observations: one paired delta is recorded against
     * the inserted action's coarse key, its mid key and (when any setting needs it) its fine key.
     * They are three aggregations of one credit stream, not three experiments — which is what lets
     * insertion read one granularity while repair reads another, the arrangement D2 is about. The
     * shrinkage prior is the global mean, which is the mean of every delta and therefore shared.
     */
    private static final class Table {
        final double[] cSum = new double[A_MAX];
        final int[] cN = new int[A_MAX];
        final double[][] mSum = new double[A_MAX][Q_MAX];
        final int[][] mN = new int[A_MAX][Q_MAX];
        /** Fine keys are unbounded, so they need a map; created only when a setting asks for FINE. */
        final java.util.HashMap<String, double[]> fine;
        double globalSum;
        long globalN;

        Table(boolean withFine) { fine = withFine ? new java.util.HashMap<>(4096) : null; }

        void reset() {
            java.util.Arrays.fill(cSum, 0.0);
            java.util.Arrays.fill(cN, 0);
            for (int a = 0; a < A_MAX; a++) {
                java.util.Arrays.fill(mSum[a], 0.0);
                java.util.Arrays.fill(mN[a], 0);
            }
            if (fine != null) fine.clear();
            globalSum = 0.0; globalN = 0;
        }

        void add(int a, int q, String fineKey, double delta) {
            cSum[a] += delta; cN[a]++;
            mSum[a][q] += delta; mN[a][q]++;
            if (fine != null && fineKey != null) {
                double[] e = fine.computeIfAbsent(fineKey, k -> new double[2]);
                e[0] += delta; e[1] += 1.0;
            }
            globalSum += delta; globalN++;
        }

        void copyFrom(Table o) {
            System.arraycopy(o.cSum, 0, cSum, 0, A_MAX);
            System.arraycopy(o.cN, 0, cN, 0, A_MAX);
            for (int a = 0; a < A_MAX; a++) {
                System.arraycopy(o.mSum[a], 0, mSum[a], 0, Q_MAX);
                System.arraycopy(o.mN[a], 0, mN[a], 0, Q_MAX);
            }
            if (fine != null && o.fine != null) {
                fine.clear();
                for (java.util.Map.Entry<String, double[]> e : o.fine.entrySet())
                    fine.put(e.getKey(), new double[]{e.getValue()[0], e.getValue()[1]});
            }
            globalSum = o.globalSum; globalN = o.globalN;
        }
    }

    // Live per-turn statistics (reset in randomActions = turn start).
    private final Table live;
    /** Frozen snapshot used for SELECTION when perBurnUpdates (copied from live at each burn). */
    private final Table snap;
    /** True when either setting keys on full action identity, so fine keys are worth computing. */
    private final boolean needFine;

    // The mutation awaiting its child's evaluation (planTurn always evals right after mutate).
    private ExperimentalEMCTSTreeNode pendingChild, pendingParent;
    private int pendInsA = -1, pendInsQ, pendRemA = -1, pendRemQ;
    private String pendInsFine, pendRemFine;

    // Telemetry (cumulative; the isolation runner uses one agent per single-turn task).
    private long biasGreedyPicks, biasUniformPicks, shortlistCalls, shortlistSkipped, statUpdates;
    private long zeroEvalPicks;

    // ---- floor-probe instrumentation (ActionBiasFloorProbe) -------------------------------------
    // The action-bias line fails quietly from BELOW. qhat shrinks toward the running global mean, so
    // an unobserved key returns exactly that mean; when every candidate key is unseen they all tie
    // and the tie-break is uniform. An arm at too small a budget is therefore Baseline with extra
    // bookkeeping — it reports a small noisy effect rather than announcing that the mechanism never
    // engaged. What decides this is not how many mutations ran but how much evidence stood behind
    // the key each biased insertion actually CHOSE: the search concentrates on a few promising
    // actions, so a sparse table can still be well-supported exactly where it is read.
    /** Bridge burns completed this turn; the probe ignores the cold start. */
    private int burnsInTurn;
    private long biasPicksAfterWarmup, biasSupportedAfterWarmup;
    /** Compute units, and candidate evaluations, charged inside repair(). See getRepairUnits(). */
    private double repairUnits;
    private long repairCandidateEvals;

    // Stage-2 groundwork: per-child sequence composition rows (only when logSeqComposition).
    public static final String SEQLOG_FIELDS = "Iter,Duplicate,ChildValue,NumKeys,Keys";
    private static final int MAX_SEQLOG_ROWS = 200_000;
    private final ArrayList<String> seqLogRows = new ArrayList<>();
    private int iterInTurn = 0;

    public Stage1BiasedAgent(long seed, Stage1Params params) {
        super(seed, params);
        this.p1 = params;
        if (params.useCrossoverExpansion || params.useCrossoverBridgeBurning)
            throw new IllegalArgumentException("Stage1BiasedAgent requires crossover OFF");
        // A repair mode and a shortlist size that disagree would silently produce an arm that is not
        // the one its name claims — the "two arms produced identical output" failure, in reverse.
        // Refuse the combination rather than pick a winner.
        switch (params.repairMode) {
            case SHORTLIST_K:
            case SHORTLIST_RANDOM:
                if (params.shortlistK <= 0) throw new IllegalArgumentException(
                        "repairMode=" + params.repairMode + " needs shortlistK > 0 (got "
                        + params.shortlistK + ")");
                break;
            case FULL:
            case ZERO_EVAL:
            case ZERO_EVAL_RANDOM:
                if (params.shortlistK != 0) throw new IllegalArgumentException(
                        "repairMode=" + params.repairMode + " needs shortlistK = 0 (got "
                        + params.shortlistK + "); shortlistK only applies to the shortlist modes");
                break;
        }
        this.needFine = params.insertKeys == Stage1Params.Granularity.FINE
                     || params.repairKeys == Stage1Params.Granularity.FINE;
        this.live = new Table(needFine);
        this.snap = params.perBurnUpdates ? new Table(needFine) : null;
    }

    public long getBiasGreedyPicks()  { return biasGreedyPicks; }
    public long getBiasUniformPicks() { return biasUniformPicks; }
    public long getShortlistCalls()   { return shortlistCalls; }
    public long getShortlistSkipped() { return shortlistSkipped; }
    public long getStatUpdates()      { return statUpdates; }
    public long getZeroEvalPicks()    { return zeroEvalPicks; }
    /** Biased insertions made after the warm-up burns — the denominator of the support fraction. */
    public long getBiasPicksAfterWarmup()     { return biasPicksAfterWarmup; }
    /** Of those, how many chose a key already carrying at least {@link #MIN_SUPPORT} observations. */
    public long getBiasSupportedAfterWarmup() { return biasSupportedAfterWarmup; }
    /** Distinct observed keys in the table the insertion bias reads, at its own granularity. */
    public int getDistinctInsertKeys() { return observationCounts().length; }
    /** Observation counts of every observed insertion key, for the median the probe reports. */
    public int[] getInsertKeyObservationCounts() { return observationCounts(); }

    /** Burns to skip before measuring: the table is empty at turn start by construction, so counting
     *  the cold start would measure the reset, not the budget. */
    public static final int WARMUP_BURNS = 4;
    /** Observations behind a key before reading it is evidence rather than the prior. */
    public static final int MIN_SUPPORT = 3;

    private int[] observationCounts() {
        Table t = p1.perBurnUpdates ? snap : live;
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        switch (p1.insertKeys) {
            case COARSE:
                for (int a = 0; a < A_MAX; a++) if (t.cN[a] > 0) out.add(t.cN[a]);
                break;
            case FINE:
                if (t.fine != null) for (double[] e : t.fine.values()) out.add((int) e[1]);
                break;
            case MID:
            default:
                for (int a = 0; a < A_MAX; a++)
                    for (int q = 0; q < Q_MAX; q++) if (t.mN[a][q] > 0) out.add(t.mN[a][q]);
                break;
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Observations behind one action's key in the table SELECTION reads. Mirrors {@link #qhat}. */
    private int nObs(Stage1Params.Granularity g, Action action, GameState gs) {
        Table t = p1.perBurnUpdates ? snap : live;
        switch (g) {
            case COARSE:
                return t.cN[actionOrd(action)];
            case FINE: {
                double[] e = t.fine == null ? null : t.fine.get(action.toString());
                return e == null ? 0 : (int) e[1];
            }
            case MID:
            default:
                return t.mN[actionOrd(action)][qualifierOf(action, gs)];
        }
    }
    /** Compute units charged inside {@code repair()} — candidate scanning plus the advances that
     *  apply the sequence. Every arm pays the latter, so compare arms on {@link #getRepairCandidateEvals()}
     *  when the question is what CHOOSING a replacement costs. */
    public double getRepairUnits() { return repairUnits; }
    /** Candidate evaluations charged inside {@code repair()}. Exact for every repair mode, because
     *  {@code evaluateState} is the only thing in any repair path that charges an eval — so this is
     *  the number that reads zero for the ZERO_EVAL family, and it reads zero by construction. */
    public long getRepairCandidateEvals() { return repairCandidateEvals; }
    public List<String> getSeqLogRows() { return seqLogRows; }

    // ------------------------------------------------------------------ per-turn lifecycle

    /** planTurn's first act; doubles as the turn-start hook: per-turn statistics reset here. */
    @Override
    protected ExperimentalEMCTSTreeNode randomActions(GameState gs) {
        live.reset();
        if (snap != null) snap.reset();
        burnsInTurn = 0;
        pendingChild = null; pendingParent = null; pendInsA = -1; pendRemA = -1;
        pendInsFine = null; pendRemFine = null;
        iterInTurn = 0;
        return super.randomActions(gs);
    }

    @Override
    protected void recordBridgeBurn(int bridgeBurningPhaseNumber, double bestSequenceValue,
                                    long turnStartTime, int tick) {
        super.recordBridgeBurn(bridgeBurningPhaseNumber, bestSequenceValue, turnStartTime, tick);
        burnsInTurn++;
        if (p1.perBurnUpdates) snap.copyFrom(live);    // publish the batch at the phase boundary
    }

    // ------------------------------------------------------------------ statistics

    private int actionOrd(Action a) {
        int o = a.getActionType().ordinal();
        return o < A_MAX ? o : A_MAX - 1;
    }

    /** Mid qualifier resolved against the state the action applies in; 0 when unresolvable. */
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
            return q < Q_MAX ? q : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Full action identity, or null when no setting needs it (so the toString is never paid for). */
    private String fineKeyOf(Action a) {
        return needFine ? a.toString() : null;
    }

    private void addObs(int a, int q, String fineKey, double delta) {
        live.add(a, q, fineKey, delta);
        statUpdates++;
    }

    /**
     * Shrunk mean delta at one granularity, from the table SELECTION reads (the snapshot in perBurn
     * mode, else live). An unobserved key returns exactly the global mean, so unseen candidates all
     * tie and the caller's uniform tie-break makes an empty table degrade to a uniform draw.
     */
    private double qhat(Stage1Params.Granularity g, Action action, GameState gs) {
        Table t = p1.perBurnUpdates ? snap : live;
        double gm = t.globalN > 0 ? t.globalSum / t.globalN : 0.0;
        double sum;
        double n;
        switch (g) {
            case COARSE: {
                int a = actionOrd(action);
                sum = t.cSum[a]; n = t.cN[a];
                break;
            }
            case FINE: {
                double[] e = t.fine == null ? null : t.fine.get(action.toString());
                sum = e == null ? 0.0 : e[0];
                n = e == null ? 0.0 : e[1];
                break;
            }
            case MID:
            default: {
                int a = actionOrd(action), q = qualifierOf(action, gs);
                sum = t.mSum[a][q]; n = t.mN[a][q];
                break;
            }
        }
        return (sum + p1.shrinkageM * gm) / (n + p1.shrinkageM);
    }

    // ------------------------------------------------------------------ biased mutation

    /**
     * Verbatim copy of {@link ExperimentalEMCTSAgent#mutate} with (a) the uniform insertion draw
     * replaced by the epsilon-greedy rule and (b) read-only bookkeeping for the credit assignment.
     * At epsilon >= 1 the epsilon coin is never flipped, so the RNG stream matches the base class
     * exactly (guarded by {@code Stage1Runner verify}).
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
        int insA = -1, insQ = 0;
        if (!allAvailableActions.isEmpty()) {
            if (p1.epsilon >= 1.0 || m_rnd.nextDouble() < p1.epsilon) {
                inserted = allAvailableActions.get(m_rnd.nextInt(allAvailableActions.size()));
                biasUniformPicks++;
            } else {
                // Greedy over Q with uniform tie-break (reservoir), so an empty table = uniform.
                double bestQ = Double.NEGATIVE_INFINITY;
                int numTies = 0;
                for (Action cand : allAvailableActions) {
                    double qv = qhat(p1.insertKeys, cand, gs);
                    if (qv > bestQ) {
                        bestQ = qv; inserted = cand; numTies = 1;
                    } else if (qv == bestQ) {
                        numTies++;
                        if (m_rnd.nextInt(numTies) == 0) inserted = cand;
                    }
                }
                biasGreedyPicks++;
                if (burnsInTurn >= WARMUP_BURNS && inserted != null) {
                    biasPicksAfterWarmup++;
                    if (nObs(p1.insertKeys, inserted, gs) >= MIN_SUPPORT) biasSupportedAfterWarmup++;
                }
            }
            insA = actionOrd(inserted);
            insQ = qualifierOf(inserted, gs);
            pendInsFine = fineKeyOf(inserted);
            newSeq.add(inserted);
        }

        // Removed-action key, resolved on the same prefix state (read-only).
        int remA = -1, remQ = 0;
        pendRemFine = null;
        if (moveToMutate < seq.size()) {
            Action removed = seq.get(moveToMutate);
            remA = actionOrd(removed);
            remQ = qualifierOf(removed, gs);
            pendRemFine = fineKeyOf(removed);
        }

        for (int i = moveToMutate + 1; i < seq.size(); i++) {
            newSeq.add(seq.get(i));
        }
        newSeq = truncateAtEndTurn(repair(newSeq, clone));
        ExperimentalEMCTSTreeNode newNode = new ExperimentalEMCTSTreeNode(newSeq, node);
        node.addChild(newNode);
        int nChildren = node.getChildren().size();
        if (nChildren > maxChildrenOnANode) maxChildrenOnANode = nChildren;

        pendingChild = newNode;
        pendingParent = node;
        pendInsA = insA; pendInsQ = insQ; pendRemA = remA; pendRemQ = remQ;
        iterInTurn++;
        return newNode;
    }

    /** Completes the credit assignment once the child is scored; optionally logs composition. */
    @Override
    public void eval(GameState gs, ExperimentalEMCTSTreeNode node) {
        boolean match = pendingChild != null && node == pendingChild;
        boolean duplicate = match && p1.logSeqComposition
                && uniqueSeqKeys.contains(seqKey(node.getSequence()));
        super.eval(gs, node);
        if (!match) return;

        double delta = node.getValue() - pendingParent.getValue();
        if (pendInsA >= 0) addObs(pendInsA, pendInsQ, pendInsFine, delta);
        if (p1.removalCredit && pendRemA >= 0) addObs(pendRemA, pendRemQ, pendRemFine, -delta);

        if (p1.logSeqComposition && seqLogRows.size() < MAX_SEQLOG_ROWS)
            seqLogRows.add(compositionRow(node, duplicate));

        pendingChild = null; pendingParent = null; pendInsA = -1; pendRemA = -1;
        pendInsFine = null; pendRemFine = null;
    }

    // ------------------------------------------------------------------ shortlisted repair

    /**
     * Repair, with its cost measured for every mode.
     *
     * <p>{@code FULL} delegates to the base class's own code, so the baseline path is literally the
     * base implementation and the equivalence gate stays meaningful. The other modes run the copy
     * below, whose only difference from the base is which candidates the replacement rule looks at.
     *
     * <p>The two meter reads are pure — they change no count and consume no randomness — so this
     * instrumentation is invisible to the equivalence gate. {@code evaluateState} is the only thing
     * in any repair path that charges an eval, which is what makes the candidate-evaluation count
     * exact rather than an estimate, and comparable across all three modes.
     */
    @Override
    protected ArrayList<Action> repair(ArrayList<Action> child, GameState gs) {
        double u0 = core.game.ComputeMeter.computeUnits();
        long e0 = core.game.ComputeMeter.evals();
        try {
            return p1.repairMode == Stage1Params.RepairMode.FULL
                    ? super.repair(child, gs)
                    : tableDirectedRepair(child, gs);
        } finally {
            repairUnits += core.game.ComputeMeter.computeUnits() - u0;
            repairCandidateEvals += core.game.ComputeMeter.evals() - e0;
        }
    }

    /**
     * Copy of {@link ExperimentalEMCTSAgent#repair} whose greedy-replacement site is replaced by the
     * table-directed rule: a top-K shortlist, or the Q-argmax with no forward-model call at all.
     */
    private ArrayList<Action> tableDirectedRepair(ArrayList<Action> child, GameState gs) {
        ArrayList<Action> repairedChild = new ArrayList<>();
        lastRepairKept = 0; lastRepairReplaced = 0;

        for (int a = 0; a < child.size(); a++) {
            if (!(gs.getActiveTribeID() == getPlayerID())) {
                return repairedChild;
            }
            boolean done;
            try {
                done = checkActionFeasibility(child.get(a), gs);
            } catch (Exception e) {
                done = false;
            }
            if (done) {
                repairedChild.add(child.get(a));
                try {
                    advance(gs, child.get(a));
                } catch (Exception e) {
                    // Feasible-but-throwing action: replace it like an infeasible one.
                    repairedChild.remove(repairedChild.size() - 1);
                    done = false;
                }
                if (done) {
                    lastRepairKept++;
                    continue;
                }
            }
            Action bestAction;
            switch (p1.repairMode) {
                case ZERO_EVAL:        bestAction = zeroEvalReplace(gs, true);         break;
                case ZERO_EVAL_RANDOM: bestAction = zeroEvalReplace(gs, false);        break;
                case SHORTLIST_RANDOM: bestAction = shortlistedGreedyReplace(gs, false); break;
                default:               bestAction = shortlistedGreedyReplace(gs, true);  break;
            }
            if (bestAction != null) {
                advance(gs, bestAction);
                repairedChild.add(bestAction);
                lastRepairReplaced++;
            } else {
                break;
            }
        }

        // Ensure repaired sequence ends with END_TURN (same as base).
        if (!repairedChild.isEmpty()
                && repairedChild.get(repairedChild.size() - 1).getActionType() != Types.ACTION.END_TURN) {
            ArrayList<Action> allAvailableActions = this.allGoodActions(gs, m_rnd);
            for (Action a : allAvailableActions) {
                if (a.getActionType() == Types.ACTION.END_TURN) {
                    repairedChild.add(a);
                    break;
                }
            }
        }
        return repairedChild;
    }

    /**
     * Zero-evaluation replacement: take the highest-Q feasible candidate outright.
     *
     * <p>No {@code copy}, no candidate {@code advance}, no {@code evaluateState} — the forward model
     * is not consulted at all, which is why {@link #getRepairCandidateEvals()} reads zero for this
     * mode by construction rather than by measurement. The caller still advances the chosen action,
     * but that is applying the sequence, a cost every arm pays for kept actions too.
     *
     * <p>Ties break uniformly, and they will be common: the table only ever holds keys for actions
     * some mutation inserted or removed, so most repair candidates are unseen and sit at the prior.
     * That is exactly why granularity matters here — a coarse key that ranks every MOVE alike must
     * break ties at random, while a fine key can separate the ones the search has actually learned
     * something about. It is also the quantity to watch if this mode underperforms: if nearly every
     * replacement is a random pick among unseen keys, the table is too sparse to be steering.
     */
    private Action zeroEvalReplace(GameState gs, boolean rankByQ) {
        ArrayList<Action> cands = this.allGoodActions(gs, m_rnd);
        if (cands.isEmpty()) return null;

        if (!rankByQ) {
            // ZERO_EVAL_RANDOM: commit to an arbitrary feasible action. Same zero forward-model
            // calls; the only thing removed is the table's opinion, which is what it controls for.
            zeroEvalPicks++;
            return cands.get(m_rnd.nextInt(cands.size()));
        }

        Action best = null;
        double bestQ = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (Action c : cands) {
            double qv = qhat(p1.repairKeys, c, gs);
            if (qv > bestQ) {
                bestQ = qv; best = c; numTies = 1;
            } else if (qv == bestQ) {
                numTies++;
                if (m_rnd.nextInt(numTies) == 0) best = c;
            }
        }
        zeroEvalPicks++;
        return best;
    }

    /**
     * The replacement rule: rank feasible candidates by Q (free), pay copy+advance+eval only for the
     * top K, pick the heuristic-best of those (ties broken uniformly, as in the base greedy).
     */
    private Action shortlistedGreedyReplace(GameState gs, boolean rankByQ) {
        ArrayList<Action> cands = this.allGoodActions(gs, m_rnd);
        if (cands.isEmpty()) return null;

        ArrayList<Action> pool = cands;
        if (cands.size() > p1.shortlistK) {
            shortlistCalls++;
            shortlistSkipped += cands.size() - p1.shortlistK;
            // Rank by Q with a random jitter far below any real delta, purely to break ties
            // (unseen keys all sit at the prior) without biasing among equals.
            //
            // SHORTLIST_RANDOM replaces the ranking key with the jitter alone, and nothing else:
            // same candidate list, same shortlist size, same evaluation of the survivors, and the
            // same NUMBER OF RNG DRAWS, so the two arms differ in the ranking and in nothing else.
            int n = cands.size();
            double[] score = new double[n];
            for (int i = 0; i < n; i++) {
                Action c = cands.get(i);
                double jitter = m_rnd.nextDouble();
                score[i] = rankByQ ? qhat(p1.repairKeys, c, gs) + jitter * 1e-9 : jitter;
            }
            pool = new ArrayList<>(p1.shortlistK);
            boolean[] taken = new boolean[n];
            for (int k = 0; k < p1.shortlistK; k++) {
                int best = -1;
                for (int i = 0; i < n; i++)
                    if (!taken[i] && (best < 0 || score[i] > score[best])) best = i;
                taken[best] = true;
                pool.add(cands.get(best));
            }
        }

        Action bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int numTies = 0;
        for (Action candidate : pool) {
            double s;
            try {
                GameState testGs = gs.copy();
                advance(testGs, candidate);
                s = heuristic.evaluateState(old_gs, testGs);
            } catch (Exception e) {
                continue;
            }
            if (s > bestScore) {
                bestScore = s; bestAction = candidate; numTies = 1;
            } else if (s == bestScore) {
                numTies++;
                if (m_rnd.nextInt(numTies) == 0) bestAction = candidate;
            }
        }
        return bestAction;
    }

    // ------------------------------------------------------------------ Stage-2 composition log

    /** One row: the child's value plus the multiset of Mid keys in its sequence. Unit types are
     *  resolved against the TURN-START state (old_gs): stable and cheap; units spawned mid-sequence
     *  fall back to an unqualified key, which the offline analysis tolerates. */
    private String compositionRow(ExperimentalEMCTSTreeNode node, boolean duplicate) {
        ArrayList<Action> seq = node.getSequence();
        StringBuilder keys = new StringBuilder(seq.size() * 12);
        ArrayList<String> ks = new ArrayList<>(seq.size());
        for (Action a : seq) {
            if (a == null) continue;
            String k;
            if (a instanceof UnitAction) {
                Actor act = old_gs == null ? null : old_gs.getActor(((UnitAction) a).getUnitId());
                k = a.getActionType().name()
                        + (act instanceof Unit ? ":" + ((Unit) act).getType().name() : "");
            } else if (a instanceof Build) {
                k = "BUILD:" + ((Build) a).getBuildingType();
            } else if (a instanceof Spawn) {
                k = "SPAWN:" + ((Spawn) a).getUnitType();
            } else if (a instanceof ResearchTech) {
                k = "RESEARCH_TECH:" + ((ResearchTech) a).getTech();
            } else {
                k = a.getActionType().name();
            }
            ks.add(k);
        }
        java.util.Collections.sort(ks);
        for (int i = 0; i < ks.size(); i++) {
            if (i > 0) keys.append('|');
            keys.append(ks.get(i));
        }
        return (iterInTurn - 1) + "," + (duplicate ? 1 : 0) + ","
                + Math.round(node.getValue() * 10000.0) / 10000.0 + "," + ks.size() + "," + keys;
    }
}
