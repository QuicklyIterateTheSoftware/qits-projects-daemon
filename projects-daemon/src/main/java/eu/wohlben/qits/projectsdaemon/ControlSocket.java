package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.agents.AgentAuthStatus;
import eu.wohlben.qits.projectsdaemon.agents.AgentLaunchService;
import eu.wohlben.qits.projectsdaemon.agents.AgentSessionQueryService;
import eu.wohlben.qits.projectsdaemon.agents.AgentSessionStore;
import eu.wohlben.qits.projectsdaemon.agents.AgentTranscriptService;
import eu.wohlben.qits.projectsdaemon.agents.AgentTranscriptTailService;
import eu.wohlben.qits.projectsdaemon.agents.CommandsAgentCommands;
import eu.wohlben.qits.projectsdaemon.agents.LocalProcessExecutor;
import eu.wohlben.qits.projectsdaemon.agents.ProcessRunner;
import eu.wohlben.qits.projectsdaemon.commands.CommandLifecycleService;
import eu.wohlben.qits.projectsdaemon.commands.CommandLogService;
import eu.wohlben.qits.projectsdaemon.commands.CommandRegistry;
import eu.wohlben.qits.projectsdaemon.commands.CommandService;
import eu.wohlben.qits.projectsdaemon.commands.CommandStore;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonCodec;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Describe;
import eu.wohlben.qits.projectsdaemon.protocol.Heartbeat;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.OpenStream;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectChanged;
import eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.projectsdaemon.protocol.Provisioned;
import eu.wohlben.qits.projectsdaemon.protocol.RunCommand;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.net.URI;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The persistent dial-home socket: {@code projects-daemon} connects to qits-projects' {@code
 * /projects/daemon/{projectId}} WebSocket, sends a {@link Hello}, and thereafter serves backend
 * requests ({@link RunCommand}, {@link Describe}, {@link OpenStream}) from in-container. The path
 * is <b>not</b> a constant here — the daemon dials the url it was handed, verbatim.
 *
 * <p><b>This class is also the daemon's single configuration reader.</b> The capability modules are
 * framework-free and cannot read configuration at all, so every setting they need is a {@code
 * @ConfigProperty} here and reaches them as a constructor argument. That is not tidiness: two
 * readers of one key is how {@link HookWebhook} and {@link AgentLaunchService} end up disagreeing
 * about the hook port, which fails invisibly — the agent runs, and simply never reports lineage or
 * activity.
 *
 * <p>Two invariants keep the container alive:
 *
 * <ul>
 *   <li><b>Never exits on failure.</b> This process is PID 1's child. So every connect/close/error
 *       path re-arms a capped-backoff retry instead of propagating — a backend that is down or a
 *       missing dial-home URL leaves the container alive exactly as {@code sleep infinity} would.
 *   <li><b>No blocking on the event loop.</b> Frame handling runs on a Vert.x event loop; command
 *       execution ({@link CommandExecutor}) and git reads ({@link ProjectDescriber}) run on a
 *       worker pool, and every reply is marshalled back onto the connection's context to write.
 * </ul>
 */
@ApplicationScoped
public class ControlSocket {

  private static final Logger LOG = Logger.getLogger(ControlSocket.class);

  @Inject Vertx vertx;

  /**
   * The loopback API over the checkout — the transport for the two capability modules. Injected
   * rather than constructed because, unlike {@link HookWebhook}, it carries its own config knobs;
   * started once the checkout exists.
   */
  @Inject ProjectsApi projectsApi;

  /**
   * Full dial-home URL qits-projects injected, e.g. {@code
   * ws://qits-projects:8080/projects/daemon/<projectId>}.
   *
   * <p>The daemon reaches two other hosts from this one address: the git host for the self-clone
   * ({@link Provisioner}) and the MCP server for agent launches ({@link DaemonMcpEndpoints}). Only
   * the second is derivable — that server is qits-projects, the same service this url points at.
   * The git host is qits-artifacts, so the Provisioner's fallback is a guess and says so.
   */
  @ConfigProperty(name = "qits.projects-daemon.url")
  Optional<String> url;

  // Identity is Optional<String>, not @ConfigProperty(defaultValue = ""): SmallRye treats an empty
  // default as "no value" and fails to resolve a plain String when the env is absent. Resolved to
  // "" below.
  @ConfigProperty(name = "qits.projects-daemon.project-id")
  Optional<String> projectIdConfig;

  /** The wrapper repository the container self-clones and runs agents over. */
  @ConfigProperty(name = "qits.projects-daemon.repo-name")
  Optional<String> repoNameConfig;

  // The git host the self-clone reads from. Unset ⇒ the Provisioner derives it from the dial-home
  // authority and says so in a WARN, because that is a guess about a different service.
  @ConfigProperty(name = "qits.projects-daemon.git-base")
  Optional<String> gitBaseConfig;

  // Build identity baked into the native image (filtered from Maven at build time). Announced in
  // the Hello so qits can show which daemon build a running container is on. Optional so a dev jar
  // built without filtering still boots.
  @ConfigProperty(name = "qits.projects-daemon.build.version")
  Optional<String> buildVersionConfig;

  @ConfigProperty(name = "qits.projects-daemon.build.time")
  Optional<String> buildTimeConfig;

  @ConfigProperty(name = "qits.projects-daemon.heartbeat-interval-ms", defaultValue = "20000")
  long heartbeatIntervalMs;

  @ConfigProperty(name = "qits.projects-daemon.reconnect-max-backoff-ms", defaultValue = "30000")
  long maxBackoffMs;

  /** Grace a terminate gives the process group between SIGTERM and SIGKILL. */
  @ConfigProperty(name = "qits.projects-daemon.term-grace-ms", defaultValue = "5000")
  long termGraceMs;

  /**
   * Loopback port the in-container coding-agent lifecycle hooks POST to. {@link HookWebhook} binds
   * it and {@link AgentLaunchService} renders it into the hook {@code curl} every launch carries;
   * passing the same field to both is what keeps them from disagreeing.
   */
  @ConfigProperty(name = "qits.projects-daemon.hooks-port", defaultValue = "13337")
  int hooksPort;

  /**
   * Where the shared agent-credential volume is mounted in this container. Read here and nowhere
   * else: the launch service overlays it as the agent's HOME and the transcript service resolves
   * config dirs under it, and two independent reads of one key is how those two silently disagree.
   */
  @ConfigProperty(name = "qits.projects-daemon.claude-mount", defaultValue = "/claude-home")
  String claudeMount;

  /** The harness a launch uses when the request names none. */
  @ConfigProperty(name = "qits.agent.default-type")
  Optional<String> agentDefaultType;

  /** Whether launches wire the turn-boundary activity hooks; the lineage hook is unconditional. */
  @ConfigProperty(name = "qits.agent.activity-tracking-enabled", defaultValue = "true")
  boolean agentActivityTrackingEnabled;

  @ConfigProperty(name = "qits.agent.transcript-tail-poll-ms", defaultValue = "500")
  long transcriptTailPollMs;

  /**
   * Explicit MCP base URL for the {@code repository} server. Unlike the workspace daemon's three
   * overrides, this one is not a correction for a guess — the derivation is provably right, because
   * the control socket and that server are the same service. It points an agent at a different qits
   * instance, or at a separately deployed MCP server if that ever splits out.
   */
  @ConfigProperty(name = "qits.repository-mcp.url")
  Optional<String> repositoryMcpUrl;

  private String projectId = "";
  private String repoName = "";

  /** Off-event-loop pool for blocking process/git work; one thread per in-flight request. */
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "projects-daemon-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile WebSocketClient client;
  private volatile WebSocket socket;
  private volatile Context socketContext;

  /**
   * The loopback listener the in-container coding agent's lifecycle hooks POST to; relays {@link
   * eu.wohlben.qits.projectsdaemon.protocol.AgentActivity} home. Started unconditionally (not gated
   * on provisioning — a hook can fire in a reconnect-adopted container, and SessionStart drives
   * session lineage, which must always be captured); re-reports on reconnect.
   */
  private volatile HookWebhook hooks;

  /**
   * The reverse tunnel {@link ProjectsApi} is reached through, since it binds loopback and has no
   * address on the shared network at all.
   */
  private volatile DaemonStreamTunnel tunnel;

  private volatile AgentTranscriptTailService transcriptTail;

  /** Transcript aggregates, held here so the query service and the sweep share one instance. */
  private final AgentSessionStore agentSessionStore = new AgentSessionStore();

  /** Ensures the autonomous self-provision runs at most once per daemon lifetime. */
  private final AtomicBoolean provisionStarted = new AtomicBoolean();

  /** Where the daemon runs the self-clone and every command (image {@code WORKDIR}). */
  private static final File WORKSPACE_DIR = new File("/workspace");

  /**
   * Frames emitted before the socket first connects — the boot self-clone can begin, and finish,
   * before the dial-home succeeds. Bounded so a never-connecting backend cannot grow it without
   * limit; the terminal provisioning events always survive (they bypass the cap), and streamed
   * clone chunks are the only thing dropped past it.
   */
  private final Queue<DaemonMessage> pendingOutbound = new ConcurrentLinkedQueue<>();

  private static final int PENDING_OUTBOUND_CAP = 4096;

  /**
   * Guards the transition between buffering (socket down) and direct-write (socket up) so a
   * worker-thread {@link #send} cannot interleave with {@link #onConnected}'s publish-and-flush:
   * without it a terminal frame can be stranded in {@link #pendingOutbound} (enqueued just after an
   * empty flush) or written ahead of still-buffered clone chunks (socket published before the
   * flush).
   */
  private final Object sendLock = new Object();

  /**
   * Begin dialing home. If no URL is configured, log and stay idle — the container must not die for
   * want of a socket.
   */
  public void start() {
    projectId = projectIdConfig.orElse("");
    repoName = repoNameConfig.orElse("");
    if (url.isEmpty() || url.get().isBlank()) {
      LOG.warn(
          "No qits.projects-daemon.url configured — projects-daemon is idle (the container stays"
              + " alive).");
      return;
    }
    // Autonomous self-provision: clone the wrapper into /workspace and materialize submodules from
    // env, on boot, off the event loop — independent of whether the socket is up yet (its results
    // buffer until it is). qits sends nothing; it only awaits the Provisioned/ProvisionFailed.
    startProvisioning();
    // The hook webhook is independent of provisioning: a lifecycle hook can fire in a
    // reconnect-adopted (already-provisioned) container, and SessionStart drives session lineage,
    // which must be captured regardless. Its frames buffer in pendingOutbound until the socket is
    // up.
    hooks = new HookWebhook(vertx, hooksPort, this::send);
    hooks.start();
    // The reverse tunnel qits reaches ProjectsApi through. Independent of provisioning for the same
    // reason the hook webhook is: it only needs the url and the port, and a stream requested before
    // the API is up simply fails to connect to loopback and answers nothing.
    tunnel = new DaemonStreamTunnel(vertx, url.get(), projectsApi.apiPort());
    tunnel.start();
    client = vertx.createWebSocketClient();
    if (heartbeatIntervalMs > 0) {
      vertx.setPeriodic(heartbeatIntervalMs, id -> heartbeat());
    }
    connect(0);
  }

  /** Kick off the boot self-clone on the worker pool, at most once. */
  private void startProvisioning() {
    if (!provisionStarted.compareAndSet(false, true)) {
      return;
    }
    Provisioner.Env env =
        new Provisioner.Env(projectId, repoName, url.get(), gitBaseConfig.orElse(""));
    workers.execute(
        () -> {
          if (Provisioner.provision(env, this::send)) {
            wireCapabilities();
          }
        });
  }

  /**
   * Assemble {@code qits-commands} and {@code qits-coding-agents} over the provisioned checkout and
   * bind the API. A failed provision means qits is tearing the container down, so there is nothing
   * to wire and the API never binds — every route would answer 500 from a missing checkout anyway.
   *
   * <p>The modules are framework-free by design — no CDI — so their objects are constructed here
   * rather than injected. That is also why the wiring is explicit about the two seams: {@link
   * DaemonProjectContext} answers the identity questions, and {@link NoDeclaredActions} answers
   * action lookup with an honest empty rather than a null that would 500.
   *
   * <p>{@code commandsChanged} rides the control socket as a {@link ProjectChanged} frame, so the
   * browser refetches the Commands list when something lands rather than at the next poll. The
   * transcript sweep uses the same callback.
   *
   * <p>A daemon with no dial-home url cannot derive the MCP endpoint an agent would be launched
   * with — but it also never connected, so it is not serving this API either. The agent surface is
   * then left unwired and answers 503.
   */
  private void wireCapabilities() {
    DaemonProjectContext context =
        new DaemonProjectContext(projectId, repoName, () -> "", ProjectDescriber::head);
    CommandStore store = new CommandStore();
    CommandLogService logs = new CommandLogService(store, null);
    CommandLifecycleService lifecycle =
        new CommandLifecycleService(store, () -> nudge(ChangeTopic.COMMANDS));
    CommandRegistry commandRegistry = new CommandRegistry(WORKSPACE_DIR.toPath(), termGraceMs);
    CommandService commandService =
        new CommandService(
            store, commandRegistry, lifecycle, logs, context, new NoDeclaredActions());
    projectsApi.wireCommands(commandService, commandRegistry, context);
    wireAgents(store, logs, commandService, commandRegistry, context);
    projectsApi.start();
    LOG.infof("projects-daemon API wired for project %s", projectId);
  }

  private void wireAgents(
      CommandStore store,
      CommandLogService logs,
      CommandService commandService,
      CommandRegistry commandRegistry,
      DaemonProjectContext context) {
    DaemonAgentDefaults defaults =
        new DaemonAgentDefaults(agentDefaultType, agentActivityTrackingEnabled);
    DaemonMcpEndpoints endpoints;
    try {
      endpoints = new DaemonMcpEndpoints(url.orElse(null), projectId, repositoryMcpUrl);
    } catch (IllegalStateException e) {
      LOG.warnf("Coding agents stay unwired: %s", e.getMessage());
      return;
    }
    ProcessRunner processes = new LocalProcessExecutor();
    AgentTranscriptService transcripts =
        new AgentTranscriptService(
            store, logs, agentSessionStore, claudeMount, () -> nudge(ChangeTopic.COMMANDS));
    AgentTranscriptTailService tail =
        new AgentTranscriptTailService(transcripts, logs, transcriptTailPollMs);
    tail.start();
    this.transcriptTail = tail;
    AgentLaunchService launch =
        new AgentLaunchService(
            new CommandsAgentCommands(commandService, commandRegistry, store),
            new AgentAuthStatus(processes, claudeMount, WORKSPACE_DIR.toPath()),
            transcripts,
            tail,
            defaults,
            endpoints,
            context,
            claudeMount,
            hooksPort);
    projectsApi.wireAgents(launch, new AgentSessionQueryService(store, agentSessionStore), defaults);
  }

  /**
   * The change-hint topics this daemon nudges about. A String on the wire (see {@link
   * ProjectChanged}); this constant holder keeps the spelling in one place rather than scattered at
   * the call sites.
   */
  private static final class ChangeTopic {
    private static final String COMMANDS = "COMMANDS";

    private ChangeTopic() {}
  }

  /**
   * Push a change nudge home. Best-effort by design: the frame carries no state, so a nudge dropped
   * while reconnecting costs one stale view until the next one.
   */
  private void nudge(String topic) {
    if (projectId == null || projectId.isBlank()) {
      return;
    }
    send(new ProjectChanged(projectId, topic));
  }

  private void connect(int attempt) {
    URI uri;
    try {
      uri = URI.create(url.get());
    } catch (RuntimeException e) {
      LOG.errorf(e, "Malformed qits.projects-daemon.url '%s' — projects-daemon idle.", url.get());
      return; // an unparseable URL will not become parseable on retry; stay alive, stay idle
    }
    int port = uri.getPort() != -1 ? uri.getPort() : 80;
    WebSocketConnectOptions options =
        new WebSocketConnectOptions().setHost(uri.getHost()).setPort(port).setURI(uri.getRawPath());
    client
        .connect(options)
        .onSuccess(this::onConnected)
        .onFailure(
            t -> {
              LOG.debugf("projects-daemon dial-home failed (attempt %d): %s", attempt, t.getMessage());
              reconnect(attempt);
            });
  }

  private void onConnected(WebSocket ws) {
    socketContext = vertx.getOrCreateContext();
    ws.textMessageHandler(this::onFrame);
    ws.closeHandler(
        v -> {
          LOG.debug("projects-daemon control socket closed — reconnecting.");
          synchronized (sendLock) {
            socket = null;
          }
          reconnect(0);
        });
    ws.exceptionHandler(t -> LOG.debugf("projects-daemon control socket error: %s", t.getMessage()));
    synchronized (sendLock) {
      // Announce, then drain the boot-provision buffer, then publish `socket` LAST — all under
      // sendLock. So a worker-thread send() (also under the lock) either ran before us (its frame is
      // in the buffer we flush here, in order) or runs after (sees the published socket and writes
      // directly, after the flushed chunks). This closes both the stranding and the reordering race.
      send(
          new Hello(
              projectId,
              repoName,
              DaemonProtocol.CAPABILITY_VERSION,
              buildVersionConfig.orElse(null),
              buildTimeConfig.orElse(null)),
          ws);
      send(new DaemonLog("INFO", "projects-daemon online for project " + projectId), ws);
      flushPending(ws);
      socket = ws;
    }
    // Reconnect adoption: re-report the last known agent activity per tracked command, so a qits
    // restart rebuilds the live "cooking / idle / waiting" projection from the daemon's retained
    // state. Off the socket-publish path (after `socket = ws`) so the re-report writes directly.
    HookWebhook h = hooks;
    if (h != null) {
      workers.execute(h::reportCurrent);
    }
    LOG.infof("projects-daemon control socket established for project %s", projectId);
  }

  private void flushPending(WebSocket ws) {
    DaemonMessage buffered;
    while ((buffered = pendingOutbound.poll()) != null) {
      send(buffered, ws);
    }
  }

  private void reconnect(int attempt) {
    long backoff = Math.min(maxBackoffMs, 500L * (1L << Math.min(attempt, 6)));
    vertx.setTimer(backoff, id -> connect(attempt + 1));
  }

  private void onFrame(String json) {
    DaemonMessage message;
    try {
      message = DaemonCodec.decode(new JsonObject(json).getMap());
    } catch (RuntimeException e) {
      LOG.debugf("projects-daemon dropped an undecodable frame: %s", e.getMessage());
      return;
    }
    switch (message) {
      case RunCommand command -> workers.execute(() -> CommandExecutor.run(command, this::send));
      case Describe ignored ->
          workers.execute(() -> send(ProjectDescriber.describe(projectId, repoName)));
      case OpenStream request -> {
        // On the event loop: both connects are non-blocking futures and the pumps are
        // handler-driven, so there is nothing here worth a worker thread.
        DaemonStreamTunnel t = tunnel;
        if (t != null) {
          t.open(request.nonce(), request.path());
        }
      }
      default ->
          // Ack and any daemon→qits echoes are informational here.
          LOG.debugf("projects-daemon received %s", message.getClass().getSimpleName());
    }
  }

  private void heartbeat() {
    WebSocket ws = socket;
    if (ws != null && !ws.isClosed()) {
      send(new Heartbeat(projectId), ws);
    }
  }

  /**
   * Emit a message on the current socket, marshalling the write onto its event loop. When the
   * socket is not up yet, buffer it for {@link #flushPending}: <b>terminal</b> provisioning events
   * always buffer; streamed clone chunks buffer only up to {@link #PENDING_OUTBOUND_CAP}, then drop
   * — the outcome, not the log tail, is what qits needs, and a verbose clone must not push its own
   * terminal out or qits' await hangs to timeout.
   */
  private void send(DaemonMessage message) {
    synchronized (sendLock) {
      WebSocket ws = socket;
      if (ws != null) {
        send(message, ws);
        return;
      }
      boolean terminal = message instanceof Provisioned || message instanceof ProvisionFailed;
      if (terminal || pendingOutbound.size() < PENDING_OUTBOUND_CAP) {
        pendingOutbound.offer(message);
      }
    }
  }

  private void send(DaemonMessage message, WebSocket ws) {
    String json = new JsonObject(DaemonCodec.encode(message)).encode();
    Context context = socketContext;
    if (context != null && Vertx.currentContext() != context) {
      context.runOnContext(v -> writeIfOpen(ws, json));
    } else {
      writeIfOpen(ws, json);
    }
  }

  private static void writeIfOpen(WebSocket ws, String json) {
    if (!ws.isClosed()) {
      ws.writeTextMessage(json);
    }
  }

  @PreDestroy
  void stop() {
    HookWebhook h = hooks;
    if (h != null) {
      h.close();
    }
    DaemonStreamTunnel tun = tunnel;
    if (tun != null) {
      tun.close();
    }
    AgentTranscriptTailService t = transcriptTail;
    if (t != null) {
      t.close();
    }
    workers.shutdownNow();
    WebSocket ws = socket;
    if (ws != null) {
      ws.close();
    }
    WebSocketClient c = client;
    if (c != null) {
      c.close();
    }
  }
}
