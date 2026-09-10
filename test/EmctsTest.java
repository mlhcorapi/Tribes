import players.ExperimentalEMCTS.ExperimentalEMCTSAgent;
import players.ExperimentalEMCTS.ExperimentalEMCTSParams;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmctsTest {
    @Test
    public void testCreation() {
        ExperimentalEMCTSParams params = new ExperimentalEMCTSParams();
        ExperimentalEMCTSAgent agent = new ExperimentalEMCTSAgent(1234, params);
        assertNotNull(agent);
    }
}
