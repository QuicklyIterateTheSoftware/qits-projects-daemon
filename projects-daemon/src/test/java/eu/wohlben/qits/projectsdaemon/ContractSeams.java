package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.GuardedInput;
import io.vertx.core.Vertx;
import java.nio.file.Path;
import java.util.List;
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

    private RunningApi(Vertx vertx, ProjectsApi api) {
      this.vertx = vertx;
      this.api = api;
    }

    public int port() {
      return api.actualPort();
    }

    @Override
    public void close() {
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
    return start(root, projectId, apiToken, imageVersion, reportedBy, capabilities, true);
  }

  /**
   * The same API bound <b>before</b> its agent surface is wired: what a booting daemon answers
   * between its loopback bind and its harness probe finishing, the window in which every agent route
   * answers 503.
   */
  public static RunningApi startApiBeforeAgents(Path root, String projectId, String apiToken) {
    return start(root, projectId, apiToken, null, null, List.of(), false);
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
        start(root, projectId, apiToken, imageVersion, reportedBy, capabilities, true);
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

  private static RunningApi start(
      Path root,
      String projectId,
      String apiToken,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities,
      boolean wireAgents) {
    Vertx vertx = Vertx.vertx();
    ProjectsApi api = new ProjectsApi();
    api.vertx = vertx;
    api.apiBasePath = Optional.of("/projects/container/" + projectId);
    ProjectContext project =
        new DaemonProjectContext(projectId, "qits-qits", () -> "main", () -> "0123456");
    CommandStore store = new CommandStore();
    CommandRegistry registry = new CommandRegistry(root, 2_000);
    api.wireCommands(
        new CommandService(
            store,
            registry,
            new CommandLifecycleService(store, null),
            new CommandLogService(store, null),
            project,
            new NoDeclaredActions()),
        registry,
        project);
    DaemonAgentDefaults defaults = new DaemonAgentDefaults(Optional.of("CLAUDE"), false);
    if (wireAgents) {
      api.wireAgents(
          new eu.wohlben.qits.agents.AgentLaunchService(
              null, null, null, null, defaults, null, project, null, 0),
          null,
          defaults,
          capabilities,
          imageVersion,
          reportedBy);
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
    return new RunningApi(vertx, api);
  }
}
