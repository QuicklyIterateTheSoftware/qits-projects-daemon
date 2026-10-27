package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentAuthStatus;
import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentSessionQueryService;
import eu.wohlben.qits.agents.AgentSessionStore;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentTranscriptService;
import eu.wohlben.qits.agents.AgentTranscriptTailService;
import eu.wohlben.qits.agents.CommandsAgentCommands;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.agents.HarnessCapabilityService;
import eu.wohlben.qits.agents.LocalProcessExecutor;
import eu.wohlben.qits.agents.ProcessRunner;
import eu.wohlben.qits.agents.WorkLinkProjects;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogLine;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.LogChannel;
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
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
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
import java.nio.file.Path;
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
   * started once the boot provision has run, with or without a checkout to show for it.
   */
  @Inject ProjectsApi projectsApi;

  /**
   * Full dial-home URL qits-projects injected, e.g. {@code
   * ws://qits-projects:8080/projects/daemon/<projectId>}.
   *
   * <p>The git host for the self-clone ({@link Provisioner}) falls back on this address. The git
   * host is not this service, so that fallback is a guess and says so.
   */
  @ConfigProperty(name = "qits.projects-daemon.url")
  Optional<String> url;

  /**
   * The project token ({@code QITS_TOKEN}), set only on a runner-placed project container — a front
   * desk on somebody's own machine, which reaches qits only through the public edge, and the edge
   * admits a bearer and nothing else. Where it is set it is the whole credential: the control
   * socket, every tunnel dial-back, the boot clone's git header and the platform MCP servers all
   * present it as is, and nothing is minted ({@link #bearer()}). It is this daemon's only dial-home
   * credential; absent, the daemon dials anonymously, as the clone-alone/developer topology does.
   * Read here and nowhere else; everything else is handed it. The same shape as
   * qits-workspace-daemon's {@code qits.workspace-daemon.token} (qits-625).
   */
  @ConfigProperty(name = "qits.projects-daemon.token")
  Optional<String> token;

  /**
   * Whom {@link #token} was minted for ({@code QITS_TOKEN_SUBJECT}). Read for the boot log only: it
   * names the credential in the container's stdout without printing the credential.
   */
  @ConfigProperty(name = "qits.projects-daemon.token-subject")
  Optional<String> tokenSubject;

  // Identity is Optional<String>, not @ConfigProperty(defaultValue = ""): SmallRye treats an empty
  // default as "no value" and fails to resolve a plain String when the env is absent. Resolved to
  // "" below.
  @ConfigProperty(name = "qits.projects-daemon.project-id")
  Optional<String> projectIdConfig;

  /** The wrapper repository the container self-clones and runs agents over. */
  @ConfigProperty(name = "qits.projects-daemon.repo-name")
  Optional<String> repoNameConfig;

  // The git host the self-clone reads from: qits-githost, serving /git/<projectId>/<repoName>.
  // Unset ⇒ the Provisioner refuses to clone and says so, because the git host's address is not
  // derivable from this container's own (see Provisioner's javadoc).
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

  /**
   * The per-surface agent configuration document qits-projects created this container with, as
   * bytes ({@code QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION}), and where to materialize it ({@code
   * …_PATH}). Both or neither; see {@link AgentConfigurationBoot} for why it rides the environment
   * rather than being mounted, and for what each of the three states means.
   */
  @ConfigProperty(name = "qits.projects-daemon.agent-configuration")
  Optional<String> agentConfiguration;

  @ConfigProperty(name = "qits.projects-daemon.agent-configuration-path")
  Optional<String> agentConfigurationPath;

  /**
   * The image build this container runs, for the capability report the editor's dropdowns are keyed
   * by.
   *
   * <p><b>Blank is the ordinary case and it is not a gap.</b> Nothing tells a container its own
   * image tag today — the daemon's own {@code build.version} is this repository's release, which is
   * a different thing from the {@code qits/project-agent} calver the container was created from —
   * and the host that <em>chose</em> that pin fills a blank in from it when it records the report.
   * The key exists so a deployment that does inject the value wins over that fallback, which is the
   * rule qits-projects' relay states: a daemon that names it wins, always.
   */
  @ConfigProperty(name = "qits.projects-daemon.image-version")
  Optional<String> imageVersion;

  /**
   * Whether this container holds {@code /workspace} at what the repositories released, by
   * supervising {@code qits checkout-daemon} for the container's life (see {@link
   * CheckoutFollower}).
   *
   * <p>On by default, because a project agent reading a checkout that drifted from the releases is
   * the failure this exists to remove. The switch is here for the container you want to pin by hand
   * — a reproduction, a bisect — and it spawns nothing and says so once rather than starting and
   * idling, so "off" is legible in the log rather than inferred from silence.
   */
  @ConfigProperty(name = "qits.projects-daemon.checkout-follow", defaultValue = "true")
  boolean checkoutFollow;

  /**
   * Whether this container keeps a {@code project.work} front desk running ({@code
   * QITS_PROJECTS_DAEMON_LIFECYCLE}): {@code ALWAYS_ON} or {@code ON_DEMAND}, the default. Anything
   * else logs one WARN and counts as {@code ON_DEMAND}. See {@link FrontDeskKeeper}.
   */
  @ConfigProperty(name = "qits.projects-daemon.lifecycle")
  Optional<String> lifecycle;

  /**
   * Whether this container's front desk holds the delivery rights ({@code
   * QITS_PROJECTS_DAEMON_DELIVERY}): {@code on} or {@code off}, the default. On with a token, the
   * front desk is kept whatever {@link #lifecycle} says and launched with the delivery token; it
   * runs a delivery pass only when a person prompts it (qits-1597). See {@link DeliveryKeeper} and
   * {@link FrontDeskCredential}.
   */
  @ConfigProperty(name = "qits.projects-daemon.delivery")
  Optional<String> delivery;

  /**
   * The desk's delivery token ({@code QITS_DELIVERY_TOKEN}), handed to the keeper's front desk
   * session as its {@code QITS_TOKEN} ({@link FrontDeskCredential}) and to no other child ({@link
   * DaemonSecrets}). Unset means no delivery rights, whatever {@link #delivery} says.
   */
  @ConfigProperty(name = "qits.projects-daemon.delivery-token")
  Optional<String> deliveryToken;

  /** Whom {@link #deliveryToken} names ({@code QITS_DELIVERY_TOKEN_SUBJECT}). */
  @ConfigProperty(name = "qits.projects-daemon.delivery-token-subject")
  Optional<String> deliveryTokenSubject;

  /**
   * How long a launch waits for qits' first {@link SessionCredentials} frame before it falls back to
   * legacy mode ({@code …_SESSION_CREDENTIALS_GRACE}, ISO-8601, PT20S), measured from daemon start
   * and again from the first connection. See {@link SessionCredentialStore}.
   */
  @ConfigProperty(name = "qits.projects-daemon.session-credentials-grace")
  Optional<String> sessionCredentialsGrace;

  /** Whether launches wire the turn-boundary activity hooks; the lineage hook is unconditional. */
  @ConfigProperty(name = "qits.agent.activity-tracking-enabled", defaultValue = "true")
  boolean agentActivityTrackingEnabled;

  @ConfigProperty(name = "qits.agent.transcript-tail-poll-ms", defaultValue = "500")
  long transcriptTailPollMs;

  /**
   * The url of the central platform-access {@code qits} MCP server (qits-630), the one platform
   * server an agent gets. There is nothing to derive it from — it is a separately deployed service —
   * so unset means no server is attached.
   */
  @ConfigProperty(name = "qits.platform-mcp.url")
  Optional<String> platformMcpUrl;

  /**
   * The platform domain ({@code QITS_DOMAIN}), which qits-projects-service's {@code FrontDeskSpecs}
   * injects into every container it creates — the same name the library's {@link
   * eu.wohlben.qits.agents.AgentDefaults#platformDomain()} documents. Unlike the rest of this
   * daemon's own settings, the key maps straight off the bare env var rather than through the {@code
   * qits.projects-daemon.*} prefix, because the name is the platform's, not this daemon's own
   * (qits-1204).
   */
  @ConfigProperty(name = "qits.domain")
  Optional<String> domain;

  // Resolved from config in start(); package-private so a test can wire the surface without dialling
  // home.
  String projectId = "";
  String repoName = "";

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

  /**
   * What this container was created to run its one surface ({@code project.work}) as. Read
   * <b>once</b>, at boot, in {@link #start()} — a container keeps what it was born with, so
   * re-reading it per launch would be file IO for a value that cannot change. Defaults to the
   * library's shipped constants, which is what a container created before the configuration epic
   * shipped runs on.
   */
  private volatile AgentSurfaceConfigurations surfaceConfigurations =
      AgentSurfaceConfigurations.shipped();

  /**
   * Ensures the autonomous self-provision runs at most once per daemon lifetime.
   *
   * <p><b>There is no re-provision path, deliberately not invented here.</b> This latch holds for
   * the whole process lifetime, and qits' {@code ensure} no-ops on a container that is already
   * running — so a container whose clone failed never retries. Recovery today is to remove the
   * container and ensure it again.
   */
  private final AtomicBoolean provisionStarted = new AtomicBoolean();

  /**
   * Whether the boot self-provision produced a usable checkout. The API binds either way (see {@link
   * #wireCapabilities}); this is what keeps the degraded surface from claiming a commit it does not
   * have, and what {@link #followCheckout()} refuses to start a follower without.
   *
   * <p>Package-private so a test can put the daemon in either state without running a clone.
   */
  volatile boolean provisioned;

  /**
   * The supervised {@code qits checkout-daemon} child, once the boot provision produced a checkout
   * for it to hold. Null until then, and on a container whose provision failed it stays null for
   * the process lifetime. Package-private for the same reason {@link #provisioned} is.
   */
  volatile CheckoutFollower checkoutFollower;

  /**
   * The front-desk keeper {@link #wireAgents} built over the wired launch service, started by
   * {@link #keepFrontDesk()} once the provision succeeded. Null when the agent surface stayed
   * unwired. Package-private for the same reason {@link #provisioned} is.
   */
  volatile FrontDeskKeeper frontDesk;

  /**
   * The front desk's inbox {@link #wireAgents} built over {@link #frontDesk}'s own session: the
   * turns {@code POST /front-desk/prompt} types or queues. Null when the agent surface stayed
   * unwired.
   */
  volatile FrontDeskInbox frontDeskInbox;

  /**
   * The session multiplex {@link #wireAgents} built over the wired launch service (qits-770): one
   * ladder per session key of qits' {@link Sessions} frame, the front desk's being {@link
   * #frontDesk}'s. Started beside the front desk once the provision succeeded. Null when the agent
   * surface stayed unwired. Package-private for the same reason {@link #provisioned} is.
   */
  volatile SessionKeeper sessionKeeper;

  /**
   * The last {@link Sessions} frame, held for a keeper wired after it arrived — qits sends one right
   * after {@code hello}, usually before the boot clone (and so the wiring) has finished.
   */
  private volatile Sessions lastSessions;

  /**
   * The per-session credentials qits sends over this socket ({@link SessionCredentials},
   * qits-1482), which every agent launch picks its {@code QITS_TOKEN} from. Until the first frame
   * — an older qits sends none — launches run as they did before it (legacy mode).
   */
  final SessionCredentialStore sessionCredentials =
      new SessionCredentialStore(
          () -> rawProjectToken().orElse(""),
          () -> tokenSubject == null ? "" : tokenSubject.orElse(""));

  /**
   * The live terminal and chat sessions {@link #wireCapabilities} spawns — held so {@link #stop}
   * can terminate them ahead of everything else. See {@link #stopAgents} for why that ordering
   * matters.
   */
  private volatile CommandRegistry commands;

  /**
   * The follower's restart ladder and give-up rule, as this daemon's policy rather than as
   * configuration. They are constants and not keys on purpose: a knob here is a way to make the
   * give-up rule invisible, and the only caller that needs other values is a test driving the
   * ladder in milliseconds.
   */
  private static final long CHECKOUT_FOLLOW_BACKOFF_INITIAL_MS = 1_000;

  private static final long CHECKOUT_FOLLOW_BACKOFF_MAX_MS = 30_000;
  private static final long CHECKOUT_FOLLOW_RUN_RESET_MS = 60_000;
  private static final int CHECKOUT_FOLLOW_MAX_RUNS = 5;

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
    // First: the grace window for qits' sessionCredentials frame is measured from daemon start, so
    // a daemon that never connects still launches, one window later, in legacy mode.
    sessionCredentials.openWindow(SessionCredentialStore.parseGrace(sessionCredentialsGrace));
    projectId = projectIdConfig.orElse("");
    repoName = repoNameConfig.orElse("");
    // Before anything is started, and deliberately so: a document that cannot be trusted must fail
    // where an operator sees it — at boot, naming the offending key — rather than at the first
    // launch of the one surface that was wrong. Both refusals throw out of here and out of Main,
    // which is what "fails visibly rather than idling while looking healthy" means for this image.
    surfaceConfigurations =
        AgentConfigurationBoot.materialize(agentConfiguration, agentConfigurationPath);
    // The delivery loop's brief is the project.delivery surface's prompt in that document, and the
    // document is read only here, so this is where the brief is (re)written. Never throws.
    DeliveryKeeper.writeBrief(DeliveryKeeper.BRIEF_PATH, surfaceConfigurations, projectId);
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
    if (bearer().isPresent()) {
      LOG.infof(
          "projects-daemon reaches qits with its project token (subject %s); nothing is minted.",
          tokenSubject == null
              ? "unknown"
              : tokenSubject.filter(value -> !value.isBlank()).orElse("unknown"));
    }
    // The reverse tunnel qits reaches ProjectsApi through. Independent of provisioning for the same
    // reason the hook webhook is: it only needs the url and the port, and a stream requested before
    // the API is up simply fails to connect to loopback and answers nothing.
    tunnel =
        new DaemonStreamTunnel(
            vertx, url.get(), this::dialBackAuthorization, projectsApi.apiPort());
    tunnel.start();
    // TLS for a wss url, with host verification against the default trust store (DaemonDial).
    client = vertx.createWebSocketClient(DaemonDial.clientOptions());
    if (heartbeatIntervalMs > 0) {
      vertx.setPeriodic(heartbeatIntervalMs, id -> heartbeat());
    }
    connect(0);
  }

  /**
   * Kick off the boot self-clone on the worker pool, at most once, then wire the surface — whatever
   * the outcome, see {@link #wireCapabilities}.
   */
  private void startProvisioning() {
    if (!provisionStarted.compareAndSet(false, true)) {
      return;
    }
    workers.execute(
        () -> {
          // The project token, when set, is the git header as is; nothing is minted, and absent, the
          // boot clone is anonymous too — the same credential every other dial-home presents.
          String gitAuthorization = bearer().orElse("");
          Provisioner.Env env =
              new Provisioner.Env(
                  projectId, repoName, gitBaseConfig.orElse(""), gitAuthorization);
          provisioned = Provisioner.provision(env, this::send);
          wireCapabilities();
          // After the API is wired, never before: the loopback surface must not wait on a
          // subprocess, and a container whose clone failed has no checkout to hold.
          followCheckout();
          // Last: a desk needs both the checkout and the wired agent surface.
          keepFrontDesk();
        });
  }

  /**
   * Start the {@code qits checkout-daemon} child that holds {@code /workspace} at what the
   * repositories released — but only when the boot provision produced a checkout for it to hold.
   *
   * <p>Never throws. This runs on the boot worker of PID 1's child, so a follower that cannot be
   * constructed costs the container a log line and nothing else; the alternative is an escaping
   * exception on the one path that also wired the agent surface.
   *
   * <p>Package-private so a test can drive both sides of the {@link #provisioned} guard without
   * running a clone.
   */
  void followCheckout() {
    if (!provisioned) {
      // Nothing to hold. A follower here would fail its first fetch against a directory that is
      // not a checkout, once a second, for the life of the container.
      return;
    }
    try {
      CheckoutFollower follower =
          new CheckoutFollower(
              checkoutFollow,
              CheckoutFollower.DEFAULT_BINARY,
              gitBaseConfig.orElse(""),
              CHECKOUT_FOLLOW_BACKOFF_INITIAL_MS,
              CHECKOUT_FOLLOW_BACKOFF_MAX_MS,
              CHECKOUT_FOLLOW_RUN_RESET_MS,
              CHECKOUT_FOLLOW_MAX_RUNS,
              termGraceMs,
              this::send);
      checkoutFollower = follower;
      follower.start();
    } catch (RuntimeException e) {
      LOG.warnf("The checkout follower did not start: %s", e.getMessage());
    }
  }

  /**
   * Start the {@link FrontDeskKeeper} and the {@link SessionKeeper} — but only when the boot
   * provision produced a checkout and {@link #wireAgents} wired a launch service. The front desk
   * keeper is a no-op unless the lifecycle is {@code ALWAYS_ON}, delivery is {@code on}, or qits'
   * {@code sessions} frame asks for the desk; the session keeper launches only what that frame asks
   * for. A failed provision is told to the session keeper, which reports every wanted key as failed.
   * Never throws, for the same reason {@link #followCheckout()} does not.
   */
  void keepFrontDesk() {
    SessionKeeper sessions = sessionKeeper;
    if (!provisioned) {
      if (sessions != null) {
        sessions.refuse("the boot provision failed: there is no checkout to run a session in");
      }
      return;
    }
    FrontDeskKeeper keeper = frontDesk;
    if (keeper != null) {
      try {
        keeper.start();
      } catch (RuntimeException e) {
        LOG.warnf("The front desk keeper did not start: %s", e.getMessage());
      }
    }
    if (sessions != null) {
      try {
        sessions.start();
      } catch (RuntimeException e) {
        LOG.warnf("The session keeper did not start: %s", e.getMessage());
      }
    }
  }

  /**
   * Assemble {@code qits-commands} and {@code qits-coding-agents} over the checkout and bind the
   * API — <b>also when the provision failed</b>.
   *
   * <p>qits does not tear a failed container down: it records the failure and reports it on the
   * agent-container read, and the container is left running. So leaving the API unbound would turn
   * a visible error into a silent 502 — every browser call arrives through the tunnel at a port
   * nothing listens on — and the one surface that could show the failure would be the one surface
   * that never binds. Binding keeps the routes reachable, and they degrade honestly against a
   * missing or partial checkout: the command list is empty, no action is declared, the recorded
   * commit is blank, and anything that would have to run in the checkout answers 503 with the
   * reason ({@link eu.wohlben.qits.commands.CheckoutUnavailableException}) instead
   * of "Internal error".
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
   * <p>Package-private so a test can wire and serve without a provision, which is the case this
   * whole method now has to survive.
   */
  void wireCapabilities() {
    // The commit a launch records comes from the checkout, so it is only asked for when there is
    // one: with no checkout the git read would run in whatever directory the daemon sits in and
    // could answer with an unrelated repository's HEAD.
    DaemonProjectContext context =
        new DaemonProjectContext(
            projectId, repoName, () -> "", () -> provisioned ? ProjectDescriber.head() : "");
    CommandStore store = new CommandStore();
    CommandLogService logs = new CommandLogService(store, null);
    CommandLifecycleService lifecycle =
        new CommandLifecycleService(store, () -> nudge(ChangeTopic.COMMANDS));
    CommandRegistry commandRegistry = new CommandRegistry(WORKSPACE_DIR.toPath(), termGraceMs);
    commands = commandRegistry;
    CommandService commandService =
        new CommandService(
            store,
            commandRegistry,
            lifecycle,
            logs,
            context,
            // Declared actions lose every daemon secret, like RunCommand (DaemonSecrets).
            DaemonSecrets.actions(new NoDeclaredActions()));
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
    // domain is an @ConfigProperty field, so CDI always hands it an Optional (possibly empty); the
    // null guard is only for a ControlSocket a test built with `new` and never ran through CDI —
    // none reach wireAgents today, but a null here would otherwise surface as an NPE far from its
    // cause.
    Optional<String> domainConfig = domain == null ? Optional.empty() : domain;
    // Started once, here, so the first launch's footer links are not the one that pays for the
    // first fetch — see WorkLinkProjects#start(). The bearer supplier hands it the RAW project
    // token: WorkLinkProjects prepends its own "Bearer " scheme, where bearer() below carries that
    // scheme already for the control socket's own header (qits-1204).
    WorkLinkProjects workLinkProjects =
        new WorkLinkProjects(domainConfig.orElse(null), this::rawProjectToken).start();
    DaemonAgentDefaults defaults =
        new DaemonAgentDefaults(
            agentDefaultType,
            agentActivityTrackingEnabled,
            surfaceConfigurations,
            DaemonAgentDefaults.factsOf(projectId, repoName),
            domainConfig,
            workLinkProjects);
    // Every probe and preflight child sees every daemon secret blank (DaemonSecrets).
    ProcessRunner processes = DaemonSecrets.processes(new LocalProcessExecutor());
    AgentTranscriptService transcripts =
        new AgentTranscriptService(
            store, logs, agentSessionStore, claudeMount, () -> nudge(ChangeTopic.COMMANDS));
    AgentTranscriptTailService tail =
        new AgentTranscriptTailService(transcripts, logs, transcriptTailPollMs);
    tail.start();
    this.transcriptTail = tail;
    AgentAuthStatus authStatus = new AgentAuthStatus(processes, claudeMount, WORKSPACE_DIR.toPath());
    // Every session spawned through it — the sign-in terminal included, which takes no overlay —
    // sees every daemon secret blank unless its launch decided otherwise (DaemonSecrets).
    AgentCommands agentCommands =
        DaemonSecrets.commands(
            new CommandsAgentCommands(commandService, commandRegistry, store));
    AgentLaunchService launch =
        new AgentLaunchService(
            agentCommands,
            authStatus,
            transcripts,
            tail,
            defaults,
            // One platform server, qits. See ProjectMcpServers.
            new ProjectMcpServers(platformMcpUrl, token),
            context,
            claudeMount,
            hooksPort,
            // "for this project", not the library's "for this workspace". The two daemons say
            // different nouns on purpose and stay divergent; the library made the sentence an
            // argument rather than picking a winner.
            TASK_PROMPT_BOOTSTRAP);
    // The webhook was created and started in start(), independent of (and possibly before)
    // provisioning finishes here — wire it up now so a hook that fires from this point on can drive
    // an interactive rename. Null-checked locally: hooks is volatile and @PreDestroy#stop() can race
    // a hook arriving during shutdown.
    HookWebhook h = hooks;
    if (h != null) {
      h.setAgentLaunch(launch);
    }
    // The launch inherits this process's environment, CLAUDE_CONFIG_DIR included.
    ClaudeTokenPreflight preflight =
        ClaudeTokenPreflight.of(
            processes,
            claudeMount,
            System.getenv("CLAUDE_CONFIG_DIR"),
            Path.of(System.getProperty("java.io.tmpdir")));
    DeliveryKeeper.Settings deliverySettings =
        DeliveryKeeper.Settings.parse(delivery, deliveryToken, deliveryTokenSubject);
    FrontDeskCredential frontDeskCredential =
        new FrontDeskCredential(deliverySettings, sessionCredentials, projectId);
    FrontDeskKeeper keeper =
        new FrontDeskKeeper(
            FrontDeskKeeper.Lifecycle.parse(lifecycle),
            frontDeskCredential,
            FrontDeskKeeper.Desk.of(launch, store, commandRegistry, claudeMount, preflight),
            SessionKeeper.Timing.DEFAULT,
            SessionKeeper.Scheduler.worker(),
            System::currentTimeMillis,
            this::send,
            // On the claude volume: a restarted container resumes a desk qits could not record.
            FrontDeskSessionRecord.of(claudeMount, projectId));
    frontDesk = keeper;
    // Every other session key qits asks for (qits-770), on a worker of its own; the front desk's
    // ladder stays on the keeper's.
    SessionKeeper multiplex =
        new SessionKeeper(
            keeper,
            sessionCredentials,
            SessionKeeper.Desk.of(launch, store, commandRegistry, claudeMount, preflight),
            SessionKeeper.Timing.DEFAULT,
            SessionKeeper.Scheduler.worker("projects-daemon-sessions"),
            System::currentTimeMillis,
            this::send);
    sessionKeeper = multiplex;
    Sessions held = lastSessions;
    if (held != null) {
      multiplex.apply(held);
    }
    DeliveryKeeper.Session deskSession =
        DeliveryKeeper.Session.of(
            store,
            commandRegistry,
            agentCommands,
            commandId -> {
              HookWebhook webhook = hooks;
              return webhook == null ? Optional.empty() : webhook.lastState(commandId);
            });
    // Turns qits types into the desk (qits-1572) — the only writer into it now that nothing types
    // on a schedule (qits-1597). It hears the webhook's activity to flush its queue on IDLE.
    FrontDeskInbox inbox =
        new FrontDeskInbox(
            keeper::keptCommandId,
            deskSession,
            commandId ->
                logs.log(commandId, null, LogChannel.TRANSCRIPT).stream()
                    .map(CommandLogLine::content)
                    .toList(),
            SessionKeeper.Scheduler.worker(),
            System::currentTimeMillis);
    frontDeskInbox = inbox;
    // A resumed desk is told it was interrupted, as one turn the inbox types once it is idle.
    keeper.promptResumesTo(inbox::prompt);
    if (h != null) {
      h.setActivityListener(inbox::onActivity);
    }
    projectsApi.wireFrontDesk(inbox);
    projectsApi.wireSessions(sessionCredentials);
    projectsApi.wireSessionKeys(multiplex::sessionKeyOf);
    projectsApi.wireAgents(
        launch,
        new AgentSessionQueryService(store, agentSessionStore),
        defaults,
        capabilities(processes, authStatus),
        // Null when constructed directly rather than by CDI, which is how the tests here build it.
        imageVersion == null ? "" : imageVersion.map(String::trim).orElse(""),
        reportedBy());
  }

  /**
   * The bootstrap turn a {@code deliverTaskPrompt} launch is seeded with. A <em>project</em>'s task
   * prompt: this container serves one project and its composed runs are drafted against that
   * project's plan. qits-workspace-daemon says "workspace" in the same sentence, and the difference
   * is deliberate — the library carries the wording as a constructor argument for exactly that
   * reason.
   */
  static final String TASK_PROMPT_BOOTSTRAP =
      "Read the work item for this project with the qits MCP server's work_details tool, then"
          + " implement what it describes.";

  /**
   * What the harnesses in this container's image can be configured with — probed <b>once, here, at
   * boot</b>, and served from {@code GET /agents/available} for the host to cache behind the
   * editor's model and effort dropdowns.
   *
   * <p>Once and at boot because each report spawns a process or two: probing per request would put
   * that on the path of every editor page load, and probing from the editor is impossible anyway —
   * the binaries live in the image and the editor is a platform-wide route with no container in
   * front of it.
   *
   * <p><b>A probe that fails must not stop the daemon starting.</b> The library already guarantees
   * that per harness (a missing binary yields the shipped fallback, flagged); the catch here is for
   * everything around it, because an empty capability array costs the editor a stale dropdown and
   * nothing else, while an exception out of the wiring would cost the container its whole agent
   * surface.
   */
  private java.util.List<HarnessCapabilities> capabilities(
      ProcessRunner processes, AgentAuthStatus authStatus) {
    try {
      return new HarnessCapabilityService(
              processes, authStatus, claudeMount, WORKSPACE_DIR.toPath())
          .reportAll();
    } catch (RuntimeException e) {
      LOG.warnf("Could not report harness capabilities: %s", e.getMessage());
      return java.util.List.of();
    }
  }

  /**
   * Who took the report. The same spelling qits-projects fills in when a daemon names nothing, so
   * the catalogue keys a container's reports the same way whichever side named it.
   */
  private String reportedBy() {
    return projectId == null || projectId.isBlank() ? "" : "project-agent/" + projectId;
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
    connect(uri, attempt, bearer());
  }

  private void connect(URI uri, int attempt, Optional<String> authorization) {
    client
        .connect(dialOptions(uri, authorization))
        .onSuccess(this::onConnected)
        .onFailure(
            t -> {
              LOG.debugf("projects-daemon dial-home failed (attempt %d): %s", attempt, t.getMessage());
              reconnect(attempt);
            });
  }

  /**
   * The control socket's connect options: TLS and the default port follow the url's scheme ({@link
   * DaemonDial}), and {@code authorization} rides as the {@code Authorization} header.
   */
  static WebSocketConnectOptions dialOptions(URI uri, Optional<String> authorization) {
    return DaemonDial.connectOptions(uri, authorization);
  }

  /** The project token as a header value, or empty when this container was handed none. */
  Optional<String> bearer() {
    return token == null
        ? Optional.empty()
        : token.filter(value -> !value.isBlank()).map(value -> "Bearer " + value.trim());
  }

  /**
   * The project token WITHOUT the {@code Bearer } scheme {@link #bearer()} adds for the control
   * socket's own header — {@link WorkLinkProjects} prepends that scheme itself, so handing it
   * {@link #bearer()} verbatim would double it. Asked for on every fetch, same as {@link #bearer()}
   * is for every dial, so a token that is rotated or only appears later is picked up without a
   * restart (qits-1204).
   */
  Optional<String> rawProjectToken() {
    return bearer()
        .map(value -> value.startsWith("Bearer ") ? value.substring("Bearer ".length()) : value);
  }

  /**
   * What every tunnel dial-back carries: the same {@link #bearer()} the control socket dials home
   * with, so an internal dial-back presents exactly what the socket it followed from presented —
   * the project token, or nothing on the anonymous clone-alone/developer topology. Nothing is
   * minted, by this method or by {@link #bearer()}: the dial-back follows an {@code OpenStream}
   * that arrived over a socket already dialled with this same credential.
   */
  Optional<String> dialBackAuthorization() {
    return bearer();
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
      send(hello(), ws);
      send(new DaemonLog("INFO", "projects-daemon online for project " + projectId), ws);
      flushPending(ws);
      socket = ws;
    }
    // The hello is on the wire: a capability-2 qits answers with sessionCredentials, so the grace
    // window restarts from here (the first connection only).
    sessionCredentials.connectionOpened();
    // Reconnect adoption: re-report the last known agent activity per tracked command, so a qits
    // restart rebuilds the live "cooking / idle / waiting" projection from the daemon's retained
    // state. Off the socket-publish path (after `socket = ws`) so the re-report writes directly.
    HookWebhook h = hooks;
    if (h != null) {
      workers.execute(h::reportCurrent);
    }
    // And where every session key stands, for a qits that restarted (nothing before its first
    // sessions frame); its answering frame on this hello is reported again once applied.
    SessionKeeper sessions = sessionKeeper;
    if (sessions != null) {
      sessions.reportCurrent();
    }
    LOG.infof("projects-daemon control socket established for project %s", projectId);
  }

  /** What this daemon announces on every connection, its capability version included. */
  Hello hello() {
    return new Hello(
        projectId,
        repoName,
        DaemonProtocol.CAPABILITY_VERSION,
        buildVersionConfig == null ? null : buildVersionConfig.orElse(null),
        buildTimeConfig == null ? null : buildTimeConfig.orElse(null));
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

  /** Decode and dispatch one frame from qits. Package-private so a test can hand it one. */
  void onFrame(String json) {
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
      case SessionCredentials frame ->
          // Replace semantics, held in memory only; the store logs keys and subjects, never tokens.
          sessionCredentials.replace(frame);
      case Sessions frame -> {
        // The desired list, replaced whole (qits-770). Held for a keeper not wired yet; a wired one
        // only schedules, so nothing here blocks the event loop.
        lastSessions = frame;
        SessionKeeper keeper = sessionKeeper;
        if (keeper != null) {
          keeper.apply(frame);
        }
      }
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
    // The keepers stop supervising before the agents are terminated, so the SIGTERM below is not
    // read as a crash to relaunch from.
    FrontDeskKeeper keeper = frontDesk;
    if (keeper != null) {
      keeper.stop();
    }
    SessionKeeper sessions = sessionKeeper;
    if (sessions != null) {
      sessions.stop();
    }
    FrontDeskInbox inbox = frontDeskInbox;
    if (inbox != null) {
      inbox.stop();
    }
    // Agents go first, before anything else is torn down — see stopAgents for why.
    stopAgents(commands);
    // Then the checkout follower, because it owns a child process: stopping supervision before the
    // worker pool goes means no restart can be scheduled into a half-torn-down daemon.
    CheckoutFollower follower = checkoutFollower;
    if (follower != null) {
      follower.stop();
    }
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

  /**
   * Terminate every live agent, ahead of everything else {@link #stop} tears down.
   *
   * <p>{@code claude --remote-control} archives its claude.ai session only on {@code SIGTERM}. Under
   * tini (this container's PID 1) a plain {@code docker stop} reaches only this daemon: each agent
   * is launched {@code setsid}'d into its own process group (see {@code CommandRegistry}'s javadoc),
   * tini does not forward a signal to a group it did not start directly, and once this process exits
   * the kernel {@code SIGKILL}s whatever is left in the container. {@link CommandRegistry#terminateAll}
   * is this daemon's only chance to hand every live agent a {@code SIGTERM} at all, so it has to run
   * before anything else — while the control socket, the network and the worker pool this method's
   * caller is about to close are still up, which is what lets an agent's exit callback (the
   * transcript sweep, the status update) report on its way out. Its grace ({@code termGraceMs},
   * default 5s) shares the same config key as {@link CheckoutFollower#stop}'s own wait, so the two
   * together already spend the full default grace twice — leave room under docker's 10s stop budget
   * when raising either.
   *
   * <p>Package-private and static so it can be exercised without standing up the rest of {@link
   * ControlSocket}.
   */
  static void stopAgents(CommandRegistry commands) {
    if (commands != null) {
      commands.terminateAll();
    }
  }
}
