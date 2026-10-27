package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.ProcessRunner;
import eu.wohlben.qits.commands.ActionResolver;
import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.GuardedInput;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the daemon's own secrets out of every child it spawns, and decides which credential each
 * child runs on (qits-1495, qits-1482).
 *
 * <p>The container is created with five secret names in its environment, and every child inherits
 * the daemon's environment by default:
 *
 * <ul>
 *   <li>{@code QITS_DELIVERY_TOKEN} / {@code _SUBJECT} — the desk's delivery token ({@code
 *       qits:desk}), set by a qits that sends no {@code sessionCredentials}; under its own name it
 *       reaches no child, and the keeper's front desk session alone gets it as its {@code
 *       QITS_TOKEN} in legacy mode ({@link FrontDeskCredential}).
 *   <li>{@code QITS_PROJECTS_DAEMON_API_TOKEN} — the loopback API's bearer. Only {@link ProjectsApi}
 *       reads it, through configuration, and <b>no child needs it</b>. A child that held it could
 *       attach to another session's terminal and type into it, which is a confused deputy no token
 *       isolation stops — so it is removed from every child, without exception.
 *   <li>{@code QITS_TOKEN} / {@code _SUBJECT} — the container's own {@code agent-container} token.
 *       The daemon's own processes need it; a session gets its {@code QITS_TOKEN} from its launch
 *       overlay only, never by inheritance ({@link SessionCredentialStore}).
 * </ul>
 *
 * <p>Which spawn keeps what:
 *
 * <ul>
 *   <li><b>agent launches</b> — {@link FrontDeskKeeper}'s desk and every launch through {@code POST
 *       /agents} — carry {@link #SCRUB} (all five names mapped to {@code ""}, which the library
 *       turns into an {@code unset}) and then <em>their own</em> credential on top ({@link
 *       #launchedAs}): the front desk's from {@link FrontDeskCredential}, a person's from the {@code
 *       desk-agents} key in {@link SessionCredentialStore}. Nothing is inherited.
 *   <li><b>{@code RunCommand}</b> ({@link CommandExecutor}) — a process qits asks for, not one the
 *       daemon needs for itself — loses all five ({@link #scrubSession(Map)}).
 *   <li><b>declared actions</b> ({@code POST /commands}) are spawned by the library's {@code
 *       CommandService}, which offers no environment or process seam of its own. The one seam it
 *       does take is the {@link ActionResolver}, whose actions carry the environment overlay their
 *       process is spawned with, so {@link #actions} lays all five over every action, blank. Blank
 *       and not absent: the library's registry layers that overlay over the daemon's environment
 *       with a plain {@code putAll} and has no removal. An empty value carries no credential.
 *   <li><b>the daemon's own git and maintenance processes</b> — {@link CheckoutFollower} ({@code qits
 *       checkout-daemon}), {@link Provisioner}'s clone and submodule fetches, {@link
 *       ProjectDescriber}'s git reads — lose the delivery credential and the API token but
 *       <b>keep</b> {@code QITS_TOKEN} ({@link #scrubDaemon(Map)}). They act as the container, and
 *       they need to: the base image's git credential helper ({@code /usr/local/bin/qits-git-credential},
 *       named by {@code /etc/qits-gitconfig}) answers qits-githost with {@code QITS_TOKEN} when it is
 *       set — this daemon's only dial-home credential, with no commissioned pair to fall back on
 *       (qits-876) — and the {@code qits} CLI the follower runs picks its edge-token mode off the
 *       same name.
 *   <li><b>what the library spawns without a request</b> — the sign-in terminal, the auth and
 *       capability probes, the token preflight — goes through {@link #commands} and {@link
 *       #processes}, which blank all five on the way in unless the launch already decided them.
 *       Blank rather than absent because those seams take an environment to lay over the inherited
 *       one and cannot express a removal; an empty value carries no credential.
 * </ul>
 */
final class DaemonSecrets {

  /** The delivery token's variable; also the source of {@code qits.projects-daemon.delivery-token}. */
  static final String DELIVERY_TOKEN = "QITS_DELIVERY_TOKEN";

  /** Whom that token names. */
  static final String DELIVERY_TOKEN_SUBJECT = "QITS_DELIVERY_TOKEN_SUBJECT";

  /** The loopback API's bearer; the source of {@code qits.projects-daemon.api-token}. */
  static final String API_TOKEN = "QITS_PROJECTS_DAEMON_API_TOKEN";

  /** The container's own token, and the name a session's own credential is handed under. */
  static final String TOKEN = "QITS_TOKEN";

  /** Whom {@link #TOKEN} names. */
  static final String TOKEN_SUBJECT = "QITS_TOKEN_SUBJECT";

  /** What no session-ish child may inherit: all five, in the order a log or a test lists them. */
  static final List<String> NAMES =
      List.of(DELIVERY_TOKEN, DELIVERY_TOKEN_SUBJECT, API_TOKEN, TOKEN, TOKEN_SUBJECT);

  /** What the daemon's own git and maintenance processes lose: everything but its own token. */
  static final List<String> DAEMON_NAMES = List.of(DELIVERY_TOKEN, DELIVERY_TOKEN_SUBJECT, API_TOKEN);

  /** The launch overlay that removes all five names from a child: an empty value is a removal. */
  static final Map<String, String> SCRUB = blankOf(NAMES);

  private DaemonSecrets() {}

  /**
   * {@code request} with {@link #SCRUB} laid over whatever overlay it already carries. The scrub
   * wins: no request may name a secret for itself. A launch that is to run as somebody goes through
   * {@link #launchedAs} instead.
   */
  static AgentLaunchRequest scrubbed(AgentLaunchRequest request) {
    return launchedAs(request, Map.of());
  }

  /**
   * {@code request} with {@link #SCRUB} laid over its overlay and {@code credential} — its {@code
   * QITS_TOKEN}/{@code QITS_TOKEN_SUBJECT} — laid over that. The credential is the only thing a
   * launch may put back.
   */
  static AgentLaunchRequest launchedAs(AgentLaunchRequest request, Map<String, String> credential) {
    return request.withEnvironment(overlay(request.environment(), credential));
  }

  /** {@code base}, then {@link #SCRUB}, then {@code credential}: the overlay a launch carries. */
  static Map<String, String> overlay(Map<String, String> base, Map<String, String> credential) {
    Map<String, String> overlay = base == null ? new HashMap<>() : new HashMap<>(base);
    overlay.putAll(SCRUB);
    if (credential != null) {
      overlay.putAll(credential);
    }
    return overlay;
  }

  /** {@code QITS_TOKEN}/{@code QITS_TOKEN_SUBJECT} as a launch overlay; null reads as blank. */
  static Map<String, String> credential(String token, String subject) {
    return Map.of(TOKEN, token == null ? "" : token, TOKEN_SUBJECT, subject == null ? "" : subject);
  }

  /** Removes all five names from a session-ish {@link ProcessBuilder#environment()}. */
  static void scrubSession(Map<String, String> environment) {
    NAMES.forEach(environment::remove);
  }

  /**
   * Removes {@link #DAEMON_NAMES} from the environment of a process the daemon runs as itself,
   * keeping {@code QITS_TOKEN}: the git helper and the {@code qits} CLI authenticate with it.
   */
  static void scrubDaemon(Map<String, String> environment) {
    DAEMON_NAMES.forEach(environment::remove);
  }

  /** {@code environment} with all five names set blank unless it already decided them. */
  static Map<String, String> blanked(Map<String, String> environment) {
    Map<String, String> out = environment == null ? new HashMap<>() : new HashMap<>(environment);
    NAMES.forEach(name -> out.putIfAbsent(name, ""));
    return out;
  }

  /** A {@link ProcessRunner} whose every child sees all five names blank. */
  static ProcessRunner processes(ProcessRunner delegate) {
    return (command, cwd, env, timeout) -> delegate.exec(command, cwd, blanked(env), timeout);
  }

  /**
   * An {@link ActionResolver} whose every action's environment has all five names blank, the scrub
   * winning over anything the action declared — a declared action is the checkout's, and a
   * checkout is untrusted.
   */
  static ActionResolver actions(ActionResolver delegate) {
    return new ActionResolver() {
      @Override
      public Optional<ResolvedAction> resolve(String actionId) {
        return delegate.resolve(actionId).map(DaemonSecrets::scrubbedAction);
      }

      @Override
      public List<ResolvedAction> actions() {
        return delegate.actions().stream().map(DaemonSecrets::scrubbedAction).toList();
      }
    };
  }

  private static ActionResolver.ResolvedAction scrubbedAction(ActionResolver.ResolvedAction action) {
    return new ActionResolver.ResolvedAction(
        action.id(),
        action.name(),
        action.executeScript(),
        action.interactive(),
        overlay(action.environment(), Map.of()));
  }

  private static Map<String, String> blankOf(List<String> names) {
    Map<String, String> out = new LinkedHashMap<>();
    names.forEach(name -> out.put(name, ""));
    return Map.copyOf(out);
  }

  /**
   * An {@link AgentCommands} whose every spawned session sees all five names blank unless the launch
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
