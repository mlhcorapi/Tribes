package players.mcts;

import core.actions.Action;
import core.actions.tribeactions.EndTurn;
import core.game.Game;
import core.game.GameState;
import players.Agent;
import players.heuristics.StateHeuristic;
import utils.ElapsedCpuTimer;
import core.data.dto.TurnData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static core.Constants.TURN_TIME_MILLIS;

public class MCTSPlayer extends Agent {

    private List<TurnData> turnDataLog = new ArrayList<>();
    private Random m_rnd;
    private MCTSParams params;
    private int turnNodesVisited = 0;
    private int turnFmCalls = 0;
    private int turnStartTick = -1;
    private int turnStartScore = 0;
    private GameState turnStartState = null;
    private StateHeuristic heuristic;

    public MCTSPlayer(long seed)
    {
        super(seed);
        m_rnd = new Random(seed);
        this.params = new MCTSParams();
    }

    public MCTSPlayer(long seed, MCTSParams params) {
        this(seed);
        this.params = params;
    }

    public Action act(GameState gs, ElapsedCpuTimer ect) {
        // Capture the score at the start of the turn. The engine calls act() once per
        // action, so a change in game tick marks the first act() of a new turn.
        if (gs.getTick() != turnStartTick) {
            turnStartTick = gs.getTick();
            turnStartScore = gs.getScore(playerID);
            turnStartState = gs.copy();
            heuristic = params.getStateHeuristic(playerID, allPlayerIDs);
        }

        //Gather all available actions:
        ArrayList<Action> allActions = gs.getAllAvailableActions();

        Action bestAction;
        if (allActions.size() == 1) {
            bestAction = allActions.get(0); //EndTurn, it's possible.
        } else {
            ArrayList<Action> rootActions = params.PRIORITIZE_ROOT ? determineActionGroup(gs, m_rnd) : allActions;
            if (rootActions == null) {
                bestAction = new EndTurn(gs.getActiveTribeID());
            } else {
                SingleTreeNode m_root = new SingleTreeNode(params, m_rnd, rootActions.size(), rootActions, this.playerID);
                m_root.setRootGameState(m_root, gs, allPlayerIDs);
                m_root.mctsSearch(ect);
                bestAction = rootActions.get(m_root.mostVisitedAction());
                turnNodesVisited += m_root.getNumNodes();
                turnFmCalls += m_root.getNumFmCalls();
            }
        }

        // At turn end, log the turn's telemetry. The "sequence" values are the SIMPLE-heuristic value
        // of the board at turn start vs the state MCTS reached by turn end (its achieved search value),
        // so MCTS is comparable on final value. MCTS is a per-action tree search, so uniqueSequences
        // and avgNodeValue are N/A (-1); NodesEvaluated = MCTS tree nodes, FmCalls = forward-model calls.
        if (bestAction instanceof EndTurn || bestAction.getActionType() == core.Types.ACTION.END_TURN) {
            double initialValue = (heuristic != null && turnStartState != null) ? heuristic.evaluateState(turnStartState, turnStartState) : 0.0;
            double finalValue = (heuristic != null && turnStartState != null) ? heuristic.evaluateState(turnStartState, gs) : 0.0;
            turnDataLog.add(new TurnData(-1, gs.getTick(), turnStartScore, initialValue, finalValue, -1.0,
                    turnFmCalls, turnNodesVisited, -1));
            turnNodesVisited = 0;
            turnFmCalls = 0;
        }

        return bestAction;
    }

    @Override
    public List<TurnData> getTurnDataLog() {
        return turnDataLog;
    }

    @Override
    public Agent copy() {
        return null;
    }

}