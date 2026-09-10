package core.data.dto;

public class CrossoverEventData {
    private final int gameId;
    private final int turnNumber;
    private final long expansionPhaseTimestamp;
    private final int bridgeBurningPhaseNumber;
    private final double bestSequenceBefore;
    private final double bestSequenceAfter;
    private final double rawImprovement;
    private final double percentageImprovement;
    private final int fmCallsElapsed; // forward-model calls done so far this turn (deterministic search-progress measure)

    public CrossoverEventData(int gameId, int turnNumber, long expansionPhaseTimestamp, int bridgeBurningPhaseNumber, double bestSequenceBefore, double bestSequenceAfter, double rawImprovement, double percentageImprovement, int fmCallsElapsed) {
        this.gameId = gameId;
        this.turnNumber = turnNumber;
        this.expansionPhaseTimestamp = expansionPhaseTimestamp;
        this.bridgeBurningPhaseNumber = bridgeBurningPhaseNumber;
        this.bestSequenceBefore = bestSequenceBefore;
        this.bestSequenceAfter = bestSequenceAfter;
        this.rawImprovement = rawImprovement;
        this.percentageImprovement = percentageImprovement;
        this.fmCallsElapsed = fmCallsElapsed;
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

    public double getBestSequenceBefore() {
        return bestSequenceBefore;
    }

    public double getBestSequenceAfter() {
        return bestSequenceAfter;
    }

    public double getRawImprovement() {
        return rawImprovement;
    }

    public double getPercentageImprovement() {
        return percentageImprovement;
    }

    public int getFmCallsElapsed() {
        return fmCallsElapsed;
    }

    @Override
    public String toString() {
        return "CrossoverEventData{" +
                "gameId=" + gameId +
                ", turnNumber=" + turnNumber +
                ", expansionPhaseTimestamp=" + expansionPhaseTimestamp +
                ", bridgeBurningPhaseNumber=" + bridgeBurningPhaseNumber +
                ", bestSequenceBefore=" + bestSequenceBefore +
                ", bestSequenceAfter=" + bestSequenceAfter +
                ", rawImprovement=" + rawImprovement +
                ", percentageImprovement=" + percentageImprovement +
                ", fmCallsElapsed=" + fmCallsElapsed +
                '}';
    }
}
