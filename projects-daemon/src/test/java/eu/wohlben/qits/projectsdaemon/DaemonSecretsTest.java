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
import eu.wohlben.qits.commands.ActionResolver;
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
 * {@link DaemonSecrets}: the daemon's secrets — the delivery credential, the API token, the
 * container's own token — reach no child that is not meant to hold them, whichever way the child is
 * spawned (qits-1495, qits-1482).
 */
class DaemonSecretsTest {

  private static final Map<String, String> ALL_BLANK =
      Map.of(
          "QITS_DELIVERY_TOKEN", "",
          "QITS_DELIVERY_TOKEN_SUBJECT", "",
          "QITS_PROJECTS_DAEMON_API_TOKEN", "",
          "QITS_TOKEN", "",
          "QITS_TOKEN_SUBJECT", "");

  private static AgentLaunchRequest request(Map<String, String> overlay) {
    return new AgentLaunchRequest(
            AgentMcpScope.REPOSITORY,
            AgentSurface.PROJECT_WORK,
            AgentLaunchMode.INTERACTIVE,
            null,
            null,
            false,
            false,
            null)
        .withEnvironment(overlay);
  }

  private static Map<String, String> with(Map<String, String> base, String... pairs) {
    Map<String, String> out = new HashMap<>(base);
    for (int i = 0; i < pairs.length; i += 2) {
      out.put(pairs[i], pairs[i + 1]);
    }
    return out;
  }

  @Test
  void theScrubCoversAllFiveNames() {
    assertEquals(ALL_BLANK, DaemonSecrets.SCRUB);
    assertEquals(
        List.of(
            "QITS_DELIVERY_TOKEN",
            "QITS_DELIVERY_TOKEN_SUBJECT",
            "QITS_PROJECTS_DAEMON_API_TOKEN",
            "QITS_TOKEN",
            "QITS_TOKEN_SUBJECT"),
        DaemonSecrets.NAMES);
  }

  @Test
  void aProjectWorkLaunchIsScrubbedAndKeepsItsOwnOverlay() {
    AgentLaunchRequest scrubbed =
        DaemonSecrets.scrubbed(
            request(
                Map.of(
                    "FOO", "bar",
                    "QITS_DELIVERY_TOKEN", "smuggled",
                    "QITS_PROJECTS_DAEMON_API_TOKEN", "smuggled",
                    "QITS_TOKEN", "smuggled")));

    assertEquals(
        with(ALL_BLANK, "FOO", "bar"),
        scrubbed.environment(),
        "the scrub wins over anything the request carried");
    assertEquals(AgentSurface.PROJECT_WORK, scrubbed.surface());
  }

  /** The session's own credential is the one thing a launch may lay back on. */
  @Test
  void aLaunchRunsOnTheCredentialItIsHandedAndNothingInherited() {
    AgentLaunchRequest launched =
        DaemonSecrets.launchedAs(
            request(Map.of("FOO", "bar", "QITS_PROJECTS_DAEMON_API_TOKEN", "smuggled")),
            DaemonSecrets.credential("qits_tok_desk", "tok-desk-session-p-1"));

    assertEquals(
        with(
            ALL_BLANK,
            "FOO",
            "bar",
            "QITS_TOKEN",
            "qits_tok_desk",
            "QITS_TOKEN_SUBJECT",
            "tok-desk-session-p-1"),
        launched.environment());
    assertEquals(
        Map.of("QITS_TOKEN", "", "QITS_TOKEN_SUBJECT", ""),
        DaemonSecrets.credential(null, null),
        "no credential is a removal, never an inheritance");
  }

  @Test
  void aSessionProcessBuilderEnvironmentLosesAllFive() {
    Map<String, String> environment = daemonEnvironment();

    DaemonSecrets.scrubSession(environment);

    assertEquals(Map.of("PATH", "/usr/bin"), environment);
  }

  /**
   * The daemon's own git and maintenance processes keep QITS_TOKEN — the git credential helper and
   * the qits CLI authenticate with it — and lose everything else.
   */
  @Test
  void aDaemonProcessBuilderEnvironmentKeepsOnlyTheContainersOwnToken() {
    Map<String, String> environment = daemonEnvironment();

    DaemonSecrets.scrubDaemon(environment);

    assertEquals(
        Map.of(
            "PATH", "/usr/bin",
            "QITS_TOKEN", "project-token",
            "QITS_TOKEN_SUBJECT", "tok-agent-container-p-1"),
        environment);
  }

  @Test
  void aProcessRunnerChildSeesAllFiveBlank() {
    List<Map<String, String>> seen = new ArrayList<>();
    ProcessRunner runner =
        DaemonSecrets.processes(
            (command, cwd, env, timeout) -> {
              seen.add(env);
              return new ProcessRunner.Result(0, "", "", false);
            });

    runner.exec(List.of("claude"), Path.of("/"), Map.of("HOME", "/claude-home"), Duration.ZERO);

    assertEquals(with(ALL_BLANK, "HOME", "/claude-home"), seen.getFirst());
  }

  /** The sign-in terminal takes no overlay; the commands seam is what blanks it. */
  @Test
  void everySpawnedSessionSeesAllFiveBlankAndEverythingElseIsForwarded() {
    RecordingCommands recording = new RecordingCommands();
    AgentCommands commands = DaemonSecrets.commands(recording);

    commands.launchAgent("sign in", "claude", true, Map.of("HOME", "/h"), "c-1", null, null, null);
    commands.launchChat("chat", "claude", null, "c-2", null, null, null, null);
    commands.launchAgent(
        "desk",
        "claude",
        true,
        Map.of("QITS_TOKEN", "qits_tok_desk", "QITS_TOKEN_SUBJECT", "tok-desk"),
        "c-3",
        null,
        null,
        null);

    assertEquals(with(ALL_BLANK, "HOME", "/h"), recording.environments.get(0));
    assertEquals(ALL_BLANK, recording.environments.get(1));
    assertEquals(
        with(ALL_BLANK, "QITS_TOKEN", "qits_tok_desk", "QITS_TOKEN_SUBJECT", "tok-desk"),
        recording.environments.get(2),
        "a credential the launch decided survives the blanking");

    assertTrue(commands.hasDraft("c-1"), "defaults are forwarded, not answered by the interface");
    assertSame(GuardedInput.HELD_FOR_DRAFT, commands.sendKeystrokesUnlessDraft("c-1", "x"));
    assertTrue(commands.chatRename("c-2", "n"));
    assertTrue(commands.sendKeystrokes("c-1", "x"));
    assertFalse(commands.chatSend("c-2", "x"));
    assertTrue(commands.ownsSession("s"));
    assertEquals(Optional.of("CLAUDE"), commands.agentTypeForSession("s"));
  }

  /** A declared action (POST /commands) loses all five, whatever it declared for itself. */
  @Test
  void aDeclaredActionSeesAllFiveBlank() {
    ActionResolver.ResolvedAction declared =
        new ActionResolver.ResolvedAction(
            "build", "Build", "make", false, Map.of("FOO", "bar", "QITS_TOKEN", "smuggled"));
    ActionResolver actions =
        DaemonSecrets.actions(
            new ActionResolver() {
              @Override
              public Optional<ResolvedAction> resolve(String actionId) {
                return "build".equals(actionId) ? Optional.of(declared) : Optional.empty();
              }

              @Override
              public List<ResolvedAction> actions() {
                return List.of(declared);
              }
            });

    ActionResolver.ResolvedAction resolved = actions.resolve("build").orElseThrow();
    assertEquals(with(ALL_BLANK, "FOO", "bar"), resolved.environment());
    assertEquals("make", resolved.executeScript());
    assertEquals(with(ALL_BLANK, "FOO", "bar"), actions.actions().getFirst().environment());
    assertEquals(Optional.empty(), actions.resolve("nope"));
  }

  /** What the container is created with: every secret, and something that is not one. */
  private static Map<String, String> daemonEnvironment() {
    Map<String, String> environment = new HashMap<>();
    environment.put("PATH", "/usr/bin");
    environment.put("QITS_DELIVERY_TOKEN", "secret");
    environment.put("QITS_DELIVERY_TOKEN_SUBJECT", "desk-delivery:p-1");
    environment.put("QITS_PROJECTS_DAEMON_API_TOKEN", "api-secret");
    environment.put("QITS_TOKEN", "project-token");
    environment.put("QITS_TOKEN_SUBJECT", "tok-agent-container-p-1");
    return environment;
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
