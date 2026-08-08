package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.agents.AgentDefaults;
import eu.wohlben.qits.projectsdaemon.agents.AgentLaunchMode;
import eu.wohlben.qits.projectsdaemon.agents.AgentLaunchRequest;
import eu.wohlben.qits.projectsdaemon.agents.AgentLaunchService;
import eu.wohlben.qits.projectsdaemon.agents.AgentMcpScope;
import eu.wohlben.qits.projectsdaemon.agents.AgentSessionQueryService;
import eu.wohlben.qits.projectsdaemon.agents.AgentType;
import eu.wohlben.qits.projectsdaemon.commands.CommandNotFoundException;
import eu.wohlben.qits.projectsdaemon.commands.CommandRegistry;
import eu.wohlben.qits.projectsdaemon.commands.CommandService;
import eu.wohlben.qits.projectsdaemon.commands.CommandStatus;
import eu.wohlben.qits.projectsdaemon.commands.InvalidCommandRequestException;
import eu.wohlben.qits.projectsdaemon.commands.LogChannel;
import eu.wohlben.qits.projectsdaemon.commands.LogSeverity;
import eu.wohlben.qits.projectsdaemon.commands.ProjectContext;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The daemon's API over the container it owns: the commands surface (list, launch, log, terminate)
 * and the coding-agent surface (launch, available harnesses, session lineage), plus the two
 * interactive websockets {@link CommandSockets} serves.
 *
 * <pre>
 *   GET  /commands[?status=]           the command list
 *   POST /commands                     launch a declared action
 *   GET  /commands/actions             what this container declares
 *   GET  /commands/{id}                one command
 *   GET  /commands/{id}/log            its captured output
 *   POST /commands/{id}/terminate      end it
 *   GET  /agents/available             the harnesses, and the default
 *   POST /agents                       launch a coding agent
 *   GET  /agent-sessions               the session lineage tree
 *   WS   /terminal/commands/{id}       the interactive terminal
 *   WS   /chat/commands/{id}           the chat transport
 * </pre>
 *
 * <p>A raw {@code vertx-core} {@link HttpServer}, exactly like {@link HookWebhook} and for the same
 * reason: the module carries {@code quarkus-vertx} only — no {@code quarkus-rest}, no {@code
 * quarkus-vertx-http}, no JAX-RS — so the native image stays lean and needs nothing registered.
 * Bodies are hand-built {@code JsonObject}s ({@link ProjectsJson}), because there is no Jackson
 * here either. No handler is allowed to throw into the event loop, so the dispatch is wrapped
 * whole.
 *
 * <h2>Why this is loopback-bound</h2>
 *
 * <p>This server binds {@code 127.0.0.1} and has no address on the shared docker network at all.
 * Its client is {@link DaemonStreamTunnel}, which dials <em>out</em> when qits asks for a stream
 * over the control socket and pipes that connection here. A peer agent container's connection is
 * refused by the network stack rather than by a token check — a boundary the topology has rather
 * than one a comment claims.
 *
 * <h2>Security</h2>
 *
 * <p>The threat model that shaped this surface: it drives processes over an <em>untrusted</em>
 * cloned repository, so an unauthenticated port would let whoever could reach it run code in this
 * container.
 *
 * <p><b>The bearer stays, and it is not the boundary.</b> Loopback is what makes this unreachable
 * from off-container; the token is defence in depth behind it, and it costs nothing to keep. This
 * is peer authentication (qits is calling), never user authentication: the daemon has no idea who
 * the user is and never will.
 *
 * <p>So the API requires a shared secret, {@code qits.projects-daemon.api-token} (injected as
 * {@code QITS_PROJECTS_DAEMON_API_TOKEN}), presented as {@code Authorization: Bearer <token>}.
 * Compared with {@link MessageDigest#isEqual} so a mismatch costs the same time whatever the
 * prefix, and never logged or echoed.
 *
 * <p><b>Absent token ⇒ the server does not bind at all</b>, with a warning. Fail-closed rather than
 * fail-open is the deliberate choice: an omitted env is indistinguishable from a misconfiguration,
 * and the failure modes are not symmetric — refusing to bind costs qits a connection error, while
 * serving anonymously would publish a shell in the container with nothing in the logs to say so.
 * The daemon itself stays alive either way; nothing here may take the container down.
 *
 * <p>Everything past the token check carries no cookies, no CORS headers and no {@code
 * Access-Control-Allow-*} — a browser is not a client of this port, and the bearer scheme is not
 * ambient authority, so there is no CSRF surface to open. {@code X-Content-Type-Options: nosniff}
 * is set because the bodies embed repository-controlled text.
 */
@ApplicationScoped
public class ProjectsApi {

  private static final Logger LOG = Logger.getLogger(ProjectsApi.class);

  /**
   * The commands surface. It carries no {@code {projectId}} prefix: the daemon serves exactly one
   * project, so the segment would be a constant the caller has to get right. {@link CommandJson}
   * puts the identity back into the response bodies.
   */
  static final String COMMANDS_PATH = "/commands";

  /** {@code GET /commands/actions} — what this container declares. */
  static final String COMMAND_ACTIONS_PATH = "/commands/actions";

  /** The coding-agent surface. Prefix-free like {@link #COMMANDS_PATH}, for the same reason. */
  static final String AGENTS_PATH = "/agents";

  static final String AGENTS_AVAILABLE_PATH = "/agents/available";

  static final String AGENT_SESSIONS_PATH = "/agent-sessions";

  private static final String BEARER = "Bearer ";

  @Inject Vertx vertx;

  // The port qits reaches this daemon's API on, through the reverse tunnel. Distinct from
  // hooks-port: they are different surfaces with different callers, and collapsing them onto one
  // listener would put the unauthenticated hook endpoint behind the tunnel too.
  @ConfigProperty(name = "qits.projects-daemon.api-port", defaultValue = "13338")
  int apiPort;

  // Loopback: the only client that reaches this server shares the container's network namespace.
  // Configurable, but there is no deployment shape that wants it wider — see the class javadoc.
  @ConfigProperty(name = "qits.projects-daemon.api-bind-address", defaultValue = "127.0.0.1")
  String apiBindAddress;

  /**
   * The public base this API is addressed at, injected by qits-projects as {@code
   * /projects/container/{projectId}}. Empty when nothing fronts the daemon, which is what every
   * direct caller (a test, a loopback probe) gets.
   *
   * <p><b>Told, never derived.</b> The proxy in front of this daemon forwards the caller's path
   * untouched — deliberately, because a hop that rewrites a path leaves the two ends disagreeing
   * about the daemon's own address, and that disagreement surfaces far from the rewrite. So the
   * daemon is configured with the part of the path that is its address rather than guessing at one:
   * no leading segment is stripped by shape, no prefix is matched by pattern. It is the same
   * property the control-socket url has — handed over whole, dialled verbatim, never parsed.
   *
   * <p>The routes below stay written as the paths they are, {@code /commands} and not {@code
   * <base>/commands}: the base is where this server is mounted, not part of what it serves, so
   * exactly one place — {@link #route} — knows about it.
   *
   * <p>{@code Optional<String>} rather than a {@code defaultValue = ""}: SmallRye reads an empty
   * default as <em>no value</em> and then fails to resolve a plain {@code String} when nothing is
   * injected. A daemon with no base is the normal case, so that spelling would make the binary die
   * on startup — and nothing in the suite would see it, because these tests construct {@code
   * ProjectsApi} directly and never resolve config at all.
   */
  @ConfigProperty(name = "qits.projects-daemon.api-base-path")
  Optional<String> apiBasePath;

  /** {@link #apiBasePath}, normalized: no trailing slash, empty when nothing fronts the daemon. */
  private String basePath = "";

  // The shared secret every request must present. Optional<> for the same SmallRye reason as the
  // identity knobs. Blank or absent ⇒ the server never binds; see the class javadoc.
  @ConfigProperty(name = "qits.projects-daemon.api-token")
  Optional<String> apiTokenConfig;

  /**
   * Off-event-loop pool for the handlers: launching and terminating processes blocks, and a
   * blocking call on the event loop would stall the socket writes and the hook webhook with it. One
   * thread per in-flight request, mirroring {@link ControlSocket}'s worker pool.
   */
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "projects-daemon-api");
            thread.setDaemon(true);
            return thread;
          });

  private volatile HttpServer server;
  private volatile String token;

  /**
   * The commands capability. Null until wired, in which case every {@code /commands} route answers
   * 503 rather than NPEing into the event loop.
   */
  private volatile CommandService commands;

  private volatile CommandRegistry registry;
  private volatile ProjectContext projectContext;

  /** The agent capability, wired alongside commands; null until then — every route answers 503. */
  private volatile AgentLaunchService agentLaunch;

  private volatile AgentSessionQueryService agentSessions;
  private volatile AgentDefaults agentDefaults;

  /** Wire the commands surface. */
  void wireCommands(
      CommandService commands, CommandRegistry registry, ProjectContext projectContext) {
    this.commands = commands;
    this.registry = registry;
    this.projectContext = projectContext;
  }

  /**
   * Wire the coding-agent surface. Separate from {@link #wireCommands} only so a test can exercise
   * commands without standing up a harness; {@link ControlSocket} wires both together.
   */
  void wireAgents(
      AgentLaunchService agentLaunch,
      AgentSessionQueryService agentSessions,
      AgentDefaults agentDefaults) {
    this.agentLaunch = agentLaunch;
    this.agentSessions = agentSessions;
    this.agentDefaults = agentDefaults;
  }

  /**
   * Bind, unless no token is configured. Called from {@link ControlSocket} once the checkout is
   * provisioned — before that there is nothing to run a command against.
   */
  public void start() {
    String configured = apiTokenConfig.map(String::trim).orElse("");
    if (configured.isEmpty()) {
      LOG.warn(
          "No qits.projects-daemon.api-token configured — the API stays unbound. It runs processes"
              + " over an untrusted checkout, so it is never served anonymously.");
      return;
    }
    listen(vertx, apiBindAddress, apiPort, configured)
        .onSuccess(
            s -> LOG.infof("projects-daemon API listening on %s:%d", apiBindAddress, s.actualPort()))
        .onFailure(
            t -> LOG.errorf(t, "projects-daemon API failed to bind %s:%d", apiBindAddress, apiPort));
  }

  /**
   * The bind, with everything explicit. Package-private and returning the listen future so a test
   * can bind an ephemeral port (pass {@code 0}) and read the one it actually got — the handlers here
   * spawn processes, so they are worth exercising over a real socket rather than only through a
   * seam.
   */
  Future<HttpServer> listen(Vertx vertx, String bindAddress, int port, String token) {
    this.token = token;
    // Null when constructed directly rather than by CDI, which is how every test here builds it.
    this.basePath = normalizeBase(apiBasePath == null ? null : apiBasePath.orElse(null));
    HttpServer bound = vertx.createHttpServer();
    this.server = bound;
    return bound
        .requestHandler(this::onRequest)
        // The interactive half of commands. Authenticated at the handshake so an unauthenticated
        // caller never gets a socket, and served here rather than over the control socket because
        // that protocol's command messages are fire-and-collect — no stdin, no resize.
        .webSocketHandshakeHandler(
            handshake ->
                CommandSockets.onHandshake(
                    handshake,
                    registry != null && authorized(handshake.headers()),
                    route(handshake.path())))
        .webSocketHandler(socket -> CommandSockets.attach(socket, registry, route(socket.path())))
        .listen(port, bindAddress);
  }

  /**
   * Normalize a configured base: a leading slash, no trailing one, and empty for every spelling of
   * "nothing fronts me" ({@code null}, blank, {@code "/"}).
   */
  private static String normalizeBase(String configured) {
    if (configured == null || configured.isBlank() || configured.equals("/")) {
      return "";
    }
    String value = configured.strip();
    if (!value.startsWith("/")) {
      value = "/" + value;
    }
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  /**
   * The route a request addresses: what is left of its path once the base this server is mounted at
   * is accounted for, or {@code null} when the request was not addressed to this daemon at all.
   *
   * <p>The trailing-slash check is what keeps {@code /projects/container/12/commands} from matching
   * a base of {@code /projects/container/1}. A plain {@code startsWith} would route one project's
   * request into another's daemon — which, on a host that runs a container per project, is a
   * cross-project code execution, not a 404.
   */
  private String route(String path) {
    if (basePath.isEmpty()) {
      return path;
    }
    if (path == null || !path.startsWith(basePath)) {
      return null;
    }
    String rest = path.substring(basePath.length());
    if (rest.isEmpty()) {
      return "/";
    }
    return rest.startsWith("/") ? rest : null;
  }

  /**
   * The configured port, readable before {@link #start} runs — {@link DaemonStreamTunnel} needs it
   * to reach this server on loopback, and is constructed earlier in the boot sequence than the
   * bind.
   */
  int apiPort() {
    return apiPort;
  }

  /** The bound port, {@code 0} before a successful listen — the test's handle on an ephemeral. */
  int actualPort() {
    HttpServer s = server;
    return s == null ? 0 : s.actualPort();
  }

  /**
   * Authenticate, then hand the request to a worker. Nothing blocking runs here: the reply is
   * marshalled back onto the request's own context to write, the same discipline {@link
   * ControlSocket} uses for its socket frames.
   */
  private void onRequest(HttpServerRequest request) {
    if (!authorized(request.headers())) {
      // Deliberately indistinguishable from a bad token and stated without detail: a caller with no
      // credential learns only that one is required, never whether the path it asked for exists.
      respond(request, 401, ProjectsJson.error("Unauthorized"));
      return;
    }
    String path = route(request.path());
    if (path == null) {
      // Addressed at some other base — the same 404 an unknown endpoint gets, because a caller who
      // guessed the wrong container should learn no more than one who guessed the wrong path.
      respond(request, 404, ProjectsJson.error("No such endpoint"));
      return;
    }
    if (path.equals(COMMANDS_PATH) || path.startsWith(COMMANDS_PATH + "/")) {
      onBodyRequest(request, path, (method, body) -> dispatchCommand(method, path, request, body));
      return;
    }
    if (isAgentPath(path)) {
      if (agentLaunch == null) {
        respond(request, 503, ProjectsJson.error("Coding agents are not available yet"));
        return;
      }
      onBodyRequest(request, path, (method, body) -> dispatchAgent(method, path, body));
      return;
    }
    respond(request, 404, ProjectsJson.error("No such endpoint"));
  }

  /** One answered request: the status and the body that goes with it. */
  private record Reply(int status, JsonObject body) {}

  /** Route and run, given the request's method and its (already read) body. */
  private interface Dispatch {
    Reply apply(HttpMethod method, String body);
  }

  /**
   * The shared shape of both surfaces: 503 until wired, GET/POST only, body read on the event loop
   * (it is a few dozen bytes) and only then handed to a worker. Both need a path segment after a
   * fixed prefix and a request body, which is why neither can be a plain query-parameter handler.
   */
  private void onBodyRequest(HttpServerRequest request, String path, Dispatch dispatch) {
    if (commands == null) {
      // Wired late, or not at all in a degraded boot. A retryable status rather than a 404 that
      // reads as "this daemon will never serve commands".
      respond(request, 503, ProjectsJson.error("Commands are not available yet"));
      return;
    }
    HttpMethod method = request.method();
    if (method != HttpMethod.GET && method != HttpMethod.POST) {
      respond(request, 405, ProjectsJson.error("Method not allowed"));
      return;
    }
    Context context = vertx.getOrCreateContext();
    request
        .body()
        .onFailure(t -> respond(request, 400, ProjectsJson.error("Could not read the request body")))
        .onSuccess(
            body -> {
              try {
                workers.execute(
                    () -> {
                      Reply reply = dispatch.apply(method, body.toString());
                      context.runOnContext(v -> respond(request, reply.status(), reply.body()));
                    });
              } catch (RejectedExecutionException shuttingDown) {
                // The pool is gone (a request that raced @PreDestroy). Answer rather than let the
                // rejection become an unhandled event-loop exception.
                respond(request, 503, ProjectsJson.error("Shutting down"));
              }
            });
  }

  /** Whether {@code path} belongs to the coding-agent surface. */
  private static boolean isAgentPath(String path) {
    return path.equals(AGENTS_PATH)
        || path.equals(AGENTS_AVAILABLE_PATH)
        || path.equals(AGENT_SESSIONS_PATH);
  }

  /**
   * Route and run one coding-agent request.
   *
   * <p>The catch ladder is {@link #dispatchCommand}'s, unchanged, and that is deliberate: because
   * qits-coding-agents depends on qits-commands, its services throw the <em>same</em> two exceptions
   * rather than declaring their own, so one mapping serves both surfaces and the frontend's error
   * handling does not fork.
   */
  private Reply dispatchAgent(HttpMethod method, String path, String body) {
    try {
      if (AGENTS_AVAILABLE_PATH.equals(path)) {
        return method == HttpMethod.GET
            ? new Reply(200, AgentJson.available(agentDefaults.defaultAgentType()))
            : new Reply(405, ProjectsJson.error("Method not allowed"));
      }
      if (AGENTS_PATH.equals(path)) {
        return method == HttpMethod.POST
            ? new Reply(
                200,
                AgentJson.launched(
                    agentLaunch.launch(launchRequest(body)),
                    projectContext.projectId(),
                    projectContext.repoName()))
            : new Reply(405, ProjectsJson.error("Method not allowed"));
      }
      if (AGENT_SESSIONS_PATH.equals(path)) {
        return method == HttpMethod.GET
            ? new Reply(200, AgentJson.sessions(agentSessions.sessionTree()))
            : new Reply(405, ProjectsJson.error("Method not allowed"));
      }
      return new Reply(404, ProjectsJson.error("No such endpoint"));
    } catch (CommandNotFoundException e) {
      return new Reply(404, ProjectsJson.error(e.getMessage()));
    } catch (InvalidCommandRequestException e) {
      return new Reply(400, ProjectsJson.error(e.getMessage()));
    } catch (RuntimeException e) {
      LOG.errorf(e, "projects-daemon agents API failed handling %s", path);
      return new Reply(500, ProjectsJson.error("Internal error"));
    }
  }

  /**
   * Route and run one {@code /commands} request. An unknown command is 404, a malformed request or
   * unknown action is 400 — the same mapping the agent surface uses.
   */
  private Reply dispatchCommand(
      HttpMethod method, String path, HttpServerRequest request, String body) {
    try {
      String projectId = projectContext.projectId();
      String repoName = projectContext.repoName();
      // Everything after "/commands", so "" for the collection and "/{id}[/verb]" otherwise.
      String rest = path.substring(COMMANDS_PATH.length());
      if (rest.isEmpty() || rest.equals("/")) {
        return method == HttpMethod.POST
            ? launchCommand(body, projectId, repoName)
            : new Reply(
                200,
                CommandJson.commands(
                    commands.list(parseStatus(request.getParam("status"))), projectId, repoName));
      }
      if (COMMAND_ACTIONS_PATH.equals(path) && method == HttpMethod.GET) {
        return new Reply(200, CommandJson.actions(commands.availableActions()));
      }
      String[] segments = rest.substring(1).split("/", 2);
      String commandId = segments[0];
      String verb = segments.length > 1 ? segments[1] : "";
      return switch (verb) {
        case "" ->
            method == HttpMethod.GET
                ? new Reply(200, CommandJson.command(commands.get(commandId), projectId, repoName))
                : new Reply(405, ProjectsJson.error("Method not allowed"));
        case "log" ->
            method == HttpMethod.GET
                ? new Reply(
                    200,
                    CommandJson.log(
                        commands.log(
                            commandId,
                            parseSeverity(request.getParam("severity")),
                            parseChannel(request.getParam("channel")))))
                : new Reply(405, ProjectsJson.error("Method not allowed"));
        case "terminate" ->
            method == HttpMethod.POST
                ? new Reply(
                    200, CommandJson.command(commands.terminate(commandId), projectId, repoName))
                : new Reply(405, ProjectsJson.error("Method not allowed"));
        default -> new Reply(404, ProjectsJson.error("No such endpoint"));
      };
    } catch (CommandNotFoundException e) {
      return new Reply(404, ProjectsJson.error(e.getMessage()));
    } catch (InvalidCommandRequestException e) {
      return new Reply(400, ProjectsJson.error(e.getMessage()));
    } catch (RuntimeException e) {
      // The text of an arbitrary exception can carry container paths the caller has no business
      // seeing, so it is logged here and not returned.
      LOG.errorf(e, "projects-daemon commands API failed handling %s", path);
      return new Reply(500, ProjectsJson.error("Internal error"));
    }
  }

  /** {@code POST /commands} — launch a declared action by id. */
  private Reply launchCommand(String body, String projectId, String repoName) {
    String actionId = jsonBody(body).getString("actionId");
    if (actionId == null || actionId.isBlank()) {
      return new Reply(400, ProjectsJson.error("actionId is required"));
    }
    return new Reply(200, CommandJson.launched(commands.launch(actionId), projectId, repoName));
  }

  /** {@code POST /agents} — the launch request, with the enums validated like a query parameter. */
  private static AgentLaunchRequest launchRequest(String body) {
    JsonObject json = jsonBody(body);
    return new AgentLaunchRequest(
        parseEnum(json.getString("scope"), AgentMcpScope::valueOf, "scope"),
        parseEnum(json.getString("mode"), AgentLaunchMode::valueOf, "mode"),
        json.getString("initialContext"),
        json.getString("resumeSessionId"),
        Boolean.TRUE.equals(json.getBoolean("fork")),
        Boolean.TRUE.equals(json.getBoolean("deliverTaskPrompt")),
        parseEnum(json.getString("agentType"), AgentType::valueOf, "agentType"));
  }

  private static JsonObject jsonBody(String body) {
    try {
      return new JsonObject(body == null || body.isBlank() ? "{}" : body);
    } catch (RuntimeException notJson) {
      throw new InvalidCommandRequestException("Expected a JSON body");
    }
  }

  /**
   * Query-parameter enums. An unparseable value is a 400 rather than being silently ignored:
   * quietly widening a filter would show a caller more than it asked for.
   */
  private static CommandStatus parseStatus(String raw) {
    return parseEnum(raw, CommandStatus::valueOf, "status");
  }

  private static LogSeverity parseSeverity(String raw) {
    return parseEnum(raw, LogSeverity::valueOf, "severity");
  }

  private static LogChannel parseChannel(String raw) {
    return parseEnum(raw, LogChannel::valueOf, "channel");
  }

  private static <T> T parseEnum(String raw, Function<String, T> of, String name) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return of.apply(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidCommandRequestException("Invalid " + name + ": " + raw);
    }
  }

  /**
   * Constant-time bearer check over any carrier's headers — a request's or a handshake's. {@link
   * MessageDigest#isEqual} rather than {@link String#equals}: the latter returns on the first
   * differing character, which is a byte-at-a-time oracle on a secret that never rotates within a
   * container's life.
   */
  private boolean authorized(MultiMap headers) {
    String header = headers.get("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      return false;
    }
    return MessageDigest.isEqual(
        header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8),
        token.getBytes(StandardCharsets.UTF_8));
  }

  /** Write one JSON answer. Always runs on the request's context, never throws. */
  private static void respond(HttpServerRequest request, int status, JsonObject body) {
    try {
      request
          .response()
          .setStatusCode(status)
          .putHeader("Content-Type", "application/json")
          // The bodies embed repository-controlled text; nosniff keeps a client from ever deciding
          // this is anything other than the JSON it is labelled as.
          .putHeader("X-Content-Type-Options", "nosniff")
          .end(body.encode());
    } catch (RuntimeException e) {
      // A client that vanished mid-response must not surface as an event-loop exception.
      LOG.debugf("projects-daemon API could not write a response: %s", e.getMessage());
    }
  }

  /** Stop accepting first, then drop the pool — the reverse order rejects live requests. */
  @PreDestroy
  void close() {
    HttpServer s = server;
    if (s != null) {
      s.close();
    }
    workers.shutdownNow();
  }
}
