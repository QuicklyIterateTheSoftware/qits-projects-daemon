package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The API's gate and its address, over a real socket.
 *
 * <p>Those two are what the tunnel and the proxy depend on and what nothing else would catch: an
 * unauthenticated caller must learn only that a credential is required, and a request addressed at
 * a <em>different</em> project's base must 404 rather than being served — on a host running a
 * container per project, a loose prefix match is cross-project code execution, not a routing bug.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ProjectsApiTest {

  private static final String TOKEN = "s3cret-project-token";
  private static final String BASE = "/projects/container/proj-1";

  @TempDir Path root;

  private Vertx vertx;
  private HttpClient client;
  private ProjectsApi api;
  private int port;

  private static final ProjectContext PROJECT =
      new ProjectContext() {
        @Override
        public String projectId() {
          return "proj-1";
        }

        @Override
        public String repoName() {
          return "qits-qits";
        }

        @Override
        public String branch() {
          return "main";
        }

        @Override
        public String commitHash() {
          return "0123456";
        }
      };

  @BeforeEach
  void startServer() {
    vertx = Vertx.vertx();
    client = vertx.createHttpClient();
    api = new ProjectsApi();
    api.vertx = vertx;
    api.apiBasePath = Optional.of(BASE);
    CommandStore store = new CommandStore();
    CommandRegistry registry = new CommandRegistry(root, 2_000);
    api.wireCommands(
        new CommandService(
            store,
            registry,
            new CommandLifecycleService(store, null),
            new CommandLogService(store, null),
            PROJECT,
            new NoDeclaredActions()),
        registry,
        PROJECT);
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN));
    port = api.actualPort();
  }

  @AfterEach
  void stopServer() {
    api.close();
    if (client != null) {
      client.close();
    }
    if (vertx != null) {
      vertx.close();
    }
  }

  private record Answer(int status, JsonObject body) {}

  private Answer get(String path, String bearer) {
    RequestOptions options =
        new RequestOptions().setHost("127.0.0.1").setPort(port).setURI(path).setMethod(HttpMethod.GET);
    return await(
        client
            .request(options)
            .compose(
                request -> {
                  if (bearer != null) {
                    request.putHeader("Authorization", "Bearer " + bearer);
                  }
                  return request.send();
                })
            .compose(
                response ->
                    response.body().map(body -> new Answer(response.statusCode(), new JsonObject(body)))));
  }

  private static <T> T await(Future<T> future) {
    try {
      return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void noCredentialIsRefusedWithoutSayingWhetherThePathExists() {
    Answer missing = get(BASE + "/commands", null);
    Answer wrong = get(BASE + "/commands", "not-the-token");
    Answer nonsense = get(BASE + "/no-such-thing", null);

    assertEquals(401, missing.status());
    assertEquals(401, wrong.status());
    assertEquals(401, nonsense.status(), "the gate runs before the router, so a 404 leaks nothing");
    assertEquals("Unauthorized", missing.body().getString("message"));
  }

  @Test
  void anAuthenticatedRequestReachesTheCommandsSurface() {
    Answer answer = get(BASE + "/commands", TOKEN);

    assertEquals(200, answer.status());
    assertTrue(answer.body().getJsonArray("entries").isEmpty());
  }

  @Test
  void theDeclaredActionListIsEmptyRatherThanAbsent() {
    // NoDeclaredActions is the seam a real resolver would land in; until then the honest answer is
    // an empty list, not a 500 from a null resolver.
    Answer answer = get(BASE + "/commands/actions", TOKEN);

    assertEquals(200, answer.status());
    assertTrue(answer.body().getJsonArray("actions").isEmpty());
  }

  @Test
  void theAgentSurfaceAnswers503UntilItIsWired() {
    // Retryable, not 404: the routes exist and the harness wiring lands after provisioning.
    Answer answer = get(BASE + "/agents/available", TOKEN);

    assertEquals(503, answer.status());
  }

  @Test
  void aRequestAddressedAtAnotherProjectsBaseIs404() {
    // A plain startsWith would route /projects/container/proj-12/commands into proj-1's daemon.
    assertEquals(404, get("/projects/container/proj-12/commands", TOKEN).status());
    assertEquals(404, get("/commands", TOKEN).status(), "the base is not optional once configured");
  }

  @Test
  void theBaseIsStrippedRatherThanServedAsPartOfThePath() {
    // The proxy forwards the caller's path untouched, so the daemon has to account for its own
    // mount point — and the routes below it stay written as the paths they are.
    assertEquals(404, get(BASE + BASE + "/commands", TOKEN).status());
    assertEquals(200, get(BASE + "/commands", TOKEN).status());
  }
}
