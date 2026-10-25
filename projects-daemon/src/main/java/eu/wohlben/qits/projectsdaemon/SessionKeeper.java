package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentNotSignedInException;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.EntityFacts;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.ReportedState;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * The session multiplex (epic qits-770): every session this desk's container keeps, one {@link
 * SessionLadder} per session key, converging on the desired list qits sends ({@link Sessions}) and
 * answering every change with the full state of every key ({@link SessionReport}).
 *
 * <ul>
 *   <li><b>{@code front-desk}</b> is the {@link FrontDeskKeeper}'s, which keeps its own ladder on its
 *       own thread with its own rules — the delivery rights, deferring to a person's desk session,
 *       the restart on a changed credential. This class hands it the entry ({@link
 *       FrontDeskKeeper#desire}), which from then on replaces the lifecycle setting as the reason to
 *       keep the desk, and puts its state in the report.
 *   <li><b>Every other key</b> — an entity's UUID, one per refinement — gets a ladder here: launched
 *       as an interactive {@code REPOSITORY}-scoped session of the surface its entry names ({@code
 *       epic.chat}), keyed by the entity ({@link AgentLaunchRequest#withSessionKey}) so its name is
 *       rendered from the entity's facts, on the key's own credential — and only that: there is no
 *       legacy fallback for a key, so a refinement never runs as the container. A frame that changes
 *       a running key's entity renames its session in place ({@code setEntity}); one that stops or
 *       drops the key terminates the session and forgets the entity ({@code forgetEntity}).
 * </ul>
 *
 * <p><b>Until the first {@link Sessions} frame nothing here does anything</b>: a capability-2 qits
 * sends none, the front desk follows its lifecycle setting exactly as before, no other session is
 * kept and no report is sent.
 *
 * <p>Ladders launch nothing before {@link #start} — the boot provision must have produced a checkout
 * — so a frame that arrives first (qits sends one right after {@code hello}, usually well before the
 * clone finishes) is held, and reported as waiting for the checkout.
 *
 * <p><b>Threading.</b> Frames arrive on the control socket's event loop and only schedule: applying a
 * frame and every step of every entity ladder run on this keeper's one worker thread ({@link
 * Scheduler#worker}), which is also what keeps the harness library's single-consumer {@code
 * setEntity}/{@code forgetEntity} single-consumer, and what serializes the launches' token
 * preflights against the shared credential file. The front desk's ladder keeps its own thread, as it
 * always had. The report is assembled under one lock from both.
 */
final class SessionKeeper {

  private static final Logger LOG = Logger.getLogger(SessionKeeper.class);

  /** A ladder's timings, in milliseconds. */
  record Timing(
      long initialBackoffMs,
      long maxBackoffMs,
      long resetAfterMs,
      long notSignedInRetryMs,
      long pollMs) {

    /** This daemon's policy: 5 s doubling to 5 min, reset after 10 min up, sign-in every 5 min. */
    static final Timing DEFAULT = new Timing(5_000, 300_000, 600_000, 300_000, 2_000);
  }

  /** Runs a step later, off the caller's thread. */
  interface Scheduler {
    void schedule(Runnable step, long delayMs);

    void shutdown();

    /** One daemon worker thread, so a keeper never blocks the event loop or the boot worker. */
    static Scheduler worker() {
      return worker("projects-daemon-front-desk");
    }

    /** One daemon worker thread named {@code name}. Single-threaded, as a ladder requires. */
    static Scheduler worker(String name) {
      ScheduledExecutorService executor =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, name);
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

  /** What a ladder needs of the agent surface, behind one seam a test can fake. */
  interface Desk {

    /** Launch a session; may throw {@link AgentNotSignedInException}. */
    Command launch(AgentLaunchRequest request);

    /** The command as the store holds it now, or empty once it is gone. */
    Optional<Command> find(String commandId);

    /** Whether {@code sessionId}'s transcript is still on the credential volume. */
    boolean transcriptExists(Command command, String sessionId);

    /**
     * Terminate {@code commandId} (SIGTERM, then the registry's grace and escalation). False when it
     * is not running.
     */
    default boolean terminate(String commandId) {
      return false;
    }

    /**
     * Make sure the claude.ai access token is fresh before a launch (qits-1102); see {@link
     * ClaudeTokenPreflight}. Must not throw, but the ladder launches regardless if it does.
     */
    default void refreshCredentials() {}

    /** Rename {@code key}'s live sessions for its entity's new facts ({@code setEntity}). */
    default void setEntity(String key, String qualifiedId, EntityFacts facts) {}

    /** Forget {@code key}'s entity and its sessions' naming ({@code forgetEntity}). */
    default void forgetEntity(String key) {}

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
        public boolean transcriptExists(Command command, String sessionId) {
          return FrontDeskKeeper.transcriptExists(claudeMount, command, sessionId);
        }

        @Override
        public boolean terminate(String commandId) {
          return registry.terminate(commandId);
        }

        @Override
        public void setEntity(String key, String qualifiedId, EntityFacts facts) {
          launch.setEntity(key, qualifiedId, facts);
        }

        @Override
        public void forgetEntity(String key) {
          launch.forgetEntity(key);
        }
      };
    }
  }

  private final FrontDeskKeeper frontDesk;
  private final SessionCredentialStore credentials;
  private final Desk desk;
  private final Timing timing;
  private final Scheduler scheduler;
  private final LongSupplier clock;
  private final Consumer<DaemonMessage> send;

  /** The entity ladders, by key, in the last frame's order. Touched only on the scheduler. */
  private final Map<String, Keyed> keyed = new LinkedHashMap<>();

  /**
   * Which key launched each command, for the API's {@code sessionKey}. One small entry per launch
   * for the container's life, like the command store's own rows.
   */
  private final Map<String, String> keysByCommand = new ConcurrentHashMap<>();

  /** Each key's latest state, as its ladder reported it. */
  private final Map<String, SessionReport.Session> latest = new ConcurrentHashMap<>();

  private final Object reportLock = new Object();

  /** The last frame's entries; null until the first, which is what "legacy" means here. */
  private volatile List<Sessions.Session> desired;

  private volatile boolean started;
  private volatile boolean stopped;

  /** Why no ladder may launch at all (the boot provision failed), or null. */
  private volatile String refusal;

  /** True while a frame is applied, so its many ladder changes go out as one report. */
  private volatile boolean applying;

  SessionKeeper(
      FrontDeskKeeper frontDesk,
      SessionCredentialStore credentials,
      Desk desk,
      Timing timing,
      Scheduler scheduler,
      LongSupplier clock,
      Consumer<DaemonMessage> send) {
    this.frontDesk = frontDesk;
    this.credentials = credentials;
    this.desk = desk;
    this.timing = timing;
    this.scheduler = scheduler;
    this.clock = clock;
    this.send = send;
    if (frontDesk != null) {
      frontDesk.reportTo(this::onReport);
    }
  }

  /**
   * Converge on {@code frame}, qits' full desired list. Called on the event loop; only schedules.
   */
  void apply(Sessions frame) {
    if (frame == null || stopped) {
      return;
    }
    scheduler.schedule(() -> applyNow(frame), 0);
  }

  /**
   * Let the ladders launch: the boot provision produced a checkout and the agent surface is wired.
   * Once; the front desk keeper is started beside this, by {@link ControlSocket}.
   */
  void start() {
    if (stopped) {
      return;
    }
    scheduler.schedule(
        () -> {
          if (started) {
            return;
          }
          started = true;
          applying = true;
          try {
            keyed.values().forEach(entry -> entry.ladder.reconsider());
          } finally {
            applying = false;
          }
          sendReport();
        },
        0);
  }

  /**
   * No ladder will ever launch: the boot provision failed, so there is no checkout to run a session
   * in. Every wanted key is reported {@code FAILED} with {@code reason}, for as long as the container
   * lives — recovery is a new container, as for every failed provision.
   */
  void refuse(String reason) {
    refusal = reason;
    sendReport();
  }

  /** Report the current state again — the control socket reconnected. Nothing before a frame. */
  void reportCurrent() {
    sendReport();
  }

  /** The session key whose ladder launched {@code commandId}, or null for any other command. */
  String sessionKeyOf(String commandId) {
    return commandId == null ? null : keysByCommand.get(commandId);
  }

  /** Stop supervising; the live sessions themselves are left to {@code terminateAll()}. */
  void stop() {
    stopped = true;
    scheduler.shutdown();
  }

  private void applyNow(Sessions frame) {
    if (stopped) {
      return;
    }
    Map<String, Sessions.Session> next = new LinkedHashMap<>();
    for (Sessions.Session session : frame.sessions()) {
      if (next.putIfAbsent(session.key(), session) != null) {
        LOG.warnf("sessions named '%s' twice; the first entry is kept.", session.key());
      }
    }
    boolean first = desired == null;
    desired = List.copyOf(next.values());
    if (first) {
      LOG.infof("Session multiplex on: %d session key(s) from qits.", next.size());
    }
    applying = true;
    try {
      if (frontDesk != null) {
        frontDesk.desire(next.get(SessionCredentials.FRONT_DESK_KEY));
      }
      next.remove(SessionCredentials.FRONT_DESK_KEY);
      for (Sessions.Session session : next.values()) {
        Keyed entry = keyed.get(session.key());
        if (entry == null) {
          entry = new Keyed(session.key());
          keyed.put(session.key(), entry);
        }
        entry.desire(session);
      }
      for (Keyed entry : List.copyOf(keyed.values())) {
        if (!next.containsKey(entry.key)) {
          entry.desire(null);
        }
      }
      // A key qits dropped is forgotten once its session has exited; one still exiting stays until
      // a later frame finds it resting.
      keyed.values().removeIf(entry -> entry.desired == null && entry.ladder.idle());
    } finally {
      applying = false;
    }
    sendReport();
  }

  private void onReport(SessionReport.Session session) {
    if (ReportedState.RUNNING.equals(session.state()) && session.commandId() != null) {
      keysByCommand.put(session.commandId(), session.key());
    }
    latest.put(session.key(), session);
    // A frame's own changes go out once, with the report that answers it.
    if (!applying) {
      sendReport();
    }
  }

  /** The full report, front desk first, then every key of the last frame in its order. */
  private void sendReport() {
    List<Sessions.Session> wanted = desired;
    if (wanted == null || stopped) {
      return; // legacy: a capability-2 qits hears nothing new
    }
    synchronized (reportLock) {
      List<SessionReport.Session> sessions = new ArrayList<>();
      sessions.add(frontDeskState());
      for (Sessions.Session session : wanted) {
        if (!session.key().equals(SessionCredentials.FRONT_DESK_KEY)) {
          sessions.add(stateOf(session.key(), session.running()));
        }
      }
      send.accept(new SessionReport(sessions));
    }
  }

  private SessionReport.Session frontDeskState() {
    String key = SessionCredentials.FRONT_DESK_KEY;
    if (frontDesk == null) {
      return new SessionReport.Session(key, ReportedState.STOPPED, null, null, null);
    }
    return stateOf(key, frontDesk.wanted());
  }

  /** What a key's ladder last said, or — before it said anything — what its wait is. */
  private SessionReport.Session stateOf(String key, boolean wanted) {
    if (wanted && refusal != null) {
      return new SessionReport.Session(key, ReportedState.FAILED, null, null, refusal);
    }
    SessionReport.Session reported = latest.get(key);
    boolean frontDeskKey = key.equals(SessionCredentials.FRONT_DESK_KEY);
    boolean launching = frontDeskKey ? frontDesk.started() : started;
    if (reported != null && (launching || !wanted)) {
      return reported;
    }
    return wanted
        ? new SessionReport.Session(
            key, ReportedState.STARTING, null, null, "waiting for the checkout")
        : new SessionReport.Session(key, ReportedState.STOPPED, null, null, null);
  }

  /** One entity key: its ladder, and what the last frame said about it. */
  private final class Keyed implements SessionLadder.Spec {

    final String key;
    final SessionLadder ladder;

    /** The last frame's entry for the key; null once qits dropped it. Scheduler thread only. */
    Sessions.Session desired;

    Keyed(String key) {
      this.key = key;
      this.ladder =
          new SessionLadder(
              this, desk, timing, scheduler, clock, send, SessionKeeper.this::onReport);
    }

    /** Take the frame's entry for the key (null: dropped) and act on what changed. */
    void desire(Sessions.Session next) {
      Sessions.Session before = desired;
      desired = next;
      boolean wasRunning = before != null && before.running();
      boolean isRunning = next != null && next.running();
      if (wasRunning && !isRunning) {
        // Stopped or dropped: no rename will reach its sessions again, and a later launch for the
        // key starts its entity afresh from the frame that asks for it.
        try {
          desk.forgetEntity(key);
        } catch (RuntimeException e) {
          LOG.debugf("Could not forget %s's entity: %s", key, e.getMessage());
        }
      } else if (isRunning
          && before != null
          && next.entity() != null
          && !Objects.equals(before.entity(), next.entity())
          && ladder.commandId().isPresent()) {
        // Renamed in place: the next launch would carry the new facts anyway, the running one
        // needs telling.
        Sessions.Entity entity = next.entity();
        try {
          desk.setEntity(key, entity.qualifiedId(), facts(entity));
        } catch (RuntimeException e) {
          LOG.warnf("Could not rename %s's session: %s", label(), e.getMessage());
        }
      }
      if (started) {
        ladder.reconsider();
      }
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public String label() {
      Sessions.Session entry = desired;
      String surface = entry == null || entry.surface() == null ? "session" : entry.surface();
      String about =
          entry != null && entry.entity() != null && entry.entity().qualifiedId() != null
              ? entry.entity().qualifiedId()
              : key;
      return surface + " session " + about;
    }

    @Override
    public boolean wanted() {
      Sessions.Session entry = desired;
      return started && !stopped && refusal == null && entry != null && entry.running();
    }

    /**
     * The key's own credential and nothing else: legacy mode — no {@code sessionCredentials} from
     * qits — fails closed too, because the container's token is not this session's to run on.
     */
    @Override
    public Map<String, String> overlay() {
      return DaemonSecrets.overlay(Map.of(), credentials.perSessionOverlayFor(key));
    }

    @Override
    public boolean restartsOnCredentialChange() {
      return true;
    }

    @Override
    public String preferredResume() {
      Sessions.Session entry = desired;
      return entry == null ? null : entry.resumeSessionId();
    }

    @Override
    public AgentLaunchRequest request(String resumeSessionId, Map<String, String> overlay) {
      Sessions.Session entry = desired;
      Sessions.Entity entity = entry.entity();
      return new AgentLaunchRequest(
              AgentMcpScope.REPOSITORY,
              AgentSurface.of(entry.surface()),
              AgentLaunchMode.INTERACTIVE,
              null,
              resumeSessionId,
              false,
              false,
              null,
              null,
              overlay)
          .withSessionKey(
              key,
              entity == null ? null : entity.qualifiedId(),
              entity == null ? null : facts(entity));
    }
  }

  /** The harness library's facts for {@code entity}. */
  static EntityFacts facts(Sessions.Entity entity) {
    return new EntityFacts(
        entity.title(), entity.status(), entity.blocked(), entity.blockSource());
  }
}
