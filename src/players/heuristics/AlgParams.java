package players.heuristics;

import players.portfolio.Portfolio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class AlgParams
{
    public final int STOP_TIME = 0;
    public final int STOP_ITERATIONS = 1;
    public final int STOP_FMCALLS = 2;
    public final int STOP_COMPUTE = 3; // deterministic compute-unit budget (ComputeMeter); used by the EMCTS family in Phase 1/3

    public final int ENTROPY_HEURISTIC = 0;
    public final int SIMPLE_HEURISTIC = 1;
    public final int DIFF_HEURISTIC = 2;
    public int heuristic_method = SIMPLE_HEURISTIC;

    public double epsilon = 1e-6;

    // Budget settings
    public int stop_type = STOP_TIME;
    public int num_iterations = 200;
    public int num_fmcalls = 2000;
    public int num_computeUnits = 13400; // budget when stop_type == STOP_COMPUTE (compute-units; see ComputeMeter)
    // Explicit per-turn SEARCH time budget in ms, used when stop_type == STOP_TIME and this is > 0.
    // 0 (the default) keeps the historical behaviour: the budget is whatever the engine's per-turn
    // ElapsedCpuTimer has left. The v6 calibration (CalibrationRunnerV6) sets it so that each agent
    // gets exactly T ms of SEARCH regardless of how long its greedy initialization took.
    public int num_timeMillis = 0;
    public int num_time = 40;
    /**
     * ComputeMeter weight profile for this agent, e.g. {@code CrossoverCombo_r5@400000}, built by
     * {@code ComputeMeter.profileKey}. An advance, a copy and an eval do not cost the same for every
     * configuration or at every budget level, so a compute-unit budget is only equal compute if each
     * configuration is priced by its own measured weights; the agent installs this on its own thread
     * at the start of each planning episode.
     *
     * <p>Null (the default) means the global weights, which is what every non-v6 agent uses and what
     * the whole pipeline falls back to when no calibration file is present.
     */
    public String computeProfile = null;
    public int FORCE_TURN_END = 5;
    public boolean PRIORITIZE_ROOT = false;


    public void setParameterValue(String param, Object value) { }
    public Object getParameterValue(String param) { return null; }
    public ArrayList<String> getParameters() { return null; }
    public Map<String, Object[]> getParameterValues() { return null; }

    public Map<String, String[]> constantNames() {
        HashMap<String, String[]> names = new HashMap<>();
        names.put("heuristic_method", new String[]{"SIMPLE_HEURISTIC", "ENTROPY_HEURISTIC"});
        return names;
    }

    public StateHeuristic getStateHeuristic(int playerID, ArrayList<Integer> allIDs)
    {
        if (heuristic_method == ENTROPY_HEURISTIC)
            return new TribesEntropyHeuristic(playerID, allIDs);
        else if (heuristic_method == SIMPLE_HEURISTIC) // New method: combined heuristics
            return new TribesSimpleHeuristic(playerID, allIDs);
        else if (heuristic_method == DIFF_HEURISTIC)
            return new TribesDiffHeuristic(playerID, allIDs);
        return null;
    }

    public PruneHeuristic getPruneHeuristic()
    {
        return null;
    }
}
