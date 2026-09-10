package core.data.dto;

public class GameSummaryData {
    private final String agentName;
    private final int gameId;
    private final int gameDurationTurns;
    private final int gameOutcomeScore;

    public GameSummaryData(String agentName, int gameId, int gameDurationTurns, int gameOutcomeScore) {
        this.agentName = agentName;
        this.gameId = gameId;
        this.gameDurationTurns = gameDurationTurns;
        this.gameOutcomeScore = gameOutcomeScore;
    }

    public String getAgentName() {
        return agentName;
    }

    public int getGameId() {
        return gameId;
    }

    public int getGameDurationTurns() {
        return gameDurationTurns;
    }

    public int getGameOutcomeScore() {
        return gameOutcomeScore;
    }

    @Override
    public String toString() {
        return "GameSummaryData{" +
                "agentName='" + agentName + '\'' +
                ", gameId=" + gameId +
                ", gameDurationTurns=" + gameDurationTurns +
                ", gameOutcomeScore=" + gameOutcomeScore +
                '}';
    }
}
