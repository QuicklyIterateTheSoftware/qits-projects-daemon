package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
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

  private record Answer(int status, JsonObject body, String retryAfter) {
    Answer(int status, JsonObject body) {
      this(status, body, null);
    }
  }

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

  private Answer post(String path, String bearer, JsonObject body) {
    RequestOptions options =
        new RequestOptions()
            .setHost("127.0.0.1")
            .setPort(port)
            .setURI(path)
            .setMethod(HttpMethod.POST);
    return await(
        client
            .request(options)
            .compose(
                request -> {
                  request.putHeader("Authorization", "Bearer " + bearer);
                  request.putHeader("Content-Type", "application/json");
                  return request.send(body.encode());
                })
            .compose(
                response ->
                    response
                        .body()
                        .map(
                            answer ->
                                new Answer(
                                    response.statusCode(),
                                    new JsonObject(answer),
                                    response.getHeader("Retry-After")))));
  }

  /**
   * Wire an agent surface that can be asked but never launches: every case below is answered
   * before the harness is reached, which is the point — nothing may spawn on the refused paths.
   */
  private void wireAgentsThatNeverLaunch() {
    DaemonAgentDefaults defaults = new DaemonAgentDefaults(Optional.of("CLAUDE"), false);
    api.wireAgents(
        new AgentLaunchService(null, null, null, null, defaults, null, PROJECT, null, 0),
        null,
        defaults);
  }

  private static JsonObject launch(String surface) {
    return new JsonObject().put("scope", "REPOSITORY").put("surface", surface);
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

  /**
   * Per-session mode without a desk-agents credential: a 503 naming the key, and no launch — even
   * with a front-desk credential present, which is the desk's own and never a person's.
   */
  @Test
  void aLaunchWithoutItsCredentialFailsClosed() {
    wireAgentsThatNeverLaunch();
    SessionCredentialStore credentials =
        new SessionCredentialStore(() -> "qits_tok_container", () -> "tok-agent-container");
    credentials.replace(
        new SessionCredentials(
            List.of(
                new SessionCredentials.Credential("entity-9", "qits_tok_refine", "tok-r"),
                new SessionCredentials.Credential("front-desk", "qits_tok_delivery", "tok-dd"))));
    api.wireSessions(credentials);

    Answer answer = post(BASE + "/agents", TOKEN, launch("project.work"));

    assertEquals(503, answer.status());
    String message = answer.body().getString("message");
    assertTrue(message.contains("'desk-agents'"), message);
    assertFalse(message.contains("qits_tok"), "never a token");
  }

  /** Inside the grace window a launch is "not yet": 503 with a Retry-After, and nothing spawned. */
  @Test
  void aLaunchInsideTheGraceWindowIsToldToRetry() {
    wireAgentsThatNeverLaunch();
    SessionCredentialStore credentials =
        new SessionCredentialStore(() -> "qits_tok_container", () -> "tok-agent-container");
    credentials.openWindow(java.time.Duration.ofMinutes(5));
    api.wireSessions(credentials);

    Answer answer = post(BASE + "/agents", TOKEN, launch("project.work"));

    assertEquals(503, answer.status());
    assertEquals(ProjectsApi.WAITING_FOR_SESSION_CREDENTIALS, answer.body().getString("message"));
    assertEquals(
        String.valueOf(ProjectsApi.SESSION_CREDENTIALS_RETRY_AFTER_SECONDS), answer.retryAfter());
  }

  /**
   * A person's launch runs on the desk-agents credential whatever surface it names — never the
   * front desk's, which may carry the delivery rights; before qits sent any, on the container's own
   * token, explicitly — it is no longer inherited — and never on the delivery token.
   */
  @Test
  void aPersonsLaunchRunsOnTheDeskAgentsCredential() {
    SessionCredentialStore credentials =
        new SessionCredentialStore(() -> "qits_tok_container", () -> "tok-agent-container");
    api.wireSessions(credentials);

    Map<String, String> legacy =
        api.personLaunch(AgentJson.launchRequest(launch("project.work"))).environment();
    assertEquals("qits_tok_container", legacy.get("QITS_TOKEN"));
    assertEquals("tok-agent-container", legacy.get("QITS_TOKEN_SUBJECT"));
    assertEquals("", legacy.get("QITS_PROJECTS_DAEMON_API_TOKEN"), "the API token is removed");
    assertEquals("", legacy.get("QITS_DELIVERY_TOKEN"));

    credentials.replace(
        new SessionCredentials(
            List.of(
                new SessionCredentials.Credential("front-desk", "qits_tok_delivery", "tok-dd"),
                new SessionCredentials.Credential("desk-agents", "qits_tok_desk", "tok-desk"))));
    for (String surface : List.of("project.work", "project.delivery")) {
      Map<String, String> overlay =
          api.personLaunch(AgentJson.launchRequest(launch(surface))).environment();
      assertEquals("qits_tok_desk", overlay.get("QITS_TOKEN"), surface);
      assertEquals("tok-desk", overlay.get("QITS_TOKEN_SUBJECT"), surface);
      assertEquals("", overlay.get("QITS_PROJECTS_DAEMON_API_TOKEN"), surface);
      assertEquals("", overlay.get("QITS_DELIVERY_TOKEN"), surface);
    }
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

  // --- the front desk (qits-1572) ---------------------------------------------------------------

  /** The kept command, its state and what was typed, behind the inbox the routes are wired to. */
  private Optional<String> kept = Optional.of("cmd-desk");

  private final Map<String, String> deskStates = new java.util.HashMap<>();
  private final List<String> typed = new java.util.ArrayList<>();

  private void wireFrontDesk() {
    DeliveryKeeper.Session session =
        new DeliveryKeeper.Session() {
          @Override
          public boolean running(String commandId) {
            return "cmd-desk".equals(commandId);
          }

          @Override
          public Optional<String> activityState(String commandId) {
            return Optional.ofNullable(deskStates.get(commandId));
          }

          @Override
          public eu.wohlben.qits.commands.GuardedInput send(String commandId, String text) {
            typed.add(text);
            return eu.wohlben.qits.commands.GuardedInput.WRITTEN;
          }
        };
    api.wireFrontDesk(
        new FrontDeskInbox(
            () -> kept,
            session,
            commandId ->
                List.of(
                    "{\"type\":\"user\",\"sessionId\":\"sess-9\",\"message\":{\"content\":\"hi\"}}",
                    "{\"type\":\"assistant\",\"sessionId\":\"sess-9\","
                        + "\"timestamp\":\"2026-10-24T10:00:00Z\","
                        + "\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}"),
            new SessionKeeper.Scheduler() {
              @Override
              public void schedule(Runnable step, long delayMs) {
                step.run();
              }

              @Override
              public void shutdown() {}
            },
            System::currentTimeMillis));
  }

  @Test
  void theFrontDeskAnswers503UntilItIsWired() {
    assertEquals(503, get(BASE + "/front-desk/last-answer", TOKEN).status());
    assertEquals(401, get(BASE + "/front-desk/last-answer", null).status(), "and is gated");
  }

  @Test
  void aPromptIsDeliveredToAnIdleDeskAndQueuedBehindABusyOne() {
    wireFrontDesk();
    deskStates.put("cmd-desk", "IDLE");

    Answer delivered = post(BASE + "/front-desk/prompt", TOKEN, new JsonObject().put("text", "go"));
    assertEquals(200, delivered.status());
    assertEquals(
        new JsonObject()
            .put("commandId", "cmd-desk")
            .put("state", "IDLE")
            .put("delivered", true)
            .put("queued", false)
            .putNull("position"),
        delivered.body());
    assertEquals(List.of("go"), typed);

    deskStates.put("cmd-desk", "BUSY");
    Answer queued = post(BASE + "/front-desk/prompt", TOKEN, new JsonObject().put("text", "next"));
    assertEquals(200, queued.status());
    assertEquals(
        new JsonObject()
            .put("commandId", "cmd-desk")
            .put("state", "BUSY")
            .put("delivered", false)
            .put("queued", true)
            .put("position", 1),
        queued.body());
  }

  @Test
  void aPromptIsRefusedWithTheContractsStatusCodes() {
    wireFrontDesk();
    deskStates.put("cmd-desk", "BUSY");

    Answer missing = post(BASE + "/front-desk/prompt", TOKEN, new JsonObject());
    assertEquals(400, missing.status());
    assertTrue(missing.body().containsKey("reason"));
    assertEquals(
        400, post(BASE + "/front-desk/prompt", TOKEN, new JsonObject().put("text", " ")).status());

    for (int i = 0; i < FrontDeskInbox.CAPACITY; i++) {
      assertEquals(
          200,
          post(BASE + "/front-desk/prompt", TOKEN, new JsonObject().put("text", "t" + i)).status());
    }
    Answer full = post(BASE + "/front-desk/prompt", TOKEN, new JsonObject().put("text", "more"));
    assertEquals(429, full.status());
    assertTrue(full.body().containsKey("reason"));

    kept = Optional.empty();
    Answer noDesk = post(BASE + "/front-desk/prompt", TOKEN, new JsonObject().put("text", "hi"));
    assertEquals(409, noDesk.status());
    assertTrue(noDesk.body().containsKey("reason"));
  }

  @Test
  void theLastAnswerIsServedAnd404sWithoutAKeptDesk() {
    wireFrontDesk();
    deskStates.put("cmd-desk", "IDLE");

    Answer answer = get(BASE + "/front-desk/last-answer", TOKEN);
    assertEquals(200, answer.status());
    assertEquals(
        new JsonObject()
            .put("commandId", "cmd-desk")
            .put("sessionId", "sess-9")
            .put("state", "IDLE")
            .put("text", "hello")
            .put("at", "2026-10-24T10:00:00Z"),
        answer.body());

    kept = Optional.empty();
    Answer none = get(BASE + "/front-desk/last-answer", TOKEN);
    assertEquals(404, none.status());
    assertTrue(none.body().containsKey("reason"));
  }
}
