package players.ExperimentalEMCTS;

import players.heuristics.AlgParams;

public class ExperimentalEMCTSParams extends AlgParams {
    public float bias = 0f;
    public int depth = 21; // phases per turn; bridge burns = depth - 1 = 20
    public int NODE_SIZE = 20;
    public boolean useCrossoverBridgeBurning = false;
    public boolean useCrossoverExpansion = false;
    public int crossoverExpansionRatio = 1;
    public int mutationExpansionRatio = 5;
    /** v4 Isolation Phase: when true, the root's INITIAL action sequence is built by sampling
     *  uniformly at random from valid actions instead of the default 1-ply greedy. The greedy
     *  REPAIR mechanism used in-search (mutate/crossover) is unaffected. Same {@code m_rnd} seed
     *  across all agents in a (scenario, visit) yields the same starting sequence. */
    public boolean randomInitialSequence = false;

    /**
     * How the search chooses the node it expands next.
     *
     * <p>The pre-2026-08-25 code descended best-child-to-leaf with no early stop, which was found and
     * fixed as a bug. Reframing it in evolutionary-strategy terms showed the "bug" is a policy, and
     * which one depends entirely on how many children an expansion produces:
     *
     * <ul>
     *   <li>at k = 1 the tree is a one-child chain, the descent is forced, and it returns the node
     *       just created — a neutral random walk with no selection at all ({@link #DRIFT});</li>
     *   <li>at k &gt; 1 each expanded node has exactly k children and the descent returns the best of
     *       them, accepted whether or not it beats its parent — <b>(1,k) comma selection</b>.</li>
     * </ul>
     *
     * <p>The fix expands the incumbent and keeps it when no offspring improves — <b>(1+k) plus
     * selection</b>. So the open question is the classical comma-versus-plus trade: comma needs
     * offspring to stop being a random walk, plus loses efficiency as k grows because each
     * non-improving generation discards all k evaluations.
     */
    public enum SelectionMode {
        /** (1+k): expand the incumbent; adopt the best child only if it beats the incumbent. */
        PLUS,
        /** (1,k): expand the incumbent; adopt the best child unconditionally. */
        COMMA,
        /** (1,k) without the best-of-k filter: adopt a UNIFORMLY RANDOM child. Splits "moving every
         *  generation" from "moving to the best of k", which COMMA confounds. */
        COMMA_RAND,
        /** Always expand the most recently created child. What the pre-fix descent does at k = 1. */
        DRIFT,
        /** The literal pre-fix policy: descend from the root taking the highest-SCORE child until a
         *  leaf. Kept so the equivalences above can be asserted rather than argued —
         *  see {@code core.data.exp.SelectionStructureCheck}. */
        LEGACY
    }

    /** Expansion policy. PLUS is the corrected default and the one every other arm is measured against. */
    public SelectionMode selectionMode = SelectionMode.PLUS;

    /**
     * Children produced per generation from the selected node (k). 1 is the default and reduces the
     * generational loop exactly to the pre-existing one-child-per-iteration search.
     *
     * <p>The budget ceiling is tested once per GENERATION, not per child, so a generation that starts
     * just under the ceiling runs to completion — measured at ~2% budget overshoot at k = 10. That is
     * deliberate: stopping mid-generation would make the selection rule operate on a truncated
     * offspring set and quietly change the policy at exactly the boundary.
     */
    public int childrenPerGeneration = 1;
}
