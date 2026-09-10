package core.data.dto;

/**
 * Per-turn telemetry on the two candidate-generation operators of the EMCTS family — mutation and
 * (for the crossover variants) main-loop crossover expansion. Collected to discriminate the v5
 * hypotheses for the single-vs-dual performance gap:
 *
 * <ul>
 *   <li><b>H1 blend incoherence</b> — child value vs the two parents' values
 *       ({@link #xoChildVsBestParentSum}, {@link #xoChildBeatsBestParent},
 *       {@link #xoChildWorseThanBothParents});</li>
 *   <li><b>H2 repair disruption</b> — actions kept vs greedily replaced while repairing each child
 *       ({@link #xoRepairKept}/{@link #xoRepairReplaced} vs the
 *       {@link #mutRepairKept}/{@link #mutRepairReplaced} mutation baseline);</li>
 *   <li><b>H3 elite-pool collapse</b> — positional parent similarity and duplicate-child rates
 *       ({@link #xoParentSimilaritySum}, {@link #xoChildDuplicates}, {@link #mutChildDuplicates});</li>
 *   <li><b>H4 operator credit</b> — count and summed value-gain of new-best-sequence events per
 *       operator ({@link #mutBestGains}/{@link #mutBestGainSum} vs
 *       {@link #xoBestGains}/{@link #xoBestGainSum}).</li>
 * </ul>
 *
 * All counters are observation-only: collecting them performs NO forward-model calls, game-state
 * copies or heuristic evaluations, so the ComputeMeter budget and the search itself are unaffected.
 * For agents without crossover expansion (EMCTS, CrossoverBB) every xo* field is zero.
 * Best-gain credit covers the two MAIN-LOOP operators only; improvements from the initial root or
 * from crossover-bridge-burning tournaments are not attributed here (the latter are already logged
 * per-burn as {@link CrossoverEventData}).
 */
public final class OperatorTelemetryData {

    // ---- mutation operator ----
    /** Main-loop iterations that generated their child via mutate(). */
    public final int mutEvents;
    /** Mutation children that became the new best-so-far sequence. */
    public final int mutBestGains;
    /** Total best-sequence value gain contributed by those mutation children. */
    public final double mutBestGainSum;
    /** Mutation children whose post-repair sequence had already been evaluated this turn. */
    public final int mutChildDuplicates;
    /** Actions kept as-is across all mutation-child repairs (feasible at their position). */
    public final int mutRepairKept;
    /** Actions greedily replaced across all mutation-child repairs (infeasible at their position). */
    public final int mutRepairReplaced;

    // ---- crossover-expansion operator (main loop; zero for EMCTS / CrossoverBB) ----
    /** Main-loop iterations that generated their child via crossover of the top-2 elite parents. */
    public final int xoEvents;
    /** Crossover children that became the new best-so-far sequence. */
    public final int xoBestGains;
    /** Total best-sequence value gain contributed by those crossover children. */
    public final double xoBestGainSum;
    /** Σ over events of (child value − max(parent1, parent2)) — blend quality vs the better parent. */
    public final double xoChildVsBestParentSum;
    /** Σ over events of (child value − mean(parent1, parent2)) — blend quality vs the parent mean. */
    public final double xoChildVsMeanParentSum;
    /** Events where the child's value strictly beat BOTH parents (synergistic blend). */
    public final int xoChildBeatsBestParent;
    /** Events where the child's value was strictly below BOTH parents (destructive blend). */
    public final int xoChildWorseThanBothParents;
    /** Σ over events of the parents' positional similarity in [0,1] (fraction of aligned positions
     *  holding the same action, over the shorter parent's length). */
    public final double xoParentSimilaritySum;
    /** Crossover children whose post-repair sequence had already been evaluated this turn. */
    public final int xoChildDuplicates;
    /** Actions kept as-is across all crossover-child inline repairs. */
    public final int xoRepairKept;
    /** Actions greedily replaced across all crossover-child inline repairs. */
    public final int xoRepairReplaced;

    public OperatorTelemetryData(int mutEvents, int mutBestGains, double mutBestGainSum,
                                 int mutChildDuplicates, int mutRepairKept, int mutRepairReplaced,
                                 int xoEvents, int xoBestGains, double xoBestGainSum,
                                 double xoChildVsBestParentSum, double xoChildVsMeanParentSum,
                                 int xoChildBeatsBestParent, int xoChildWorseThanBothParents,
                                 double xoParentSimilaritySum, int xoChildDuplicates,
                                 int xoRepairKept, int xoRepairReplaced) {
        this.mutEvents = mutEvents;
        this.mutBestGains = mutBestGains;
        this.mutBestGainSum = mutBestGainSum;
        this.mutChildDuplicates = mutChildDuplicates;
        this.mutRepairKept = mutRepairKept;
        this.mutRepairReplaced = mutRepairReplaced;
        this.xoEvents = xoEvents;
        this.xoBestGains = xoBestGains;
        this.xoBestGainSum = xoBestGainSum;
        this.xoChildVsBestParentSum = xoChildVsBestParentSum;
        this.xoChildVsMeanParentSum = xoChildVsMeanParentSum;
        this.xoChildBeatsBestParent = xoChildBeatsBestParent;
        this.xoChildWorseThanBothParents = xoChildWorseThanBothParents;
        this.xoParentSimilaritySum = xoParentSimilaritySum;
        this.xoChildDuplicates = xoChildDuplicates;
        this.xoRepairKept = xoRepairKept;
        this.xoRepairReplaced = xoRepairReplaced;
    }
}
