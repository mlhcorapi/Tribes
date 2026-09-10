package players.emcts;

import players.heuristics.AlgParams;

public class EMCTSParams extends AlgParams {
    public float bias = 0f;
    public int depth = 21; // phases per turn; bridge burns = depth - 1 = 20
    public int NODE_SIZE = 20;
    /** v4 Isolation Phase: when true, the root's INITIAL action sequence is built by sampling
     *  uniformly at random from valid actions instead of the default 1-ply greedy. The greedy
     *  REPAIR mechanism used in-search (mutate/crossover) is unaffected. Same {@code m_rnd} seed
     *  across all agents in a (scenario, visit) yields the same starting sequence. */
    public boolean randomInitialSequence = false;
}
