package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.projectsdaemon.SessionKeeper.Scheduler;
import eu.wohlben.qits.projectsdaemon.SessionKeeper.Timing;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *       that holds the delivery rights, which a session a person started from the desk panel never
 *       is (it runs on {@code desk-agents}). So the keeper tracks the command it launched ({@link
 *       #keptCommandId()}), counts only that one as live, and launches its own beside anybody
 *       else's.
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
 * <p><b>The ladder is {@link SessionLadder}</b>, the one every key of the session multiplex climbs
 * (qits-770); this class is the front desk's specialisation of it — what keeps the desk, what holds
 * a launch back, which credential it runs on. <b>Once qits sent a {@code sessions} frame</b> ({@link
 * #desire}) its {@code front-desk} entry replaces {@link Lifecycle} as the reason to keep the desk:
 * {@code RUNNING} keeps it, {@code STOPPED} or absent does not (the kept session is terminated), and
 * delivery on still keeps it either way; the entry's {@code resumeSessionId} is the first resume
 * tried. Every state change is reported to {@link SessionKeeper} ({@link #reportTo}), which puts it
 * in the {@code sessionReport}; before a frame nothing is sent.
 *
 * <p>The launch's credential is {@link FrontDeskCredential}'s to decide, asked before every launch:
 * the {@code front-desk} session credential once qits sent credentials; before that, the delivery
 * token while delivering and the container's own token otherwise. Nothing types the delivery loop
 * into {@link #keptCommandId()} on a schedule (qits-1597): a pass runs when a person prompts the
 * desk, directly or through {@link FrontDeskInbox}.
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

  /**
   * What the keeper needs of the agent surface, behind one seam a test can fake: a ladder's {@link
   * SessionKeeper.Desk}, and whether somebody else's desk session is up.
   */
  interface Desk extends SessionKeeper.Desk {

    /**
     * Whether any live {@code project.work} session exists, whoever started it. Asked only while
     * not delivering.
     */
    boolean liveSessionExists();

    /** The production {@code project.work} desk over this daemon's launch service and store. */
    static Desk of(
        AgentLaunchService launch,
        CommandStore store,
        CommandRegistry registry,
        String claudeMount,
        ClaudeTokenPreflight preflight) {
      SessionKeeper.Desk agents = SessionKeeper.Desk.of(launch, store, registry, claudeMount, preflight);
      return new Desk() {
        @Override
        public void refreshCredentials() {
          agents.refreshCredentials();
        }

        @Override
        public Command launch(AgentLaunchRequest request) {
          return agents.launch(request);
        }

        @Override
        public Optional<Command> find(String commandId) {
          return agents.find(commandId);
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
          return agents.transcriptExists(command, sessionId);
        }

        @Override
        public boolean terminate(String commandId) {
          return agents.terminate(commandId);
        }
      };
    }
  }

  private final Lifecycle lifecycle;
  private final FrontDeskCredential credential;
  private final Desk desk;
  private final Scheduler scheduler;
  private final SessionLadder ladder;

  /** Where the ladder's state changes go; nowhere until {@link SessionKeeper} asks for them. */
  private volatile Consumer<SessionReport.Session> reporter = session -> {};

  /** {@link #start} was called: the checkout and the agent surface are there to launch with. */
  private volatile boolean armed;

  private volatile boolean stopped;

  /** Whether qits sent a {@code sessions} frame, so {@link #desired} — not the lifecycle — decides. */
  private volatile boolean sessionsMode;

  /** The last frame's {@code front-desk} entry; null when it had none. */
  private volatile Sessions.Session desired;

  // Touched only on the scheduler's single thread.
  private boolean waitingLogged;

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
    this.scheduler = scheduler;
    this.ladder =
        new SessionLadder(
            new FrontDesk(), desk, timing, scheduler, clock, send, session -> reporter.accept(session));
  }

  /**
   * Begin keeping the desk, once; a no-op unless it is wanted ({@link #wanted}) — {@link
   * Lifecycle#ALWAYS_ON}, delivery on, or a {@code RUNNING} entry in qits' {@code sessions}. The
   * delivery loop needs a desk to run in, so delivery keeps one even {@link Lifecycle#ON_DEMAND}.
   * Whether the loop can run yet (its rights may arrive with qits' first {@code
   * sessionCredentials}) is asked again before every launch. A desk wanted only later — a frame
   * that asks for it — is started then.
   */
  void start() {
    if (armed || stopped) {
      return;
    }
    armed = true;
    if (!wanted()) {
      LOG.debug("Lifecycle ON_DEMAND: no front desk is kept; the SPA launches one when asked.");
      return;
    }
    if (credential.deliveryOn()) {
      LOG.infof(
          "Keeping the %s %s running with the desk's delivery rights; it delivers when prompted.",
          SURFACE.key(), LABEL);
    } else {
      LOG.infof("Keeping the %s %s running.", SURFACE.key(), LABEL);
    }
    scheduler.schedule(ladder::reconsider, 0);
  }

  /**
   * qits' {@code front-desk} entry of a {@code sessions} frame — null when the frame has none —
   * which from now on decides whether the desk is kept, in place of the lifecycle. Any thread; the
   * change is acted on on the keeper's own.
   */
  void desire(Sessions.Session entry) {
    sessionsMode = true;
    desired = entry;
    if (armed && !stopped) {
      scheduler.schedule(ladder::reconsider, 0);
    }
  }

  /** Where every state change of the desk's ladder is reported. */
  void reportTo(Consumer<SessionReport.Session> listener) {
    this.reporter = listener == null ? session -> {} : listener;
  }

  /**
   * Whether the desk is to be kept: delivery on, or — once qits sent {@code sessions} — a {@code
   * RUNNING} {@code front-desk} entry, else (before that) {@link Lifecycle#ALWAYS_ON}.
   */
  boolean wanted() {
    return credential.deliveryOn() || keptForTheDesk();
  }

  /** Whether {@link #start} was called, so the desk's ladder may launch. */
  boolean started() {
    return armed;
  }

  /**
   * The command the keeper launched, while it is the one being kept — what {@link FrontDeskInbox}
   * types into. Empty before the first launch and between an exit and the relaunch. It names the
   * keeper's own session only, never one a person started: a person's session holds no delivery
   * token.
   */
  Optional<String> keptCommandId() {
    return ladder.commandId();
  }

  /** Stop supervising; the live session itself is left to {@code CommandRegistry.terminateAll()}. */
  void stop() {
    stopped = true;
    ladder.stop();
    scheduler.shutdown();
  }

  /** The desk is wanted for its own sake, not only as the delivery loop's room. */
  private boolean keptForTheDesk() {
    if (sessionsMode) {
      Sessions.Session entry = desired;
      return entry != null && entry.running();
    }
    return lifecycle == Lifecycle.ALWAYS_ON;
  }

  /** The front desk as a ladder sees it. */
  private final class FrontDesk implements SessionLadder.Spec {

    @Override
    public String key() {
      return SessionCredentials.FRONT_DESK_KEY;
    }

    @Override
    public String label() {
      return LABEL;
    }

    @Override
    public boolean wanted() {
      return !stopped && FrontDeskKeeper.this.wanted();
    }

    /**
     * Launch only when no live {@code project.work} session exists, unless delivering — and not at
     * all while the desk is kept only for the delivery loop and has no rights to run it with yet.
     */
    @Override
    public String holdOff() {
      if (!keptForTheDesk() && !credential.delivering()) {
        // Kept only for the delivery loop, and it has no rights to run with yet: no delivery token
        // and no session credentials from qits (an older qits without a token never sends any).
        // This is what "no token, no loop" looked like before, minus the desk; ask again on the poll.
        if (!waitingLogged) {
          waitingLogged = true;
          LOG.infof("The %s waits for delivery rights before it launches.", LABEL);
        }
        return "waiting for delivery rights";
      }
      try {
        if (!credential.delivering() && desk.liveSessionExists()) {
          // Somebody else's desk (the SPA's) is up. Watch it; launch when it is gone.
          return "a project.work session started from the desk panel is live; launching when it"
              + " ends";
        }
      } catch (RuntimeException e) {
        LOG.warnf("Could not ask for a live %s session: %s", LABEL, e.getMessage());
        return "could not ask for a live project.work session";
      }
      return null;
    }

    @Override
    public Map<String, String> overlay() {
      return credential.launchOverlay();
    }

    /** A legacy credential is read from the environment and cannot change. */
    @Override
    public boolean restartsOnCredentialChange() {
      return credential.perSessionMode();
    }

    @Override
    public String preferredResume() {
      Sessions.Session entry = desired;
      return entry == null ? null : entry.resumeSessionId();
    }

    @Override
    public AgentLaunchRequest request(String resumeSessionId, Map<String, String> overlay) {
      return new AgentLaunchRequest(
          AgentMcpScope.REPOSITORY,
          SURFACE,
          AgentLaunchMode.INTERACTIVE,
          null,
          resumeSessionId,
          false,
          false,
          null,
          null,
          overlay);
    }
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
