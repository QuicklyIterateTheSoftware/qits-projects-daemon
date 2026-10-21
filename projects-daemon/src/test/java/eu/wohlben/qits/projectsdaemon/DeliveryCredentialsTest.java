package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.ProcessRunner;
import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.GuardedInput;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link DeliveryCredentials}: the delivery desk's credential reaches no child but the delivery
 * session, whichever way the child is spawned (qits-1495).
 */
class DeliveryCredentialsTest {

  @Test
  void aProjectWorkLaunchIsScrubbedAndKeepsItsOwnOverlay() {
    AgentLaunchRequest request =
        new AgentLaunchRequest(
                AgentMcpScope.REPOSITORY,
                AgentSurface.PROJECT_WORK,
                AgentLaunchMode.INTERACTIVE,
                null,
                null,
                false,
                false,
                null)
            .withEnvironment(Map.of("FOO", "bar", "QITS_DELIVERY_TOKEN", "smuggled"));

    AgentLaunchRequest scrubbed = DeliveryCredentials.scrubbed(request);

    assertEquals(
        Map.of("FOO", "bar", "QITS_DELIVERY_TOKEN", "", "QITS_DELIVERY_TOKEN_SUBJECT", ""),
        scrubbed.environment(),
        "the scrub wins over anything the request carried");
    assertEquals(AgentSurface.PROJECT_WORK, scrubbed.surface());
  }

  @Test
  void aProcessBuilderEnvironmentLosesBothNames() {
    Map<String, String> environment = new HashMap<>();
    environment.put("QITS_DELIVERY_TOKEN", "secret");
    environment.put("QITS_DELIVERY_TOKEN_SUBJECT", "desk-delivery:p-1");
    environment.put("QITS_TOKEN", "project-token");

    DeliveryCredentials.scrub(environment);

    assertEquals(Map.of("QITS_TOKEN", "project-token"), environment);
  }

  @Test
  void aProcessRunnerChildSeesBothNamesBlank() {
    List<Map<String, String>> seen = new ArrayList<>();
    ProcessRunner runner =
        DeliveryCredentials.processes(
            (command, cwd, env, timeout) -> {
              seen.add(env);
              return new ProcessRunner.Result(0, "", "", false);
            });

    runner.exec(List.of("claude"), Path.of("/"), Map.of("HOME", "/claude-home"), Duration.ZERO);

    assertEquals(
        Map.of("HOME", "/claude-home", "QITS_DELIVERY_TOKEN", "", "QITS_DELIVERY_TOKEN_SUBJECT", ""),
        seen.getFirst());
  }

  /** The sign-in terminal takes no overlay; the commands seam is what blanks it. */
  @Test
  void everySpawnedSessionSeesBothNamesBlankAndEverythingElseIsForwarded() {
    RecordingCommands recording = new RecordingCommands();
    AgentCommands commands = DeliveryCredentials.commands(recording);

    commands.launchAgent("sign in", "claude", true, Map.of("HOME", "/h"), "c-1", null, null, null);
    commands.launchChat("chat", "claude", null, "c-2", null, null, null, null);

    assertEquals(
        Map.of("HOME", "/h", "QITS_DELIVERY_TOKEN", "", "QITS_DELIVERY_TOKEN_SUBJECT", ""),
        recording.environments.get(0));
    assertEquals(
        Map.of("QITS_DELIVERY_TOKEN", "", "QITS_DELIVERY_TOKEN_SUBJECT", ""),
        recording.environments.get(1));

    assertTrue(commands.hasDraft("c-1"), "defaults are forwarded, not answered by the interface");
    assertSame(GuardedInput.HELD_FOR_DRAFT, commands.sendKeystrokesUnlessDraft("c-1", "x"));
    assertTrue(commands.chatRename("c-2", "n"));
    assertTrue(commands.sendKeystrokes("c-1", "x"));
    assertFalse(commands.chatSend("c-2", "x"));
    assertTrue(commands.ownsSession("s"));
    assertEquals(Optional.of("CLAUDE"), commands.agentTypeForSession("s"));
  }

  private static final class RecordingCommands implements AgentCommands {
    final List<Map<String, String>> environments = new ArrayList<>();

    @Override
    public Command launchAgent(
        String name,
        String script,
        boolean interactive,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef agentSession,
        CommandExitListener onExit,
        AgentLaunchMetadata agent) {
      environments.add(environment);
      return null;
    }

    @Override
    public Command launchChat(
        String name,
        String script,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef agentSession,
        CommandExitListener onExit,
        ChatProtocolFactory protocolFactory,
        AgentLaunchMetadata agent) {
      environments.add(environment);
      return null;
    }

    @Override
    public boolean chatSend(String commandId, String text) {
      return false;
    }

    @Override
    public boolean chatRename(String commandId, String name) {
      return true;
    }

    @Override
    public boolean sendKeystrokes(String commandId, String text) {
      return true;
    }

    @Override
    public boolean hasDraft(String commandId) {
      return true;
    }

    @Override
    public GuardedInput sendKeystrokesUnlessDraft(String commandId, String text) {
      return GuardedInput.HELD_FOR_DRAFT;
    }

    @Override
    public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {}

    @Override
    public boolean ownsSession(String sessionId) {
      return true;
    }

    @Override
    public Optional<String> agentTypeForSession(String sessionId) {
      return Optional.of("CLAUDE");
    }
  }
}
