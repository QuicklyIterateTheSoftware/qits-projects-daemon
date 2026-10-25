package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentNotSignedInException;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.ReportedState;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * One session key's ladder: keeps that key's session running while it is wanted, and stops it when
 * it is not. The rules are the front desk's, written once for every key ({@link SessionKeeper}):
 *
 * <ul>
 *   <li><b>Launch</b> when wanted and nothing of the key's is running — unless {@link Spec#holdOff}
 *       names a reason to wait, which is asked again on the poll.
 *   <li><b>Supervise:</b> on exit, relaunch after a backoff that starts at 5 s and doubles to 5 min,
 *       and resets once a session stayed up for 10 min. It never gives up.
 *   <li><b>Resume</b> while the transcript exists: the id qits named ({@link Spec#preferredResume})
 *       first, else the key's previous session. A resume the harness refused is not tried again.
 *   <li><b>Fresh token:</b> {@link ClaudeTokenPreflight} before every launch; a failure is logged and
 *       the launch goes ahead.
 *   <li><b>Credential per key</b> ({@link Spec#overlay}): inside the grace window it waits quietly;
 *       with none it fails closed — a WARN and the backoff ladder, never another credential. A
 *       changed credential restarts the running session at once, resuming it, when {@link
 *       Spec#restartsOnCredentialChange} says the credential can change.
 *   <li><b>Not signed in</b> is a WARN and a retry every 5 min, never a sign-in terminal.
 *   <li><b>Stop:</b> no longer wanted, its session is terminated (SIGTERM, then the registry's
 *       grace) and the ladder rests until it is wanted again.
 * </ul>
 *
 * <p>Every state change is a {@link SessionReport.Session} handed to the report listener, at most
 * once per distinct value.
 *
 * <p><b>Confined to its scheduler's thread.</b> Every step, and {@link #reconsider} — the one way in
 * — runs there, so nothing below needs a lock; {@link #commandId()} is the only cross-thread read. A
 * single-threaded {@link SessionKeeper.Scheduler} is therefore required, and the ones {@link
 * SessionKeeper.Scheduler#worker} builds are. Each step is tagged with the ladder's generation, so a
 * stop or a restart retires whatever was scheduled before it rather than leaving two climbs.
 */
final class SessionLadder {

  private static final Logger LOG = Logger.getLogger(SessionLadder.class);

  /** What makes one key's session the one it is, behind the ladder that keeps it. */
  interface Spec {

    /** The session key, as {@code sessions} and {@code sessionReport} name it. */
    String key();

    /** How the session is named in the logs and {@link DaemonLog}s. */
    String label();

    /** Whether the session is wanted now. Asked on every step. */
    boolean wanted();

    /**
     * Why the launch waits now, or null to go ahead — asked before the credential. A reason is the
     * report's {@code STARTING} detail, and the ladder asks again on the poll. Must not throw.
     */
    default String holdOff() {
      return null;
    }

    /**
     * The launch's environment overlay: every daemon secret scrubbed, the key's credential laid on.
     *
     * @throws SessionCredentialStore.AwaitingSessionCredentialsException inside the grace window
     * @throws SessionCredentialStore.MissingSessionCredentialException when the key has none
     */
    Map<String, String> overlay();

    /** Whether the credential can change under a running session, so a changed one restarts it. */
    boolean restartsOnCredentialChange();

    /** The harness session qits wants resumed, or null. */
    default String preferredResume() {
      return null;
    }

    /** The launch, resuming {@code resumeSessionId} (null: fresh), on {@code overlay}. */
    AgentLaunchRequest request(String resumeSessionId, Map<String, String> overlay);
  }

  private final Spec spec;
  private final SessionKeeper.Desk desk;
  private final SessionKeeper.Timing timing;
  private final SessionKeeper.Scheduler scheduler;
  private final LongSupplier clock;
  private final Consumer<DaemonMessage> send;
  private final Consumer<SessionReport.Session> report;

  private volatile boolean stopped;

  // Written only on the scheduler's thread; volatile because the front desk inbox and the API read it.
  private volatile String commandId;

  // Touched only on the scheduler's thread.
  private long generation;
  private boolean climbing;
  private Map<String, String> launchedOverlay;
  private boolean restarting;
  private boolean stopping;
  private long launchedAt;
  private long backoffMs;
  private String previousSessionId;
  private Command previousCommand;
  private String refusedResume;
  private Integer pendingExitCode;
  private SessionReport.Session lastReport;

  SessionLadder(
      Spec spec,
      SessionKeeper.Desk desk,
      SessionKeeper.Timing timing,
      SessionKeeper.Scheduler scheduler,
      LongSupplier clock,
      Consumer<DaemonMessage> send,
      Consumer<SessionReport.Session> report) {
    this.spec = spec;
    this.desk = desk;
    this.timing = timing;
    this.scheduler = scheduler;
    this.clock = clock;
    this.send = send;
    this.report = report;
    this.backoffMs = timing.initialBackoffMs();
  }

  /**
   * Act on whether the session is wanted, on the ladder's thread: start climbing when it became
   * wanted, stop when it no longer is. Asking again while nothing changed does nothing — a periodic
   * {@code sessions} frame neither relaunches a session nor cuts a backoff short.
   */
  void reconsider() {
    if (stopped) {
      return;
    }
    boolean wanted = spec.wanted();
    if (wanted && !climbing) {
      climbing = true;
      generation++;
      attempt();
    } else if (!wanted && climbing) {
      if (commandId == null) {
        retire(); // nothing running: whatever was scheduled (a backoff, a wait) is void now
      } else {
        terminate(); // its poll sees the exit and retires
      }
    }
  }

  /** The command this ladder launched, while it runs. Readable from any thread. */
  Optional<String> commandId() {
    return Optional.ofNullable(commandId);
  }

  /** Whether the ladder rests: not wanted, nothing scheduled, nothing of its own running. */
  boolean idle() {
    return !climbing && commandId == null;
  }

  /** Stop supervising for good; a live session is left to {@code CommandRegistry.terminateAll()}. */
  void stop() {
    stopped = true;
  }

  private void attempt() {
    if (!spec.wanted()) {
      retire();
      return;
    }
    String hold = spec.holdOff();
    if (hold != null) {
      report(ReportedState.STARTING, null, null, hold);
      later(this::attempt, timing.pollMs());
      return;
    }
    Map<String, String> overlay;
    try {
      overlay = spec.overlay();
    } catch (SessionCredentialStore.AwaitingSessionCredentialsException e) {
      // Not yet, rather than no: qits has had no chance to send this session its credentials since
      // the daemon started or the socket connected. Ask again shortly, without spending a backoff
      // step and without a WARN — a launch now would race the frame and run on the wrong credential.
      LOG.debugf("%s waits for session credentials ('%s')", spec.label(), e.key());
      report(ReportedState.STARTING, null, null, "waiting for session credentials");
      later(this::attempt, timing.pollMs());
      return;
    } catch (SessionCredentialStore.MissingSessionCredentialException e) {
      // Fail closed (qits-1482): no credential for this session's key means no session, never a
      // session on another credential. qits may issue one any moment, so ask again on the ladder;
      // the previous session stays resumable, since nothing about it was refused.
      String message = spec.label() + " not launched: no session credential for '" + e.key() + "'";
      LOG.warn(message);
      send.accept(new DaemonLog("WARN", message));
      report(ReportedState.FAILED, null, null, message);
      later(this::attempt, nextBackoff());
      return;
    }
    try {
      // Remote Control connects once, at startup, on whatever token the volume holds; a stale
      // one leaves the session at "Remote Control failed · /login" for good (qits-1102).
      desk.refreshCredentials();
    } catch (RuntimeException e) {
      LOG.warnf(
          "%s: the claude.ai token preflight failed (%s); launching anyway",
          spec.label(), e.getClass().getSimpleName());
    }
    String resume = resumableSession();
    Command command;
    try {
      command = desk.launch(spec.request(resume, overlay));
    } catch (AgentNotSignedInException e) {
      String message = spec.label() + " not signed in: run the login command on the runners page";
      LOG.warn(message);
      send.accept(new DaemonLog("WARN", message));
      report(ReportedState.FAILED, null, null, message);
      later(this::attempt, timing.notSignedInRetryMs());
      return;
    } catch (RuntimeException e) {
      // A resume the harness refuses must not wedge the session on that id: the next try is fresh.
      LOG.warnf("The %s did not launch: %s", spec.label(), e.getMessage());
      if (resume != null && resume.equals(spec.preferredResume())) {
        refusedResume = resume;
      }
      previousSessionId = null;
      previousCommand = null;
      report(ReportedState.FAILED, null, null, spec.label() + " did not launch: " + e.getMessage());
      later(this::attempt, nextBackoff());
      return;
    }
    commandId = command.id();
    launchedOverlay = overlay;
    launchedAt = clock.getAsLong();
    stopping = false;
    if (restarting) {
      restarting = false;
      pendingExitCode = null;
      String message = spec.label() + " restarted on its new session credential";
      LOG.info(message);
      send.accept(new DaemonLog("INFO", message));
    } else if (pendingExitCode != null) {
      String message = spec.label() + " relaunched (exit " + pendingExitCode + ")";
      LOG.info(message);
      send.accept(new DaemonLog("INFO", message));
      pendingExitCode = null;
    } else {
      LOG.infof("%s launched (command %s)", spec.label(), commandId);
    }
    report(ReportedState.RUNNING, commandId, sessionIdOf(command, resume), null);
    later(this::poll, timing.pollMs());
  }

  /** Watch the session and relaunch it once it is gone — or retire, once it is not wanted. */
  private void poll() {
    if (commandId == null) {
      attempt();
      return;
    }
    Optional<Command> current;
    try {
      current = desk.find(commandId);
    } catch (RuntimeException e) {
      later(this::poll, timing.pollMs());
      return;
    }
    long now = clock.getAsLong();
    if (current.isPresent() && current.get().status() == CommandStatus.RUNNING) {
      if (now - launchedAt >= timing.resetAfterMs()) {
        backoffMs = timing.initialBackoffMs();
      }
      if (!spec.wanted()) {
        terminate();
      } else if (!restarting && !stopping && credentialChanged()) {
        // qits re-issued the key's credential (the loop toggled, a token rotated): the session's
        // environment and MCP bearer are fixed at launch, so it is restarted onto the new one,
        // resuming its transcript. SIGTERM first, so remote control archives cleanly.
        restarting = true;
        LOG.infof(
            "%s: its session credential changed; restarting it (command %s)",
            spec.label(), commandId);
        try {
          desk.terminate(commandId);
        } catch (RuntimeException e) {
          LOG.warnf("Could not terminate the %s for its restart: %s", spec.label(), e.getMessage());
        }
      }
      // The session id lands with the harness's SessionStart hook, after the launch: report it once
      // it is known, so qits can name it as the resume of a later launch.
      report(
          ReportedState.RUNNING,
          commandId,
          sessionIdOf(current.get(), null),
          stopping ? "stopping" : null);
      later(this::poll, timing.pollMs());
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
    int exitCode = code == null ? -1 : code;
    commandId = null;
    if (!spec.wanted()) {
      LOG.infof("%s stopped (exit %d)", spec.label(), (Object) exitCode);
      retire();
      return;
    }
    pendingExitCode = exitCode;
    // A restart onto a new credential is not a crash, and neither is a stop that was taken back:
    // relaunch at once, without a backoff step.
    long delay = restarting || stopping ? 0 : nextBackoff();
    stopping = false;
    LOG.debugf(
        "%s exited (exit %d); relaunching in %d ms",
        spec.label(),
        (Object) pendingExitCode,
        (Object) delay);
    report(
        ReportedState.STARTING,
        null,
        null,
        "exited (exit " + exitCode + "); relaunching in " + delay + " ms");
    later(this::attempt, delay);
  }

  /** SIGTERM the running session, once; its poll sees the exit. */
  private void terminate() {
    if (stopping) {
      return;
    }
    stopping = true;
    LOG.infof("%s is no longer wanted; stopping it (command %s)", spec.label(), commandId);
    try {
      desk.terminate(commandId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not terminate the %s: %s", spec.label(), e.getMessage());
    }
    report(ReportedState.RUNNING, commandId, lastReportedSessionId(), "stopping");
  }

  /** Rest: nothing runs, nothing scheduled counts any more. The previous session stays resumable. */
  private void retire() {
    generation++;
    climbing = false;
    stopping = false;
    restarting = false;
    pendingExitCode = null;
    report(ReportedState.STOPPED, null, null, null);
  }

  /** Schedule {@code step}, void if the ladder retired or restarted its climb before it runs. */
  private void later(Runnable step, long delayMs) {
    long scheduledIn = generation;
    scheduler.schedule(
        () -> {
          if (!stopped && scheduledIn == generation) {
            step.run();
          }
        },
        delayMs);
  }

  private void report(String state, String command, String sessionId, String detail) {
    SessionReport.Session next =
        new SessionReport.Session(spec.key(), state, command, sessionId, detail);
    if (Objects.equals(next, lastReport)) {
      return;
    }
    lastReport = next;
    try {
      report.accept(next);
    } catch (RuntimeException e) {
      LOG.debugf("Could not report %s: %s", spec.key(), e.getMessage());
    }
  }

  private String lastReportedSessionId() {
    return lastReport == null ? null : lastReport.sessionId();
  }

  /**
   * Whether the credential the running session was launched with is no longer the one it would be
   * launched with now. Only for a credential that is there: a key qits withdrew leaves the running
   * session alone (its next launch fails closed), as does the grace window.
   */
  private boolean credentialChanged() {
    if (launchedOverlay == null || !spec.restartsOnCredentialChange()) {
      return false;
    }
    try {
      return !spec.overlay().equals(launchedOverlay);
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

  /**
   * The session to resume: the one qits named while its transcript exists and the harness has not
   * refused it, else the previous session of this ladder while its transcript exists, else null.
   */
  private String resumableSession() {
    String preferred = spec.preferredResume();
    if (preferred != null && !preferred.isBlank() && !preferred.equals(refusedResume)) {
      Command owner = preferred.equals(previousSessionId) ? previousCommand : null;
      if (transcriptPresent(owner, preferred)) {
        return preferred;
      }
    }
    if (previousSessionId == null) {
      return null;
    }
    if (transcriptPresent(previousCommand, previousSessionId)) {
      return previousSessionId;
    }
    previousSessionId = null;
    previousCommand = null;
    return null;
  }

  private boolean transcriptPresent(Command command, String sessionId) {
    try {
      return desk.transcriptExists(command, sessionId);
    } catch (RuntimeException e) {
      LOG.debugf("Could not look for transcript %s: %s", sessionId, e.getMessage());
      return false;
    }
  }

  private static String sessionIdOf(Command command, String fallback) {
    AgentSessionRef session = command == null ? null : command.currentSession();
    return session != null && session.sessionId() != null ? session.sessionId() : fallback;
  }
}
