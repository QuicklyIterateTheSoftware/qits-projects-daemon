package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdaemon.commands.ActionResolver;
import eu.wohlben.qits.projectsdaemon.commands.CommandLifecycleService;
import eu.wohlben.qits.projectsdaemon.commands.CommandLogService;
import eu.wohlben.qits.projectsdaemon.commands.CommandRegistry;
import eu.wohlben.qits.projectsdaemon.commands.CommandService;
import eu.wohlben.qits.projectsdaemon.commands.CommandStore;
import eu.wohlben.qits.projectsdaemon.commands.ProjectContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the container answers once the self-provision has failed.
 *
 * <p>qits does not remove such a container — it records the failure, reports it on the
 * agent-container read, and leaves the container running. So the daemon binds anyway: an unbound
 * API would turn that recorded failure into a silent 502 from every call through the tunnel, and
 * the one surface that could show the fault would be the one that never answers.
 *
 * <p>Binding is only half of it. The routes have to degrade honestly too — empty where the answer
 * is empty, and 503 with the reason where something would have to run in a checkout that is not
 * there.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class FailedProvisionSurfaceTest {

  private static final String TOKEN = "s3cret-project-token";
  private static final String PROJECT_ID = "proj-7";
  private static final String REPO = "qits-qits";

  @TempDir Path tempDir;

  private Vertx vertx;
  private HttpClient client;
  private ProjectsApi api;
  private int port;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    client = vertx.createHttpClient();
  }

  @AfterEach
  void tearDown() {
    if (api != null) {
      api.close();
    }
    if (client != null) {
      client.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  @Test
  void theApiBindsAndServesWhenTheProvisionFailed() {
    wireAsIfTheProvisionFailed();

    Answer commands = get("/commands");
    Answer actions = get("/commands/actions");
    Answer agents = get("/agents/available");

    assertEquals(200, commands.status(), "the surface must answer, or the failure is a silent 502");
    assertTrue(commands.body().getJsonArray("entries").isEmpty(), "no command has run here");
    assertEquals(200, actions.status());
    assertTrue(actions.body().getJsonArray("actions").isEmpty());
    assertEquals(200, agents.status(), "the harness list needs no checkout");
    assertNotNull(agents.body().getString("defaultAgent"), "and it names one");
  }

  @Test
  void aLaunchWithNoCheckoutIs503WithTheReason() {
    // The registry's root is a directory that was never created — exactly what a container whose
    // clone failed has. Without the guard the spawn reaches ProcessBuilder with a missing working
    // directory and the API can only answer "Internal error".
    wireDirectly(tempDir.resolve("never-cloned"));

    Answer answer = post("/commands", new JsonObject().put("actionId", "build"));

    assertEquals(503, answer.status(), "retryable, and not the caller's fault");
    assertTrue(
        answer.body().getString("message").contains("never-cloned"),
        "the reason is returned rather than hidden behind Internal error");
    assertTrue(
        get("/commands").body().getJsonArray("entries").isEmpty(),
        "refused before the record, so nothing is left stuck in RUNNING");
  }

  @Test
  void aLaunchInAnEmptyCheckoutIsNotRefused() {
    // An empty directory is a checkout that is merely empty (a clone that landed nothing), not an
    // absent one. The guard is about the directory, so this path stays open.
    wireDirectly(tempDir);

    Answer answer = post("/commands", new JsonObject().put("actionId", "no-such-action"));

    assertEquals(400, answer.status(), "an unknown action, the normal answer");
  }

  /** Wire the whole surface the way {@link ControlSocket} does after a provision that failed. */
  private void wireAsIfTheProvisionFailed() {
    api = new ProjectsApi();
    api.vertx = vertx;
    api.apiBasePath = Optional.empty();
    api.apiBindAddress = "127.0.0.1";
    api.apiPort = 0;
    api.apiTokenConfig = Optional.of(TOKEN);

    ControlSocket daemon = new ControlSocket();
    daemon.vertx = vertx;
    daemon.projectsApi = api;
    daemon.projectId = PROJECT_ID;
    daemon.repoName = REPO;
    daemon.url = Optional.of("ws://qits-projects:8080/projects/daemon/" + PROJECT_ID);
    daemon.repositoryMcpUrl = Optional.empty();
    daemon.agentDefaultType = Optional.empty();
    daemon.agentActivityTrackingEnabled = false;
    daemon.transcriptTailPollMs = 500;
    daemon.claudeMount = tempDir.toString();
    daemon.termGraceMs = 2_000;

    // No provision ran, so `provisioned` stays false — the state a ProvisionFailed leaves behind.
    daemon.wireCapabilities();
    port = awaitBind();
  }

  /** Wire only the commands surface, over a chosen checkout root. */
  private void wireDirectly(Path workspaceRoot) {
    api = new ProjectsApi();
    api.vertx = vertx;
    CommandStore store = new CommandStore();
    CommandRegistry registry = new CommandRegistry(workspaceRoot, 2_000);
    api.wireCommands(
        new CommandService(
            store,
            registry,
            new CommandLifecycleService(store, null),
            new CommandLogService(store, null),
            PROJECT,
            new DeclaredActions(
                List.of(
                    new ActionResolver.ResolvedAction(
                        "build", "Build", "true", false, Map.of())))),
        registry,
        PROJECT);
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN));
    port = api.actualPort();
  }

  /** {@code ProjectsApi.start()} binds without handing back its future; wait for the port. */
  private int awaitBind() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      int bound = api.actualPort();
      if (bound != 0) {
        return bound;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalStateException("the API never bound");
  }

  private static final ProjectContext PROJECT =
      new ProjectContext() {
        @Override
        public String projectId() {
          return PROJECT_ID;
        }

        @Override
        public String repoName() {
          return REPO;
        }

        @Override
        public String branch() {
          return "";
        }

        @Override
        public String commitHash() {
          return "";
        }
      };

  private record DeclaredActions(List<ResolvedAction> declared) implements ActionResolver {
    @Override
    public Optional<ResolvedAction> resolve(String actionId) {
      return declared.stream().filter(action -> action.id().equals(actionId)).findFirst();
    }

    @Override
    public List<ResolvedAction> actions() {
      return declared;
    }
  }

  private record Answer(int status, JsonObject body) {}

  private Answer get(String path) {
    return call(HttpMethod.GET, path, null);
  }

  private Answer post(String path, JsonObject body) {
    return call(HttpMethod.POST, path, body);
  }

  private Answer call(HttpMethod method, String path, JsonObject body) {
    RequestOptions options =
        new RequestOptions().setHost("127.0.0.1").setPort(port).setURI(path).setMethod(method);
    return await(
        client
            .request(options)
            .compose(
                request -> {
                  request.putHeader("Authorization", "Bearer " + TOKEN);
                  return body == null ? request.send() : request.send(body.encode());
                })
            .compose(
                response ->
                    response
                        .body()
                        .map(raw -> new Answer(response.statusCode(), new JsonObject(raw)))));
  }

  private static <T> T await(Future<T> future) {
    try {
      return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
