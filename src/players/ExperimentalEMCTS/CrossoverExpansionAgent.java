package players.ExperimentalEMCTS;

/**
 * CrossoverExpansion: replaces standard mutation with Crossover Expansion on a set ratio
 * during tree growth; uses standard (non-crossover) bridge burning. All search and
 * telemetry machinery is inherited from {@link ExperimentalEMCTSAgent}; this class only
 * flips the crossover flags so the experiment isolates the impact of crossover expansion.
 */
public class CrossoverExpansionAgent extends ExperimentalEMCTSAgent {

    /** Default configuration (used by the smoke test). */
    public CrossoverExpansionAgent(long seed) {
        super(seed, createParams());
    }

    /**
     * Configurable constructor used by the experiment runners. The caller controls
     * stop_type, the budget (num_fmcalls / num_time), mutationExpansionRatio, heuristic,
     * depth, etc.; this agent only forces the crossover flags that define the variant.
     */
    public CrossoverExpansionAgent(long seed, ExperimentalEMCTSParams params) {
        super(seed, applyVariant(params));
    }

    private static ExperimentalEMCTSParams applyVariant(ExperimentalEMCTSParams params) {
        params.useCrossoverBridgeBurning = false;
        params.useCrossoverExpansion = true;
        return params;
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.num_fmcalls = 8000;
        params.heuristic_method = params.SIMPLE_HEURISTIC;
        params.mutationExpansionRatio = 5;
        return applyVariant(params);
    }

    @Override
    public CrossoverExpansionAgent copy() {
        return new CrossoverExpansionAgent(getSeed());
    }
}
