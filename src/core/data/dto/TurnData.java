package core.data.dto;

/**
 * Per-turn telemetry for one planning episode. The three workload metrics are kept distinct so
 * algorithm efficiency can be compared properly:
 *   - fmCalls         : forward-model advance() calls this turn. A compute-DEMAND metric only - under
 *                       v5's STOP_COMPUTE the budget is compute-units (see core.game.ComputeMeter),
 *                       so fmCalls is EXPECTED to differ across agents at an equal budget.
 *   - nodesEvaluated  : candidate nodes scored this turn (every eval()), DUPLICATES INCLUDED.
 *   - uniqueSequences : DISTINCT action sequences among those evaluated (de-duplicated).
 * Efficiency ratios (computed in analysis): nodesEvaluated/fmCalls, uniqueSequences/fmCalls,
 * uniqueSequences/nodesEvaluated, value-per-node, etc.
 *
 * Value metrics:
 *   - initialSequenceValue : greedy-root (pre-search) heuristic value of the turn's plan.
 *   - finalSequenceValue   : best-node (post-search) heuristic value of the turn's plan.
 *   - avgNodeValue         : mean heuristic value over ALL nodes evaluated this turn.
 *
 * A metric is logged as -1 (ints) when not applicable to an agent (e.g. uniqueSequences/avgNodeValue
 * for the per-action tree-search baseline MCTS).
 */
public class TurnData {
    private final int gameId;
    private final int turnNumber;
    private final int scoreAtTurnStart;
    private final double initialSequenceValue;
    private final double finalSequenceValue;
    private final double avgNodeValue;
    private final int fmCalls;
    private final int nodesEvaluated;
    private final int uniqueSequences;

    // ---- v6 realized-spend columns (see analysis/EXPERIMENT_V6_PLAN.md §3A) ----------------------
    // SEARCH spend: what the budget actually bounded, measured post-initialization. Logging these is
    // what turns "the budget was equalised across agents" from a claim into a reported result.
    private final double computeUnits;   // weighted units spent on search (ComputeMeter)
    private final long   advances;       // search-only advance() count
    private final long   copies;         // search-only copy() count
    private final long   evals;          // search-only eval() count
    // INITIALIZATION spend: the greedy 1-ply root build, which v6 deliberately does NOT charge to the
    // budget (it is identical across all configs, and its cost scales with branching factor, which
    // differs systematically by scenario source). Logged separately so its size is reportable.
    private final double initComputeUnits;
    private final long   initAdvances;
    private final long   initCopies;
    private final long   initEvals;
    private final int    initFmCalls;
    // Wall-clock, recorded as evidence only — never used as a budget.
    private final long   planMillis;     // whole planning episode (init + search)
    private final long   initMillis;     // initialization portion of the above

    /** Legacy constructor (pre-v6 agents: MCTS, OEP). v6 spend columns are recorded as -1. */
    public TurnData(int gameId, int turnNumber, int scoreAtTurnStart,
                    double initialSequenceValue, double finalSequenceValue, double avgNodeValue,
                    int fmCalls, int nodesEvaluated, int uniqueSequences) {
        this(gameId, turnNumber, scoreAtTurnStart, initialSequenceValue, finalSequenceValue, avgNodeValue,
             fmCalls, nodesEvaluated, uniqueSequences,
             -1.0, -1L, -1L, -1L, -1.0, -1L, -1L, -1L, -1, -1L, -1L);
    }

    public TurnData(int gameId, int turnNumber, int scoreAtTurnStart,
                    double initialSequenceValue, double finalSequenceValue, double avgNodeValue,
                    int fmCalls, int nodesEvaluated, int uniqueSequences,
                    double computeUnits, long advances, long copies, long evals,
                    double initComputeUnits, long initAdvances, long initCopies, long initEvals,
                    int initFmCalls, long planMillis, long initMillis) {
        this.gameId = gameId;
        this.turnNumber = turnNumber;
        this.scoreAtTurnStart = scoreAtTurnStart;
        this.initialSequenceValue = initialSequenceValue;
        this.finalSequenceValue = finalSequenceValue;
        this.avgNodeValue = avgNodeValue;
        this.fmCalls = fmCalls;
        this.nodesEvaluated = nodesEvaluated;
        this.uniqueSequences = uniqueSequences;
        this.computeUnits = computeUnits;
        this.advances = advances;
        this.copies = copies;
        this.evals = evals;
        this.initComputeUnits = initComputeUnits;
        this.initAdvances = initAdvances;
        this.initCopies = initCopies;
        this.initEvals = initEvals;
        this.initFmCalls = initFmCalls;
        this.planMillis = planMillis;
        this.initMillis = initMillis;
    }

    public double getComputeUnits()     { return computeUnits; }
    public long   getAdvances()         { return advances; }
    public long   getCopies()           { return copies; }
    public long   getEvals()            { return evals; }
    public double getInitComputeUnits() { return initComputeUnits; }
    public long   getInitAdvances()     { return initAdvances; }
    public long   getInitCopies()       { return initCopies; }
    public long   getInitEvals()        { return initEvals; }
    public int    getInitFmCalls()      { return initFmCalls; }
    public long   getPlanMillis()       { return planMillis; }
    public long   getInitMillis()       { return initMillis; }
    /** Forward-model calls spent on SEARCH only (total minus initialization). */
    public int    getSearchFmCalls()    { return initFmCalls < 0 ? fmCalls : fmCalls - initFmCalls; }

    public int getGameId() {
        return gameId;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public int getScoreAtTurnStart() {
        return scoreAtTurnStart;
    }

    public double getInitialSequenceValue() {
        return initialSequenceValue;
    }

    public double getFinalSequenceValue() {
        return finalSequenceValue;
    }

    public double getAvgNodeValue() {
        return avgNodeValue;
    }

    public int getFmCalls() {
        return fmCalls;
    }

    public int getNodesEvaluated() {
        return nodesEvaluated;
    }

    public int getUniqueSequences() {
        return uniqueSequences;
    }

    @Override
    public String toString() {
        return "TurnData{" +
                "gameId=" + gameId +
                ", turnNumber=" + turnNumber +
                ", scoreAtTurnStart=" + scoreAtTurnStart +
                ", initialSequenceValue=" + initialSequenceValue +
                ", finalSequenceValue=" + finalSequenceValue +
                ", avgNodeValue=" + avgNodeValue +
                ", fmCalls=" + fmCalls +
                ", nodesEvaluated=" + nodesEvaluated +
                ", uniqueSequences=" + uniqueSequences +
                '}';
    }
}
