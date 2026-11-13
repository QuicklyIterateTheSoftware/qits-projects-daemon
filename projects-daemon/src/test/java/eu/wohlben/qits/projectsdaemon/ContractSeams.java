package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentSessionQueryService;
import eu.wohlben.qits.agents.AgentSessionStore;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.commands.ActionResolver;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogLine;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.GuardedInput;
import io.vertx.core.Vertx;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * <b>The two seams the contract tests need, opened to them</b> (ticket qits-1149). {@link
 * ProjectsApi} and {@link ControlSocket} keep their wiring package-private; the provider tests
 * ({@code contracts/}) and the consumer tests ({@code consumer/}) live in their own packages, so
 * this class is the one place that reaches in for them.
 */
public final class ContractSeams {

  private ContractSeams() {}

  /** A running daemon API: its loopback port, closed with the test. */
  public static final class RunningApi implements AutoCloseable {

    private final Vertx vertx;
    private final ProjectsApi api;
    private final CommandRegistry registry;

    private RunningApi(Vertx vertx, ProjectsApi api, CommandRegistry registry) {
      this.vertx = vertx;
      this.api = api;
      this.registry = registry;
    }

    public int port() {
      return api.actualPort();
    }

    @Override
    public void close() {
      // A state that launches a command leaves its process in the registry; end it with the state.
      registry.terminateAll();
      api.close();
      vertx.close();
    }
  }

  /**
   * The daemon API on an ephemeral loopback port, mounted at {@code /projects/container/<projectId>}
   * the way qits-projects mounts it, with the agent surface wired and {@code capabilities} as its
   * boot-time harness report. No harness runs: {@code GET /agents/available} reads only the defaults
   * and the report.
   */
  public static RunningApi startApi(
      Path root,
      String projectId,
      String apiToken,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities) {
    return start(
        root, projectId, apiToken, imageVersion, reportedBy, capabilities, true, new Wiring());
  }

  /**
   * The same API bound <b>before</b> its agent surface is wired: what a booting daemon answers
   * between its loopback bind and its harness probe finishing, the window in which every agent route
   * answers 503.
   */
  public static RunningApi startApiBeforeAgents(Path root, String projectId, String apiToken) {
    return start(root, projectId, apiToken, null, null, List.of(), false, new Wiring());
  }

  /**
   * The API with its agent surface wired (as {@link #startApi}) and a front desk inbox whose kept
   * command {@code keptCommandId} is RUNNING and last reported {@code IDLE}, so {@code POST
   * /front-desk/prompt} is typed at once (and swallowed: no PTY runs), and whose TRANSCRIPT channel
   * holds {@code transcript} for {@code GET /front-desk/last-answer} to read.
   */
  public static RunningApi startApiWithFrontDesk(
      Path root,
      String projectId,
      String apiToken,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities,
      String keptCommandId,
      List<String> transcript) {
    RunningApi running =
        start(
            root, projectId, apiToken, imageVersion, reportedBy, capabilities, true, new Wiring());
    DeliveryKeeper.Session session =
        new DeliveryKeeper.Session() {
          @Override
          public boolean running(String commandId) {
            return keptCommandId.equals(commandId);
          }

          @Override
          public Optional<String> activityState(String commandId) {
            return keptCommandId.equals(commandId) ? Optional.of("IDLE") : Optional.empty();
          }

          @Override
          public GuardedInput send(String commandId, String text) {
            return keptCommandId.equals(commandId)
                ? GuardedInput.WRITTEN
                : GuardedInput.NOT_RUNNING;
          }
        };
    SessionKeeper.Scheduler never =
        new SessionKeeper.Scheduler() {
          @Override
          public void schedule(Runnable step, long delayMs) {}

          @Override
          public void shutdown() {}
        };
    running.api.wireFrontDesk(
        new FrontDeskInbox(
            () -> Optional.of(keptCommandId),
            session,
            commandId -> keptCommandId.equals(commandId) ? transcript : List.of(),
            never,
            () -> 0L));
    return running;
  }

  /**
   * The API with its agent surface wired (as {@link #startApi}) and one named agent (qits-2154):
   * {@code name}, whose key {@code agent:<name>} the current frame names on {@code surface}, RUNNING
   * as {@code commandId} in harness session {@code sessionId} and last reported {@code IDLE} — so
   * {@code POST /named-agents/<name>/prompt} is typed at once (and swallowed: no PTY runs), {@code GET
   * /named-agents/<name>/last-answer} reads {@code transcript}, and {@code GET /named-agents} lists
   * it.
   */
  public static RunningApi startApiWithNamedAgent(
      Path root,
      String projectId,
      String apiToken,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities,
      String name,
      String surface,
      String commandId,
      String sessionId,
      List<String> transcript) {
    RunningApi running =
        start(
            root, projectId, apiToken, imageVersion, reportedBy, capabilities, true, new Wiring());
    DeliveryKeeper.Session session =
        new DeliveryKeeper.Session() {
          @Override
          public boolean running(String id) {
            return commandId.equals(id);
          }

          @Override
          public Optional<String> activityState(String id) {
            return commandId.equals(id) ? Optional.of("IDLE") : Optional.empty();
          }

          @Override
          public GuardedInput send(String id, String text) {
            return commandId.equals(id) ? GuardedInput.WRITTEN : GuardedInput.NOT_RUNNING;
          }
        };
    SessionKeeper.Scheduler never =
        new SessionKeeper.Scheduler() {
          @Override
          public void schedule(Runnable step, long delayMs) {}

          @Override
          public void shutdown() {}
        };
    List<NamedAgentHost.Agent> roster =
        List.of(
            new NamedAgentHost.Agent(
                name,
                eu.wohlben.qits.projectsdaemon.protocol.NamedAgents.namedAgentKey(name),
                surface,
                "RUNNING",
                commandId,
                sessionId));
    running.api.wireNamedAgents(
        new NamedAgentHost(
            () -> roster,
            session,
            id -> commandId.equals(id) ? transcript : List.of(),
            never,
            () -> 0L));
    return running;
  }

  /**
   * The API with its agent surface wired (as {@link #startApi}) over a command store holding {@code
   * commands} — finished ones, which no registry process backs — whose log buffers hold {@code
   * logs}, and with {@code actions} declared, so {@code POST /commands} of one of them really
   * spawns its script in {@code root}.
   */
  public static RunningApi startApiWithCommands(
      Path root,
      String projectId,
      String apiToken,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities,
      List<Command> commands,
      Map<String, List<CommandLogLine>> logs,
      List<ActionResolver.ResolvedAction> actions) {
    Wiring wiring = new Wiring();
    seed(wiring.store, commands, logs);
    wiring.actions = new DeclaredActions(actions);
    return start(root, projectId, apiToken, imageVersion, reportedBy, capabilities, true, wiring);
  }

  /**
   * The API with its agent surface wired (as {@link #startApi}) and a real {@link
   * AgentSessionQueryService} over a command store holding {@code commands}, whose sweep stats are
   * {@code stats} — so {@code GET /agent-sessions} answers the tree those commands drove.
   */
  public static RunningApi startApiWithAgentSessions(
      Path root,
      String projectId,
      String apiToken,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities,
      List<Command> commands,
      List<AgentSessionStore.Stat> stats) {
    Wiring wiring = new Wiring();
    seed(wiring.store, commands, Map.of());
    AgentSessionStore sessionStore = new AgentSessionStore();
    sessionStore.replace(
        stats.stream().map(AgentSessionStore.Stat::sessionId).distinct().toList(), stats);
    wiring.agentSessions = new AgentSessionQueryService(wiring.store, sessionStore);
    return start(root, projectId, apiToken, imageVersion, reportedBy, capabilities, true, wiring);
  }

  /**
   * The API with its agent surface wired (as {@link #startApi}) inside the grace window for qits'
   * session credentials: {@code POST /agents} answers 503 with a {@code Retry-After} and launches
   * nothing. The window's clock never moves, so it never closes while the state runs.
   */
  public static RunningApi startApiAwaitingSessionCredentials(
      Path root,
      String projectId,
      String apiToken,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities) {
    Wiring wiring = new Wiring();
    SessionCredentialStore credentials = new SessionCredentialStore(() -> "", () -> "", () -> 0L);
    credentials.openWindow(Duration.ofMinutes(5));
    wiring.sessionCredentials = credentials;
    return start(root, projectId, apiToken, imageVersion, reportedBy, capabilities, true, wiring);
  }

  /** What a state wires beyond the defaults: an empty store, no actions, no session tree. */
  private static final class Wiring {
    final CommandStore store = new CommandStore();
    ActionResolver actions = new NoDeclaredActions();
    AgentSessionQueryService agentSessions;
    SessionCredentialStore sessionCredentials;
  }

  private static void seed(
      CommandStore store, List<Command> commands, Map<String, List<CommandLogLine>> logs) {
    for (Command command : commands) {
      store.put(command);
    }
    logs.forEach((commandId, lines) -> lines.forEach(store.log(commandId)::append));
  }

  /** An action resolver declaring exactly {@code actions}. */
  private record DeclaredActions(List<ActionResolver.ResolvedAction> actions)
      implements ActionResolver {

    DeclaredActions {
      actions = List.copyOf(actions);
    }

    @Override
    public Optional<ResolvedAction> resolve(String actionId) {
      return actions.stream().filter(action -> action.id().equals(actionId)).findFirst();
    }
  }

  private static RunningApi start(
      Path root,
      String projectId,
      String apiToken,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities,
      boolean wireAgents,
      Wiring wiring) {
    Vertx vertx = Vertx.vertx();
    ProjectsApi api = new ProjectsApi();
    api.vertx = vertx;
    api.apiBasePath = Optional.of("/projects/container/" + projectId);
    ProjectContext project =
        new DaemonProjectContext(projectId, "qits-qits", () -> "main", () -> "0123456");
    CommandStore store = wiring.store;
    CommandRegistry registry = new CommandRegistry(root, 2_000);
    api.wireCommands(
        new CommandService(
            store,
            registry,
            new CommandLifecycleService(store, null),
            new CommandLogService(store, null),
            project,
            wiring.actions),
        registry,
        project);
    DaemonAgentDefaults defaults = new DaemonAgentDefaults(Optional.of("CLAUDE"), false);
    if (wireAgents) {
      api.wireAgents(
          new eu.wohlben.qits.agents.AgentLaunchService(
              null, null, null, null, defaults, null, project, null, 0),
          wiring.agentSessions,
          defaults,
          capabilities,
          imageVersion,
          reportedBy);
    }
    if (wiring.sessionCredentials != null) {
      api.wireSessions(wiring.sessionCredentials);
    }
    try {
      api.listen(vertx, "127.0.0.1", 0, apiToken)
          .toCompletionStage()
          .toCompletableFuture()
          .get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      api.close();
      vertx.close();
      throw new IllegalStateException("The daemon API did not bind", e);
    }
    return new RunningApi(vertx, api, registry);
  }
}
