package core.data.dto;

public class BridgeBurnEventData {
    private final int gameId;
    private final int turnNumber;
    private final long expansionPhaseTimestamp;
    private final int bridgeBurningPhaseNumber;
    private final double bestSequenceValue;
    private final int fmCallsElapsed; // forward-model calls done so far this turn (deterministic search-progress measure)
    /** v6: compute-units spent on SEARCH so far this turn (greedy initialization excluded — see
     *  ExperimentalEMCTSAgent.planTurn). Gives the per-phase budget trajectory, so within-turn drift
     *  in the advance/copy/eval mix is observable and per-phase budget adherence is checkable.
     *  -1 when not recorded (agents that do not gate on the ComputeMeter). */
    private final double computeUnitsElapsed;

    public BridgeBurnEventData(int gameId, int turnNumber, long expansionPhaseTimestamp, int bridgeBurningPhaseNumber, double bestSequenceValue, int fmCallsElapsed) {
        this(gameId, turnNumber, expansionPhaseTimestamp, bridgeBurningPhaseNumber, bestSequenceValue, fmCallsElapsed, -1.0);
    }

    public BridgeBurnEventData(int gameId, int turnNumber, long expansionPhaseTimestamp, int bridgeBurningPhaseNumber, double bestSequenceValue, int fmCallsElapsed, double computeUnitsElapsed) {
        this.gameId = gameId;
        this.turnNumber = turnNumber;
        this.expansionPhaseTimestamp = expansionPhaseTimestamp;
        this.bridgeBurningPhaseNumber = bridgeBurningPhaseNumber;
        this.bestSequenceValue = bestSequenceValue;
        this.fmCallsElapsed = fmCallsElapsed;
        this.computeUnitsElapsed = computeUnitsElapsed;
    }

    public double getComputeUnitsElapsed() {
        return computeUnitsElapsed;
    }

    public int getGameId() {
        return gameId;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public long getExpansionPhaseTimestamp() {
        return expansionPhaseTimestamp;
    }

    public int getBridgeBurningPhaseNumber() {
        return bridgeBurningPhaseNumber;
    }

    public double getBestSequenceValue() {
        return bestSequenceValue;
    }

    public int getFmCallsElapsed() {
        return fmCallsElapsed;
    }

    @Override
    public String toString() {
        return "BridgeBurnEventData{" +
                "gameId=" + gameId +
                ", turnNumber=" + turnNumber +
                ", expansionPhaseTimestamp=" + expansionPhaseTimestamp +
                ", bridgeBurningPhaseNumber=" + bridgeBurningPhaseNumber +
                ", bestSequenceValue=" + bestSequenceValue +
                ", fmCallsElapsed=" + fmCallsElapsed +
                '}';
    }
}
