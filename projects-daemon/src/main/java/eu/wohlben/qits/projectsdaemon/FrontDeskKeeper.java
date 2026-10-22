package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentNotSignedInException;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

/**
 * Keeps this project's front desk — one interactive {@code project.work} session, which the surface
 * seed launches as {@code claude --remote-control '🐞 qits front desk'} — running for the life of the
 * container, when the container was created {@link Lifecycle#ALWAYS_ON} or when delivery is on
 * ({@link FrontDeskCredential#deliveryOn()}; it launches for the loop once it has the rights to run
 * it, {@link FrontDeskCredential#delivering()}).
 *
 * <p>Started by {@link ControlSocket} once the boot provision succeeded <b>and</b> the agent surface
 * was wired; a container whose clone failed has nothing to steer a desk at, and one whose agents
 * stayed unwired has nothing to launch with. {@link Lifecycle#ON_DEMAND} — the default — starts
 * nothing at all, which keeps a container that was not asked for a desk byte for byte what it was;
 * unless delivery is on, in which case the desk is kept whatever the lifecycle says.
 *
 * <ul>
 *   <li><b>Launch</b> only when no live {@code project.work} session exists. One the SPA started
 *       counts: the desk is the surface, not whoever opened it, and two of them would be two remote
 *       control threads for one desk. <b>Not while delivering:</b> then the desk is the session
 *       that holds the delivery rights and takes the delivery ticks, which a session a person
 *       started from the desk panel never is (it runs on {@code desk-agents}). So the keeper tracks the command it launched
 *       ({@link #keptCommandId()}), counts only that one as live, and launches its own beside
 *       anybody else's.
 *   <li><b>Supervise:</b> when the keeper's session exits, it is relaunched after a backoff that
 *       starts at 5 s and doubles to 5 min, and resets once a session stayed up for 10 min. It never
 *       gives up: a desk that stops coming back is the failure this class exists to remove.
 *   <li><b>Resume:</b> a relaunch continues the previous keeper session while its transcript still
 *       exists, so the remote-control thread survives a crash of its process.
 *   <li><b>Fresh token:</b> before every launch the claude.ai access token on the credential
 *       volume is refreshed through {@code claude} itself when it is missing, unreadable or within
 *       5 min of expiry ({@link ClaudeTokenPreflight}), because Remote Control connects only at
 *       startup and never retries. A failed preflight is logged and the launch goes ahead.
 *   <li><b>Credential change</b> (qits-1482): in per-session mode the keeper re-reads its {@code
 *       front-desk} credential on every poll. When qits re-issued it — the loop toggled, a token
 *       rotated — the kept session is terminated ({@link Desk#terminate}) and relaunched at once,
 *       resuming its transcript, because a session's environment and MCP bearer are fixed at launch.
 *       Legacy mode never restarts: its credential is the environment's and cannot change.
 *   <li><b>No credential:</b> inside the grace window for qits' first {@code sessionCredentials}
 *       the keeper waits on its poll, quietly; in per-session mode without a {@code front-desk}
 *       credential it fails closed — a WARN and the backoff ladder, never another credential.
 *   <li><b>Not signed in</b> is not a crash to back off from: it says so once per attempt as a
 *       {@link DaemonLog} WARN and asks again every 5 min. It never opens a sign-in terminal —
 *       signing in is a person's deliberate step on the runners page.
 * </ul>
 *
 * <p>Exit is observed by polling the command store, because a launch's exit listener belongs to the
 * harness library (its transcript sweep) and is not offered to a caller. A map lookup every couple of
 * seconds is the whole cost.
 *
 * <p>Every timing and every collaborator is a constructor argument ({@link Timing}, {@link Desk},
 * {@link Scheduler}, the clock), so a test drives the ladder deterministically without a real
 * harness or a real wait.
 *
 * <p>The launch's credential is {@link FrontDeskCredential}'s to decide, asked before every launch:
 * the {@code front-desk} session credential once qits sent credentials; before that, the delivery
 * token while delivering and the container's own token otherwise. The delivery loop itself is {@link
 * DeliveryKeeper}'s tick, typed into {@link #keptCommandId()}.
 */
final class FrontDeskKeeper {

  private static final Logger LOG = Logger.getLogger(FrontDeskKeeper.class);

  /** The surface the desk runs as. */
  static final AgentSurface SURFACE = AgentSurface.PROJECT_WORK;

  /** How the desk is named in the logs and {@link DaemonLog}s. */
  private static final String LABEL = "front desk";

  /** Whether this container keeps a front desk running ({@code QITS_PROJECTS_DAEMON_LIFECYCLE}). */
  enum Lifecycle {
    ALWAYS_ON,
    ON_DEMAND;

    /**
     * The configured value; unset or blank is {@link #ON_DEMAND}, and anything else unknown logs
     * one WARN and counts as {@link #ON_DEMAND} — a typo must not start a desk nobody asked for.
     */
    static Lifecycle parse(Optional<String> configured) {
      if (configured == null || configured.isEmpty() || configured.get().isBlank()) {
        return ON_DEMAND;
      }
      String value = configured.get().trim();
      try {
        return valueOf(value.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        LOG.warnf(
            "Unknown qits.projects-daemon.lifecycle '%s' (expected ALWAYS_ON or ON_DEMAND);"
                + " treating it as ON_DEMAND.",
            value);
        return ON_DEMAND;
      }
    }
  }

  /** The keeper's ladder, in milliseconds. */
  record Timing(
      long initialBackoffMs,
      long maxBackoffMs,
      long resetAfterMs,
      long notSignedInRetryMs,
      long pollMs) {

    /** This daemon's policy: 5 s doubling to 5 min, reset after 10 min up, sign-in every 5 min. */
    static final Timing DEFAULT = new Timing(5_000, 300_000, 600_000, 300_000, 2_000);
  }

  /** What the keeper needs of the agent surface, behind one seam a test can fake. */
  interface Desk {

    /** Launch a session; may throw {@link AgentNotSignedInException}. */
    Command launch(AgentLaunchRequest request);

    /** The command as the store holds it now, or empty once it is gone. */
    Optional<Command> find(String commandId);

    /**
     * Whether any live {@code project.work} session exists, whoever started it. Asked only while
     * not delivering.
     */
    boolean liveSessionExists();

    /** Whether {@code sessionId}'s transcript is still on the credential volume. */
    boolean transcriptExists(Command command, String sessionId);

    /**
     * Terminate {@code commandId} (SIGTERM, then the registry's grace and escalation) — the first
     * half of a restart onto a changed credential. False when it is not running.
     */
    default boolean terminate(String commandId) {
      return false;
    }

    /**
     * Make sure the claude.ai access token is fresh before a launch (qits-1102); see {@link
     * ClaudeTokenPreflight}. Must not throw, but the keeper launches regardless if it does.
     */
    default void refreshCredentials() {}

    /** The production {@code project.work} desk over this daemon's launch service and store. */
    static Desk of(
        AgentLaunchService launch,
        CommandStore store,
        CommandRegistry registry,
        String claudeMount,
        ClaudeTokenPreflight preflight) {
      return new Desk() {
        @Override
        public void refreshCredentials() {
          preflight.ensureFresh();
        }

        @Override
        public Command launch(AgentLaunchRequest request) {
          return launch.launch(request);
        }

        @Override
        public Optional<Command> find(String commandId) {
          return store.find(commandId);
        }

        @Override
        public boolean liveSessionExists() {
          return store.findByStatus(CommandStatus.RUNNING).stream()
              .anyMatch(
                  command ->
                      SURFACE.key().equals(command.agentSurface())
                          && registry.isRunning(command.id()));
        }

        @Override
        public boolean transcriptExists(Command command, String sessionId) {
          return FrontDeskKeeper.transcriptExists(claudeMount, command, sessionId);
        }

        @Override
        public boolean terminate(String commandId) {
          return registry.terminate(commandId);
        }
      };
    }
  }

  /** Runs a step later, off the caller's thread. */
  interface Scheduler {
    void schedule(Runnable step, long delayMs);

    void shutdown();

    /** One daemon worker thread, so the keeper never blocks the event loop or the boot worker. */
    static Scheduler worker() {
      ScheduledExecutorService executor =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "projects-daemon-front-desk");
                thread.setDaemon(true);
                return thread;
              });
      return new Scheduler() {
        @Override
        public void schedule(Runnable step, long delayMs) {
          if (!executor.isShutdown()) {
            executor.schedule(step, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
          }
        }

        @Override
        public void shutdown() {
          executor.shutdownNow();
        }
      };
    }
  }

  private final Lifecycle lifecycle;
  private final FrontDeskCredential credential;
  private final Desk desk;
  private final Timing timing;
  private final Scheduler scheduler;
  private final LongSupplier clock;
  private final Consumer<DaemonMessage> send;

  private volatile boolean started;
  private volatile boolean stopped;

  // Written only on the scheduler's single thread; volatile because the delivery tick reads it.
  private volatile String commandId;

  // Touched only on the scheduler's single thread (and start(), before the first step runs).
  private Map<String, String> launchedOverlay;
  private boolean restarting;
  private boolean waitingLogged;
  private long launchedAt;
  private long backoffMs;
  private String previousSessionId;
  private Command previousCommand;
  private Integer pendingExitCode;

  FrontDeskKeeper(
      Lifecycle lifecycle,
      Desk desk,
      Timing timing,
      Scheduler scheduler,
      LongSupplier clock,
      Consumer<DaemonMessage> send) {
    this(lifecycle, FrontDeskCredential.SCRUBBED, desk, timing, scheduler, clock, send);
  }

  FrontDeskKeeper(
      Lifecycle lifecycle,
      FrontDeskCredential credential,
      Desk desk,
      Timing timing,
      Scheduler scheduler,
      LongSupplier clock,
      Consumer<DaemonMessage> send) {
    this.lifecycle = lifecycle;
    this.credential = credential;
    this.desk = desk;
    this.timing = timing;
    this.scheduler = scheduler;
    this.clock = clock;
    this.send = send;
    this.backoffMs = timing.initialBackoffMs();
  }

  /**
   * Begin keeping the desk, once; a no-op unless {@link Lifecycle#ALWAYS_ON} or delivery is on —
   * the delivery loop needs a desk to run in, so delivery keeps one even {@link
   * Lifecycle#ON_DEMAND}. Whether the loop can run yet (its rights may arrive with qits' first
   * {@code sessionCredentials}) is asked again before every launch.
   */
  void start() {
    boolean delivering = credential.deliveryOn();
    if (lifecycle != Lifecycle.ALWAYS_ON && !delivering) {
      LOG.debug("Lifecycle ON_DEMAND: no front desk is kept; the SPA launches one when asked.");
      return;
    }
    if (started || stopped) {
      return;
    }
    started = true;
    if (delivering) {
      LOG.infof(
          "Keeping the %s %s running with the desk's delivery rights; it runs the delivery loop.",
          SURFACE.key(), LABEL);
    } else {
      LOG.infof("Keeping the %s %s running.", SURFACE.key(), LABEL);
    }
    scheduler.schedule(this::attempt, 0);
  }

  /**
   * The command the keeper launched, while it is the one being kept — what the delivery tick types
   * into. Empty before the first launch and between an exit and the relaunch. It names the
   * keeper's own session only, never one a person started: a person's session holds no delivery
   * token.
   */
  Optional<String> keptCommandId() {
    return Optional.ofNullable(commandId);
  }

  /** Stop supervising; the live session itself is left to {@code CommandRegistry.terminateAll()}. */
  void stop() {
    stopped = true;
    scheduler.shutdown();
  }

  /**
   * Launch the desk, unless one is already live. While delivering only the keeper's own session
   * counts, and this runs only when there is none, so it launches whoever else's is up.
   */
  private void attempt() {
    if (stopped) {
      return;
    }
    if (lifecycle != Lifecycle.ALWAYS_ON && !credential.delivering()) {
      // Kept only for the delivery loop, and it has no rights to run with yet: no delivery token
      // and no session credentials from qits (an older qits without a token never sends any). This
      // is what "no token, no loop" looked like before, minus the desk; ask again on the poll.
      if (!waitingLogged) {
        waitingLogged = true;
        LOG.infof("The %s waits for delivery rights before it launches.", LABEL);
      }
      scheduler.schedule(this::attempt, timing.pollMs());
      return;
    }
    try {
      if (!credential.delivering() && desk.liveSessionExists()) {
        // Somebody else's desk (the SPA's) is up. Watch it; launch when it is gone.
        scheduler.schedule(this::poll, timing.pollMs());
        return;
      }
    } catch (RuntimeException e) {
      LOG.warnf("Could not ask for a live %s session: %s", LABEL, e.getMessage());
      scheduler.schedule(this::attempt, timing.pollMs());
      return;
    }
    Map<String, String> overlay;
    try {
      overlay = credential.launchOverlay();
    } catch (SessionCredentialStore.AwaitingSessionCredentialsException e) {
      // Not yet, rather than no: qits has had no chance to send this desk its credentials since the
      // daemon started or the socket connected. Ask again shortly, without spending a backoff step
      // and without a WARN — a launch now would race the frame and run on the wrong credential.
      LOG.debugf("%s waits for session credentials ('%s')", LABEL, e.key());
      scheduler.schedule(this::attempt, timing.pollMs());
      return;
    } catch (SessionCredentialStore.MissingSessionCredentialException e) {
      // Fail closed (qits-1482): no credential for this session's key means no session, never a
      // session on another credential. qits may issue one any moment, so ask again on the ladder;
      // the previous session stays resumable, since nothing about it was refused.
      String message = LABEL + " not launched: no session credential for '" + e.key() + "'";
      LOG.warn(message);
      send.accept(new DaemonLog("WARN", message));
      scheduler.schedule(this::attempt, nextBackoff());
      return;
    }
    try {
      // Remote Control connects once, at startup, on whatever token the volume holds; a stale
      // one leaves the desk at "Remote Control failed · /login" for good (qits-1102).
      desk.refreshCredentials();
    } catch (RuntimeException e) {
      LOG.warnf(
          "%s: the claude.ai token preflight failed (%s); launching anyway",
          LABEL, e.getClass().getSimpleName());
    }
    String resume = resumableSession();
    Command command;
    try {
      command =
          desk.launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  SURFACE,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  resume,
                  false,
                  false,
                  null,
                  null,
                  overlay));
    } catch (AgentNotSignedInException e) {
      String message = LABEL + " not signed in: run the login command on the runners page";
      LOG.warn(message);
      send.accept(new DaemonLog("WARN", message));
      scheduler.schedule(this::attempt, timing.notSignedInRetryMs());
      return;
    } catch (RuntimeException e) {
      // A resume the harness refuses must not wedge the desk on that id: the next try is fresh.
      LOG.warnf("The %s did not launch: %s", LABEL, e.getMessage());
      previousSessionId = null;
      previousCommand = null;
      scheduler.schedule(this::attempt, nextBackoff());
      return;
    }
    commandId = command.id();
    launchedOverlay = overlay;
    launchedAt = clock.getAsLong();
    if (restarting) {
      restarting = false;
      pendingExitCode = null;
      String message = LABEL + " restarted on its new session credential";
      LOG.info(message);
      send.accept(new DaemonLog("INFO", message));
    } else if (pendingExitCode != null) {
      String message = LABEL + " relaunched (exit " + pendingExitCode + ")";
      LOG.info(message);
      send.accept(new DaemonLog("INFO", message));
      pendingExitCode = null;
    } else {
      LOG.infof("%s launched (command %s)", LABEL, commandId);
    }
    scheduler.schedule(this::poll, timing.pollMs());
  }

  /** Watch the keeper's session (or somebody else's) and relaunch once it is gone. */
  private void poll() {
    if (stopped) {
      return;
    }
    if (commandId == null) {
      // Watching a session the keeper did not start.
      attempt();
      return;
    }
    Optional<Command> current;
    try {
      current = desk.find(commandId);
    } catch (RuntimeException e) {
      scheduler.schedule(this::poll, timing.pollMs());
      return;
    }
    long now = clock.getAsLong();
    if (current.isPresent() && current.get().status() == CommandStatus.RUNNING) {
      if (now - launchedAt >= timing.resetAfterMs()) {
        backoffMs = timing.initialBackoffMs();
      }
      if (!restarting && credentialChanged()) {
        // qits re-issued the front desk's credential (the loop toggled, a token rotated): the
        // session's environment and MCP bearer are fixed at launch, so it is restarted onto the new
        // one, resuming its transcript. SIGTERM first, so remote control archives cleanly.
        restarting = true;
        LOG.infof("%s: its session credential changed; restarting it (command %s)", LABEL, commandId);
        try {
          desk.terminate(commandId);
        } catch (RuntimeException e) {
          LOG.warnf("Could not terminate the %s for its restart: %s", LABEL, e.getMessage());
        }
      }
      scheduler.schedule(this::poll, timing.pollMs());
      return;
    }
    // Exited (or evicted from the store, which only a long-dead command is).
    Command exited = current.orElse(null);
    if (now - launchedAt >= timing.resetAfterMs()) {
      backoffMs = timing.initialBackoffMs();
    }
    if (exited != null) {
      AgentSessionRef session = exited.currentSession();
      if (session != null && session.sessionId() != null) {
        previousSessionId = session.sessionId();
        previousCommand = exited;
      }
    }
    Integer code = exited == null ? null : exited.exitCode();
    pendingExitCode = code == null ? -1 : code;
    commandId = null;
    // A restart onto a new credential is not a crash: relaunch at once, without a backoff step.
    long delay = restarting ? 0 : nextBackoff();
    LOG.debugf(
        "%s exited (exit %d); relaunching in %d ms",
        LABEL,
        (Object) pendingExitCode,
        (Object) delay);
    scheduler.schedule(this::attempt, delay);
  }

  /**
   * Whether the credential the kept session was launched with is no longer the one it would be
   * launched with now. Only ever true in per-session mode — a legacy credential is read from the
   * environment and cannot change — and only for a credential that is there: a key qits withdrew
   * leaves the running session alone (its next launch fails closed), as does the grace window.
   */
  private boolean credentialChanged() {
    if (launchedOverlay == null || !credential.perSessionMode()) {
      return false;
    }
    try {
      return !credential.launchOverlay().equals(launchedOverlay);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The current backoff, doubling the next one up to the cap. */
  private long nextBackoff() {
    long delay = backoffMs;
    backoffMs = Math.min(timing.maxBackoffMs(), Math.max(1, backoffMs) * 2);
    return delay;
  }

  /** The previous keeper session's id while its transcript exists, otherwise null. */
  private String resumableSession() {
    if (previousSessionId == null) {
      return null;
    }
    try {
      if (desk.transcriptExists(previousCommand, previousSessionId)) {
        return previousSessionId;
      }
    } catch (RuntimeException e) {
      LOG.debugf("Could not look for transcript %s: %s", previousSessionId, e.getMessage());
    }
    previousSessionId = null;
    previousCommand = null;
    return null;
  }

  /**
   * Whether {@code sessionId}'s transcript is on the credential volume: the hook-reported path when
   * it sits under the harness's config dir ({@code $CLAUDE_CONFIG_DIR}, i.e. {@code
   * <claudeMount>/.claude}), else a search of that dir's {@code projects/} for {@code
   * <sessionId>.jsonl} — the same resolution the harness's transcript import uses. Kimi keeps a
   * {@code sessions/<workDirKey>/<sessionId>} directory instead.
   */
  static boolean transcriptExists(String claudeMount, Command command, String sessionId) {
    if (claudeMount == null || claudeMount.isBlank() || sessionId == null) {
      return false;
    }
    boolean kimi = command != null && "KIMI".equalsIgnoreCase(command.agentType());
    Path configDir = Path.of(claudeMount, kimi ? ".kimi-code" : ".claude").normalize();
    if (command != null) {
      for (AgentSessionRef ref : command.agentSessions()) {
        if (sessionId.equals(ref.sessionId())
            && ref.transcriptPath() != null
            && !ref.transcriptPath().isBlank()) {
          Path reported = Path.of(ref.transcriptPath()).normalize();
          if (reported.startsWith(configDir) && Files.isRegularFile(reported)) {
            return true;
          }
        }
      }
    }
    Path root = configDir.resolve(kimi ? "sessions" : "projects");
    if (!Files.isDirectory(root)) {
      return false;
    }
    String name = kimi ? sessionId : sessionId + ".jsonl";
    try (Stream<Path> walk = Files.walk(root, 3)) {
      return walk.anyMatch(
          path ->
              path.getFileName().toString().equals(name)
                  && (kimi ? Files.isDirectory(path) : Files.isRegularFile(path)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
