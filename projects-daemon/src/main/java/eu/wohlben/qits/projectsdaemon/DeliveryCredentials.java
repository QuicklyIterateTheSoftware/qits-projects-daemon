package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.ProcessRunner;
import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.GuardedInput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the delivery desk's credential ({@code QITS_DELIVERY_TOKEN}, {@code
 * QITS_DELIVERY_TOKEN_SUBJECT}) out of every child this daemon spawns, the delivery session
 * excepted (qits-1495).
 *
 * <p>The container is created with both names in its environment, and every child inherits the
 * daemon's environment by default — so without this, any agent a person starts, any {@code
 * RunCommand}, the checkout follower and the git reads would all hold a token whose roles ({@code
 * qits:desk}) none of them was meant to carry. The delivery session does not need the names either:
 * {@link DeliveryKeeper} hands it the token as its {@code QITS_TOKEN}, which is the spelling the CLI
 * and the platform MCP server read.
 *
 * <p>Three shapes of spawn, three answers:
 *
 * <ul>
 *   <li><b>agent launches</b> carry {@link #SCRUB} as their environment overlay ({@link
 *       #scrubbed}): the library removes an overlay key mapped to {@code ""} and opens the script
 *       with an {@code unset} of it. That covers {@link FrontDeskKeeper}'s desk and every launch
 *       through {@code POST /agents}.
 *   <li><b>the daemon's own {@link ProcessBuilder}s</b> — {@link CommandExecutor} ({@code
 *       RunCommand}), {@link CheckoutFollower}, {@link Provisioner}, {@link ProjectDescriber} —
 *       remove both names from the inherited environment ({@link #scrub(Map)}).
 *   <li><b>what the library spawns without a request</b> — the sign-in terminal, the auth and
 *       capability probes, the token preflight — goes through {@link #commands} and {@link
 *       #processes}, which blank both names on the way in. Blank rather than absent because those
 *       seams take an environment to lay over the inherited one and cannot express a removal; an
 *       empty value carries no credential.
 * </ul>
 */
final class DeliveryCredentials {

  /** The delivery token's variable; also the source of {@code qits.projects-daemon.delivery-token}. */
  static final String TOKEN = "QITS_DELIVERY_TOKEN";

  /** Whom that token names. */
  static final String TOKEN_SUBJECT = "QITS_DELIVERY_TOKEN_SUBJECT";

  /** Both names, in the order a log or a test lists them. */
  static final List<String> NAMES = List.of(TOKEN, TOKEN_SUBJECT);

  /** The launch overlay that removes both names from a child: an empty value is a removal. */
  static final Map<String, String> SCRUB = Map.of(TOKEN, "", TOKEN_SUBJECT, "");

  private DeliveryCredentials() {}

  /**
   * {@code request} with {@link #SCRUB} laid over whatever overlay it already carries. The scrub
   * wins: no caller other than the delivery keeper may hand a child the delivery credential.
   */
  static AgentLaunchRequest scrubbed(AgentLaunchRequest request) {
    Map<String, String> overlay = new HashMap<>(request.environment());
    overlay.putAll(SCRUB);
    return request.withEnvironment(overlay);
  }

  /** Removes both names from a {@link ProcessBuilder#environment()} (or any mutable map). */
  static void scrub(Map<String, String> environment) {
    NAMES.forEach(environment::remove);
  }

  /** {@code environment} with both names set blank, for a seam that can only overlay. */
  static Map<String, String> blanked(Map<String, String> environment) {
    Map<String, String> out = environment == null ? new HashMap<>() : new HashMap<>(environment);
    NAMES.forEach(name -> out.putIfAbsent(name, ""));
    return out;
  }

  /** A {@link ProcessRunner} whose every child sees both names blank. */
  static ProcessRunner processes(ProcessRunner delegate) {
    return (command, cwd, env, timeout) -> delegate.exec(command, cwd, blanked(env), timeout);
  }

  /**
   * An {@link AgentCommands} whose every spawned session sees both names blank unless the launch
   * already decided them. This is what reaches the sign-in terminal, which takes no request and so
   * no overlay; for every other launch it only repeats what {@link #scrubbed} already did.
   *
   * <p>Every method is forwarded, defaults included: a default left to the interface would answer
   * for the production adapter (the draft guard, the rename) with the test double's behaviour.
   */
  static AgentCommands commands(AgentCommands delegate) {
    return new AgentCommands() {
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
        return delegate.launchAgent(
            name,
            script,
            interactive,
            blanked(environment),
            commandId,
            agentSession,
            onExit,
            agent);
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
        return delegate.launchChat(
            name,
            script,
            blanked(environment),
            commandId,
            agentSession,
            onExit,
            protocolFactory,
            agent);
      }

      @Override
      public boolean chatSend(String commandId, String text) {
        return delegate.chatSend(commandId, text);
      }

      @Override
      public boolean chatRename(String commandId, String name) {
        return delegate.chatRename(commandId, name);
      }

      @Override
      public boolean sendKeystrokes(String commandId, String text) {
        return delegate.sendKeystrokes(commandId, text);
      }

      @Override
      public boolean hasDraft(String commandId) {
        return delegate.hasDraft(commandId);
      }

      @Override
      public GuardedInput sendKeystrokesUnlessDraft(String commandId, String text) {
        return delegate.sendKeystrokesUnlessDraft(commandId, text);
      }

      @Override
      public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {
        delegate.reportAgentSession(commandId, sessionId, transcriptPath);
      }

      @Override
      public boolean ownsSession(String sessionId) {
        return delegate.ownsSession(sessionId);
      }

      @Override
      public Optional<String> agentTypeForSession(String sessionId) {
        return delegate.agentTypeForSession(sessionId);
      }
    };
  }
}
