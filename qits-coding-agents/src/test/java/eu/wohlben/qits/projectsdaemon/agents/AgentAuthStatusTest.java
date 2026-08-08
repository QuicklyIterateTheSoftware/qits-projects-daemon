package eu.wohlben.qits.projectsdaemon.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdaemon.commands.CheckoutUnavailableException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The login probe, and what it does when there is no checkout to probe from. */
class AgentAuthStatusTest {

  private static final String CLAUDE_MOUNT = "/claude-home";

  @TempDir Path workspaceRoot;

  /** Answers every probe with one canned result, and records that it was asked. */
  private static final class Probe implements ProcessRunner {
    private final int exitCode;
    private final String stdout;
    private boolean asked;

    private Probe(int exitCode, String stdout) {
      this.exitCode = exitCode;
      this.stdout = stdout;
    }

    @Override
    public Result exec(List<String> command, Path cwd, Map<String, String> env, Duration timeout) {
      asked = true;
      return new Result(exitCode, stdout, "", false);
    }
  }

  @Test
  void readsTheSignedInStateFromTheProbe() {
    Probe probe = new Probe(0, "{\"loggedIn\": true}");

    assertTrue(new AgentAuthStatus(probe, CLAUDE_MOUNT, workspaceRoot).isLoggedIn(AgentType.CLAUDE));
  }

  @Test
  void aMissingCheckoutIsReportedRatherThanReadAsSignedOut() {
    // A failed self-provision leaves no /workspace. Answering "signed out" would send the launch to
    // an interactive login terminal that cannot spawn either, and the operator would chase a login
    // prompt instead of the clone that failed.
    Probe probe = new Probe(0, "{\"loggedIn\": true}");
    Path missing = workspaceRoot.resolve("never-cloned");

    CheckoutUnavailableException failure =
        assertThrows(
            CheckoutUnavailableException.class,
            () -> new AgentAuthStatus(probe, CLAUDE_MOUNT, missing).isLoggedIn(AgentType.CLAUDE));

    assertTrue(failure.getMessage().contains("never-cloned"), "the message names the path");
    assertFalse(probe.asked, "the probe never runs — it would fail to start in a missing directory");
  }
}
