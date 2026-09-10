package players.ExperimentalEMCTS;

/**
 * CrossoverBB: standard EMCTS search plus Crossover Bridge Burning at phase transitions.
 * All search/telemetry machinery is inherited from {@link ExperimentalEMCTSAgent}; this
 * class only flips the crossover flags so the experiment isolates the impact of crossover.
 */
public class CrossoverBBAgent extends ExperimentalEMCTSAgent {

    /** Default configuration (used by the smoke test). */
    public CrossoverBBAgent(long seed) {
        super(seed, createParams());
    }

    /** Configurable constructor used by the experiment runners (see CrossoverExpansionAgent). */
    public CrossoverBBAgent(long seed, ExperimentalEMCTSParams params) {
        super(seed, applyVariant(params));
    }

    private static ExperimentalEMCTSParams applyVariant(ExperimentalEMCTSParams params) {
        params.useCrossoverBridgeBurning = true;
        params.useCrossoverExpansion = false;
        return params;
    }

    private static ExperimentalEMCTSParams createParams() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        params.stop_type = params.STOP_FMCALLS;
        params.num_fmcalls = 8000;
        params.heuristic_method = params.SIMPLE_HEURISTIC;
        return applyVariant(params);
    }

    @Override
    public CrossoverBBAgent copy() {
        return new CrossoverBBAgent(getSeed());
    }
}
