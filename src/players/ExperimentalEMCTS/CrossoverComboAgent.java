package players.ExperimentalEMCTS;

/**
 * CrossoverCombo: uses crossover in BOTH expansion and bridge burning. All search and
 * telemetry machinery is inherited from {@link ExperimentalEMCTSAgent}; this class only
 * flips the crossover flags so the experiment isolates the combined impact of crossover.
 */
public class CrossoverComboAgent extends ExperimentalEMCTSAgent {

    /** Default configuration (used by the smoke test). */
    public CrossoverComboAgent(long seed) {
        super(seed, createParams());
    }

    /** Configurable constructor used by the experiment runners (see CrossoverExpansionAgent). */
    public CrossoverComboAgent(long seed, ExperimentalEMCTSParams params) {
        super(seed, applyVariant(params));
    }

    private static ExperimentalEMCTSParams applyVariant(ExperimentalEMCTSParams params) {
        params.useCrossoverBridgeBurning = true;
        params.useCrossoverExpansion = true;
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
    public CrossoverComboAgent copy() {
        return new CrossoverComboAgent(getSeed());
    }
}
