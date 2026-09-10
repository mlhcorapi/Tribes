package players.tbets;

import players.heuristics.AlgParams;

/**
 * Parameters for {@link TBETSAgent}, the Tribes port of TBETS (Turn-Based Evolutionary Tree Search),
 * originally written in C# for TUBSTAP (see {@code AI/TBETS} in the TUBSTAP_Taylor repository).
 *
 * <p>Field names mirror the C# constants so the two implementations can be diffed; the C# name is
 * given in the comment where it differs. Defaults are the TUBSTAP values EXCEPT where Tribes forces
 * a different choice - those are called out individually below and are the only places this port
 * knowingly departs from the original.
 */
public class TBETSParams extends AlgParams {

    // ------------------------------------------------------------------ tree shape

    /** {@code NUM_INIT}: candidate turns generated for the root before the search loop starts. */
    public int numInit = 20;

    /** {@code STARTING_POP}: candidate turns generated when an unexplored node is first exploited. */
    public int startingPop = 5;

    /**
     * How many TURN plies the tree may grow, counting from the root's children.
     *
     * <p>1 (the Tribes default) keeps the tree one ply deep: root -&gt; candidate turns for me. That is
     * the only depth that is well defined here without an opponent model, because growing ply 2 means
     * generating the OPPONENT's whole turn from a state the agent cannot see (Tribes hands each agent
     * a fog-reduced {@code gs.copy(playerIdx)}), and in a solo scenario the turn does not even change
     * hands on END_TURN.
     *
     * <p>TUBSTAP's TBETS effectively runs unbounded depth on a fully observable 2-player map. Set this
     * greater than 1 only on full-observability, multi-tribe scenarios.
     */
    public int maxPlies = 1;

    /** Maximum actions in one turn sequence. Matches {@code EMCTSParams.NODE_SIZE}. */
    public int nodeSize = 20;

    // ------------------------------------------------------------------ evolution

    /** {@code MUTATION_RATIO}: P(mutate) when evolving an explored node; otherwise crossover. */
    public double mutationRatio = 0.3;

    /** {@code MUTATION_RATE}: per-action P(resample) inside one mutation pass over a sequence. */
    public double mutationRate = 0.4;

    /** Tournament size for picking a parent among a node's children. TUBSTAP uses 3. */
    public int tournamentSize = 3;

    /**
     * Fraction of the (fitness-sorted) sibling list the tournament draws from. TUBSTAP draws from
     * {@code candidates.Count/5}, i.e. the top 20%.
     */
    public double tournamentTopFraction = 0.2;

    // ------------------------------------------------------------------ selection

    /** UCB exploration constant. TUBSTAP uses sqrt(2). */
    public double ucbC = 1.414;

    /** Progressive widening: expand here while {@code children < wideningK * descendants^alpha}. */
    public double wideningK = 2.0;
    public double wideningAlpha = 0.4;
    /** P(stop descending) once the widening test says this node is under-expanded. */
    public double wideningStopProb = 0.95;

    // ------------------------------------------------------------------ rollouts

    /**
     * Whether node fitness comes from a ROLLOUT (TUBSTAP behaviour) or straight from the heuristic
     * applied to the node's own post-turn state.
     *
     * <p>TBETS scores a candidate turn by building two "phantom" nodes - a scripted opponent turn and
     * then a scripted turn of its own - and evaluating the state two turns later
     * ({@code TBETSStateEvaluator.EvaluateNodeFitness}). Three things make that a much heavier and
     * less sound proposition in Tribes than in TUBSTAP:
     *
     * <ul>
     *   <li><b>Cost.</b> One scripted turn is tens of {@code advance()} calls, and the greedy script
     *       adds a {@code copy() + advance() + evaluateState()} per candidate action per step.
     *       Measured: turning this on multiplied root-seeding cost by ~35x and cut the number of
     *       distinct sequences the search evaluates per turn from ~2200 to ~180.</li>
     *   <li><b>Observability.</b> Tribes hands each agent a fog-reduced state, so the opponent half
     *       of the rollout is played out over units the agent cannot see. TUBSTAP is fully
     *       observable.</li>
     *   <li><b>Applicability.</b> In a solo scenario the active tribe does not change on END_TURN, so
     *       there is no opponent turn to script; the rollout degenerates to the agent moving twice.</li>
     * </ul>
     *
     * <p>false (the default) therefore scores a node as {@code heuristic.evaluateState(rootState,
     * node.state)} - the same one-shot evaluation EMCTS and OEP use here, which is what makes a
     * TBETS-vs-EMCTS comparison at a fixed budget mean anything.
     *
     * <p><b>Measured on the scenario portfolio, rollouts LOSE, and not narrowly.</b> Against EMCTS at
     * a matched compute budget, paired over 40 scenarios x 5 visits, the rollout arm scored
     * <b>-145 (40k units) and -162 (80k units)</b> on the value of the turn it actually played
     * (p&lt;0.001 both), winning 13-15% of scenarios; plain TBETS over the same pairs scored
     * <b>+217 and +251</b>. See {@code analysis/TBETS_VS_EMCTS.md}.
     *
     * <p><b>Why, and it is not simply cost.</b> The rollout arm has the HIGHEST search value of any
     * arm (8853 against EMCTS's 8148 at 40k) and the LOWEST delivered turn (7990). It is optimising
     * something other than what it hands over. A node's rollout value is the heuristic two turns
     * downstream of a SCRIPTED continuation, and two turns of accrual dominates any difference
     * between the candidate turns being compared - so ranking by it is close to ranking by noise.
     * The cheap script ({@code rolloutGreedy = false}) makes that worse, since the continuation is
     * then random; the greedy script is a better proxy but cannot afford to run at these budgets.
     * An earlier probe appeared to show rollouts winning; that probe compared the rollout arm's own
     * inflated value scale against the one-shot arms' and was wrong.
     *
     * <p>The switch stays because the port would be incomplete without it and because a better
     * scripted policy, or scoring the rollout as a DIFFERENCE against a common baseline rather than
     * as a level, could plausibly rescue the idea. As implemented, do not turn it on.
     */
    public boolean useRollouts = false;

    /** Scripted turns played after the node's own turn when {@link #useRollouts}. TUBSTAP uses 2. */
    public int rolloutTurns = 2;

    /** Hard cap on actions per scripted rollout turn, so a rollout cannot run away. */
    public int rolloutMaxActionsPerTurn = 20;

    /**
     * true: the rollout script picks the 1-ply greedy best action (closest to TUBSTAP's
     * {@code SimulateTurn}, which greedily picks the best attack and otherwise a heuristic move).
     * false: uniform random, which is far cheaper - no copy+evaluate per candidate.
     */
    public boolean rolloutGreedy = true;

    // ------------------------------------------------------------------ action bias

    /**
     * How a single action is drawn when building, mutating or repairing a turn sequence - the hook
     * that replaces TUBSTAP's {@code ATK_BIAS}. See {@code TBETSAgent.pickBiasedAction} and the
     * discussion in the class javadoc of {@link TBETSAgent}.
     */
    public enum Bias {
        /** Uniform over feasible actions. The control. */
        NONE,
        /**
         * Literal port of {@code ATK_BIAS}: with P = biasStrength, draw from the feasible ATTACK
         * actions if there are any, otherwise fall through to uniform.
         *
         * <p>Measured over 8 scenarios against a rule-based opponent, the fraction of biased draws
         * that found any feasible ATTACK to fire on was <b>4.3% over 10-turn games, 7.2% over 20 and
         * 10.5% over 30</b> - it rises with game length because that is when the tribes make contact.
         * So an 80% attack prior buys an effective 3-8% shift in the action distribution, concentrated
         * in the late game, and the other 90%+ of draws are uniform plus the cost of a filter pass.
         * That is a different intervention from the one it is in TUBSTAP, where every unit acts every
         * turn and an attack is usually on the table. Kept so the port can be run as written and so
         * this rate can be re-measured on other scenario sets; not a serious candidate.
         */
        ATTACK,
        /**
         * Draw an action CATEGORY first (see {@link #categoryWeights}), then uniformly within it.
         * The structural replacement for ATK_BIAS: a fixed domain prior over kinds of action, but
         * over kinds that Tribes actually scores. Preserves the diversity the evolutionary operators
         * need, since it still samples rather than optimises.
         */
        CATEGORY_WEIGHTS,
        /**
         * With P = biasStrength take the 1-ply greedy best by heuristic; else uniform.
         *
         * <p>Two costs, both measured and both large. It is the only policy that spends forward-model
         * calls to make a single draw - one {@code copy() + advance() + evaluateState()} per candidate
         * action - which multiplied root seeding cost by roughly 15x in the probe runs. And it
         * collapses diversity: at biasStrength 0.8 it evaluated ~500 distinct sequences per turn where
         * the sampling policies reached ~2200, because near-identical greedy turns leave mutation
         * nothing to vary. Available for comparison; not recommended as the generator.
         */
        GREEDY_EPSILON,
        /**
         * With P = biasStrength take the best action key by shrunk mean value learned online within
         * the turn - the same estimator as {@code players.stage1.Stage1BiasedAgent}, so this is the
         * turn-level counterpart of the Stage-1 action-bias line. Costs no forward-model calls, and
         * in the probe runs it was the only policy with a positive MEDIAN search gain and the highest
         * rate of turns improved among the non-rollout policies.
         */
        LEARNED_DELTA
    }

    /**
     * Default NONE. TUBSTAP's bias does not survive the port (see {@link Bias#ATTACK}), and no
     * candidate replacement has been shown to beat uniform on SCORE at a sample size that can
     * resolve it - two 8-scenario probe runs ranked the sampling policies in different orders, so
     * that comparison is still noise. Uniform is therefore the honest starting point, and choosing a
     * replacement is a sweep to run, not a default to inherit. {@link Bias#CATEGORY_WEIGHTS} and
     * {@link Bias#LEARNED_DELTA} are the two worth running.
     */
    public Bias biasMode = Bias.NONE;

    /** {@code ATK_BIAS}: strength of whichever bias is selected. 0 = never fires, 1 = always. */
    public double biasStrength = 0.8;

    /**
     * Relative sampling weights per {@code TBETSAgent.ActionCategory}, indexed by ordinal, used by
     * {@link Bias#CATEGORY_WEIGHTS}. The defaults encode the Tribes analogue of "attacks are the
     * actions that matter": expansion (capture/explore) and economy carry the score, with aggression
     * present but not dominant. Only categories that actually have a feasible action compete.
     */
    public double[] categoryWeights = {
            2.0,  // AGGRESSION  - attack, convert
            3.0,  // EXPANSION   - capture, examine, move, climb
            3.0,  // ECONOMY     - build, gather, level up, spawn, roads, forestry
            2.0,  // TECH        - research
            1.0   // UTILITY     - heal, recover, veteran, upgrades, diplomacy
    };

    /** Shrinkage prior strength for {@link Bias#LEARNED_DELTA}: Q = (sum + m*global)/(n + m). */
    public double learnedShrinkageM = 5.0;

    /**
     * Policy used when an action has to be REPLACED because an earlier one invalidated it - the
     * repair step inside mutation and crossover - as opposed to when an action is being GENERATED or
     * deliberately resampled.
     *
     * <p>null (the default) uses {@link #biasMode} for both, which is what TUBSTAP does: one
     * {@code GenerateBiasedAction} serves generation, mutation and repair alike.
     *
     * <p>Splitting them looks obviously right - generation wants DIVERSITY, repair wants QUALITY on
     * a forced decision - and it is what the EMCTS family here already does (uniform insertion,
     * greedy repair). <b>Measured, it does not work as expected in TBETS, and the reason is
     * instructive.</b> Setting {@code repairBias = GREEDY_EPSILON} on top of a sampling generator
     * still collapsed distinct sequences per turn from ~2530 to ~780 (greedy-everywhere is ~510).
     * Repair is not the rare event the argument assumes: TBETS mutation is a WHOLE-SEQUENCE pass at
     * {@link #mutationRate} = 0.4, so ~40% of positions are deliberately redrawn and much of the
     * tail is invalidated downstream - greedy repair ends up rewriting most of the sequence, which
     * is a greedy generator by another name. EMCTS escapes this because its mutation changes exactly
     * one action.
     *
     * <p>It is a trade, not a dead end: the split raised the share of turns improved from 41% to
     * 48-50%, the best of the one-shot configurations. To keep that without paying in diversity,
     * lower {@link #mutationRate} first (so repair really is rare), or bound the greedy - cap the
     * rewrites per sequence, or shortlist candidates by learned value the way
     * {@code Stage1Params.shortlistK} does.
     */
    public Bias repairBias = null;

    /** Strength for {@link #repairBias}; read only when {@code repairBias != null}. */
    public double repairBiasStrength = 1.0;

    // ------------------------------------------------------------------ budget bookkeeping

    /**
     * Exclude the root seeding ({@link #numInit} generated turns) from the search budget.
     *
     * <p>{@code EMCTSAgent} and {@code ExperimentalEMCTSAgent} exclude their greedy initialization,
     * and for a good reason: every one of those configs runs byte-identical initialization code, so
     * excluding a shared constant makes the remaining budget mean the same amount of SEARCH in all of
     * them. That reason does NOT hold here. TBETS seeding cost depends on the config under test -
     * turning on {@link #useRollouts} multiplied it by ~35x in the probe runs, and
     * {@link Bias#GREEDY_EPSILON} by ~15x - so excluding it would quietly hand the expensive configs
     * extra compute and make exactly the comparison one wants to run unfair. Default is therefore to
     * CHARGE it. Measured and logged either way (the {@code init*} columns of {@link
     * core.data.dto.TurnData}).
     *
     * <p>Set true only when comparing configs whose seeding cost is known to match.
     */
    public boolean excludeSeedingFromBudget = false;

    /** Safety net against a no-progress stall inside the search loop. */
    public int maxIterations = 1_000_000;
}
