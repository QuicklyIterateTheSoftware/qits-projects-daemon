package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
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
    api.wireAgents(
        new eu.wohlben.qits.agents.AgentLaunchService(
            null, null, null, null, defaults, null, project, null, 0),
        null,
        defaults,
        capabilities,
        imageVersion,
        reportedBy);
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

  /**
   * What {@link ControlSocket} mints for {@code audience} from a commissioned client: the exact
   * {@code POST <tokenUrl>} the dial-home and the boot clone make, and the {@code Authorization}
   * value it turns the answer into.
   */
  public static Optional<String> mint(
      String tokenUrl, String clientId, String clientSecret, String audience) throws Exception {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.empty();
    socket.commissionedClientId = Optional.of(clientId);
    socket.commissionedClientSecret = Optional.of(clientSecret);
    socket.authTokenUrl = Optional.of(tokenUrl);
    return socket.authorization(Optional.of(audience)).get(20, TimeUnit.SECONDS);
  }
}
