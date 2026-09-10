package players.stage1;

import players.ExperimentalEMCTS.ExperimentalEMCTSParams;

/**
 * Parameters for {@link Stage1BiasedAgent}. Every default is the DO-NOTHING setting: an agent built
 * from an unmodified Stage1Params behaves bit-identically to plain ExperimentalEMCTSAgent (the
 * runner's verify step asserts this), so the baseline config and the experimental configs share one
 * implementation.
 *
 * <p>The non-default values encode the Stage-0 conclusions (see
 * {@code analysis/STAGE0_SIGNAL_PLAN.md} and the Stage-0 results): Mid-granularity keys, paired-delta
 * credit, removal credit on, no decay (statistics accumulate over the whole turn and reset at turn
 * start), shrinkage toward the running global mean.
 */
public class Stage1Params extends ExperimentalEMCTSParams {

    /**
     * How an action is keyed in the learned table.
     *
     * <p>The two decisions that read the table want <b>different</b> granularities, which is why
     * {@link #insertKeys} and {@link #repairKeys} are separate settings. The principle (D2) is that
     * granularity should track whether a decision is <b>corrected downstream</b>: an insertion is a
     * <i>proposal</i> that the sequence is scored on afterwards, so abstraction generalises and
     * coarse keys win; a zero-evaluation repair is a <i>commitment</i> that nothing checks, so
     * precision matters and fine keys win.
     */
    public enum Granularity {
        /** Action type only — e.g. {@code MOVE}. ~10-25 keys per turn. */
        COARSE,
        /** Type plus an actor/tech qualifier — e.g. {@code MOVE:RIDER}. ~7-25 keys per turn. */
        MID,
        /**
         * Full action identity — e.g. {@code MOVE by unit 7 to (12,4)} — thousands of keys per turn.
         *
         * <p>Keyed on {@code Action.toString()}, which is this codebase's canonical action identity:
         * it is what both agents' {@code seqKey} is built from, and {@code ActionKeyCollisionProbe}
         * exists to assert that two distinct actions in one reachable state never share a string.
         * Composing a key by hand from selected fields would re-open exactly the collision class
         * that probe was written to close.
         */
        FINE
    }

    /** What the greedy repair does when an action must be replaced. */
    public enum RepairMode {
        /**
         * Exhaustive greedy: {@code copy + advance + evaluateState} for every feasible candidate.
         * The baseline behaviour, and ~69% of the whole search budget at mid-game branching factors.
         */
        FULL,
        /**
         * Rank candidates by shrunk mean delta (free) and pay the exhaustive treatment for only the
         * top {@link #shortlistK}.
         */
        SHORTLIST_K,
        /**
         * Take the highest-ranked candidate outright, with <b>no forward-model call at all</b>: no
         * copy, no candidate advance, no evaluation. The chosen action is still advanced, because
         * that is applying the sequence rather than scoring a candidate — so the quantity that goes
         * to zero is the <i>candidate evaluation</i> count, which the agent reports directly.
         */
        ZERO_EVAL,
        /**
         * {@link #SHORTLIST_K} with the Q ranking replaced by a random one: still exactly
         * {@link #shortlistK} candidates evaluated, chosen uniformly instead of by the table.
         *
         * <p><b>This is the control that attributes the shortlist result.</b> Stage D's champion
         * (`Comma_Short15`) beat exhaustive repair by +118.9 at 80k while nearly doubling the nodes
         * it evaluated — a throughput gain that shortlisting buys whether or not the ranking is any
         * good. Without this arm, "the learned table directs repair" and "evaluating 15 candidates
         * instead of 300 is simply cheaper" predict the same measurement.
         */
        SHORTLIST_RANDOM,
        /**
         * {@link #ZERO_EVAL} with the Q argmax replaced by a uniform draw: the same zero
         * forward-model calls, committing to an arbitrary feasible action.
         *
         * <p>The floor for the zero-eval family, and the same attribution question one rung further
         * down: if Q-argmax repair does no better than this, the table carries no usable ranking.
         */
        ZERO_EVAL_RANDOM
    }

    /**
     * Probability that the mutation-insertion step draws UNIFORMLY from the feasible set (the
     * baseline behaviour). With probability 1 - epsilon the step instead picks the feasible action
     * with the highest shrunk mean delta (ties broken uniformly). 1.0 = bias machinery never fires
     * and the RNG stream is identical to plain EMCTS.
     */
    public double epsilon = 1.0;

    /** Granularity the INSERTION bias reads. Default MID (the previous {@code coarseKeys = false}). */
    public Granularity insertKeys = Granularity.MID;

    /**
     * Granularity the REPAIR ranker reads. Default MID, which is what repair used when insertion and
     * repair shared one setting — so the default reproduces the previous behaviour exactly.
     */
    public Granularity repairKeys = Granularity.MID;

    /** Credit the REMOVED action with the negated delta (doubles evidence; Stage-0 F7). */
    public boolean removalCredit = true;

    /**
     * true = selection reads a snapshot of the statistics frozen at the last bridge burn (the
     * "batched update at phase boundaries" variant); false = selection reads the live statistics
     * (continuous updates). Accumulation is always continuous.
     */
    public boolean perBurnUpdates = false;

    /** Replacement strategy for greedy repair. FULL delegates to the base class's own code. */
    public RepairMode repairMode = RepairMode.FULL;

    /**
     * Shortlist size for {@link RepairMode#SHORTLIST_K}: rank the feasible candidates by shrunk mean
     * delta and run the exhaustive copy+advance+eval greedy over only the top K. Must be &gt; 0 for
     * SHORTLIST_K and 0 for every other mode; {@link Stage1BiasedAgent} enforces that, because a
     * mode and a K that disagree would silently produce an arm that is not the one its name claims.
     */
    public int shortlistK = 0;

    /** Shrinkage prior strength m: Q(a) = (sumDelta_a + m * globalMean) / (n_a + m). */
    public double shrinkageM = 5.0;

    /**
     * Log one row per evaluated mutation child with the child sequence's Mid-key composition
     * (Stage-2 groundwork: offline co-occurrence / interaction analysis). Observation only; enabled
     * on the Baseline config so the interaction estimates come from UNBIASED search data.
     */
    public boolean logSeqComposition = false;
}
