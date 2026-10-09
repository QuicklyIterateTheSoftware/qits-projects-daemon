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
 * container, when the container was created {@link Lifecycle#ALWAYS_ON}.
 *
 * <p>Started by {@link ControlSocket} once the boot provision succeeded <b>and</b> the agent surface
 * was wired; a container whose clone failed has nothing to steer a desk at, and one whose agents
 * stayed unwired has nothing to launch with. {@link Lifecycle#ON_DEMAND} — the default — starts
 * nothing at all, which keeps a container that was not asked for a desk byte for byte what it was.
 *
 * <ul>
 *   <li><b>Launch</b> only when no live {@code project.work} session exists. One the SPA started
 *       counts: the desk is the surface, not whoever opened it, and two of them would be two remote
 *       control threads for one desk.
 *   <li><b>Supervise:</b> when the keeper's session exits, it is relaunched after a backoff that
 *       starts at 5 s and doubles to 5 min, and resets once a session stayed up for 10 min. It never
 *       gives up: a desk that stops coming back is the failure this class exists to remove.
 *   <li><b>Resume:</b> a relaunch continues the previous keeper session while its transcript still
 *       exists, so the remote-control thread survives a crash of its process.
 *   <li><b>Fresh token:</b> before every launch the claude.ai access token on the credential
 *       volume is refreshed through {@code claude} itself when it is missing, unreadable or within
 *       5 min of expiry ({@link ClaudeTokenPreflight}), because Remote Control connects only at
 *       startup and never retries. A failed preflight is logged and the launch goes ahead.
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
 */
final class FrontDeskKeeper {

  private static final Logger LOG = Logger.getLogger(FrontDeskKeeper.class);

  /** The surface the desk runs as; the one surface a project container serves. */
  static final AgentSurface SURFACE = AgentSurface.PROJECT_WORK;

  static final String NOT_SIGNED_IN_MESSAGE =
      "front desk not signed in: run the login command on the runners page";

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

    /** Whether any live {@code project.work} session exists, whoever started it. */
    boolean liveSessionExists();

    /** Whether {@code sessionId}'s transcript is still on the credential volume. */
    boolean transcriptExists(Command command, String sessionId);

    /**
     * Make sure the claude.ai access token is fresh before a launch (qits-1102); see {@link
     * ClaudeTokenPreflight}. Must not throw, but the keeper launches regardless if it does.
     */
    default void refreshCredentials() {}

    /** The production desk over this daemon's launch service, store and registry. */
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
  private final Desk desk;
  private final Timing timing;
  private final Scheduler scheduler;
  private final LongSupplier clock;
  private final Consumer<DaemonMessage> send;

  private volatile boolean started;
  private volatile boolean stopped;

  // Touched only on the scheduler's single thread (and start(), before the first step runs).
  private String commandId;
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
    this.lifecycle = lifecycle;
    this.desk = desk;
    this.timing = timing;
    this.scheduler = scheduler;
    this.clock = clock;
    this.send = send;
    this.backoffMs = timing.initialBackoffMs();
  }

  /** Begin keeping the desk, once; a no-op unless {@link Lifecycle#ALWAYS_ON}. */
  void start() {
    if (lifecycle != Lifecycle.ALWAYS_ON) {
      LOG.debug("Lifecycle ON_DEMAND: no front desk is kept; the SPA launches one when asked.");
      return;
    }
    if (started || stopped) {
      return;
    }
    started = true;
    LOG.info("Lifecycle ALWAYS_ON: keeping the project.work front desk running.");
    scheduler.schedule(this::attempt, 0);
  }

  /** Stop supervising; the live session itself is left to {@code CommandRegistry.terminateAll()}. */
  void stop() {
    stopped = true;
    scheduler.shutdown();
  }

  /** Launch the desk, unless one is already live. */
  private void attempt() {
    if (stopped) {
      return;
    }
    try {
      if (desk.liveSessionExists()) {
        // Somebody else's desk (the SPA's) is up. Watch it; launch when it is gone.
        scheduler.schedule(this::poll, timing.pollMs());
        return;
      }
    } catch (RuntimeException e) {
      LOG.warnf("Could not ask for a live front desk session: %s", e.getMessage());
      scheduler.schedule(this::attempt, timing.pollMs());
      return;
    }
    try {
      // Remote Control connects once, at startup, on whatever token the volume holds; a stale
      // one leaves the desk at "Remote Control failed · /login" for good (qits-1102).
      desk.refreshCredentials();
    } catch (RuntimeException e) {
      LOG.warnf(
          "front desk: the claude.ai token preflight failed (%s); launching anyway",
          e.getClass().getSimpleName());
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
                  null));
    } catch (AgentNotSignedInException e) {
      LOG.warn(NOT_SIGNED_IN_MESSAGE);
      send.accept(new DaemonLog("WARN", NOT_SIGNED_IN_MESSAGE));
      scheduler.schedule(this::attempt, timing.notSignedInRetryMs());
      return;
    } catch (RuntimeException e) {
      // A resume the harness refuses must not wedge the desk on that id: the next try is fresh.
      LOG.warnf("The front desk did not launch: %s", e.getMessage());
      previousSessionId = null;
      previousCommand = null;
      scheduler.schedule(this::attempt, nextBackoff());
      return;
    }
    commandId = command.id();
    launchedAt = clock.getAsLong();
    if (pendingExitCode != null) {
      String message = "front desk relaunched (exit " + pendingExitCode + ")";
      LOG.info(message);
      send.accept(new DaemonLog("INFO", message));
      pendingExitCode = null;
    } else {
      LOG.infof("front desk launched (command %s)", commandId);
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
    long delay = nextBackoff();
    LOG.debugf(
        "front desk exited (exit %d); relaunching in %d ms", (Object) pendingExitCode, (Object) delay);
    scheduler.schedule(this::attempt, delay);
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
