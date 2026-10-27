package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.EntityFacts;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.AgentSessionSource;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Lifecycle;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SessionKeeper} — the session multiplex — against a faked harness and a virtual clock: one
 * ladder per key, each independent of the others; {@code STOPPED} and a dropped key stop only that
 * key and forget its entity; changed facts rename only that key's session; a key without a
 * credential fails closed; every change is a {@code sessionReport}; and before the first {@code
 * sessions} frame nothing of it exists at all.
 */
class SessionKeeperTest {

  private static final SessionKeeper.Timing TIMING = SessionKeeper.Timing.DEFAULT;

  private static final String A = "0b7c4e1a-5d1f-4c3e-9a77-2f0e8c1d4b6a";
  private static final String B = "7f3a9c2e-1b4d-4e8f-a6c5-3d2e1f0a9b8c";

  // --- the virtual clock and scheduler --------------------------------------------------------

  private long now = 1_000_000;
  private long sequence;

  private record Task(long dueAt, long order, Runnable step) {}

  private final PriorityQueue<Task> tasks =
      new PriorityQueue<>(
          (a, b) ->
              a.dueAt() != b.dueAt()
                  ? Long.compare(a.dueAt(), b.dueAt())
                  : Long.compare(a.order(), b.order()));

  private final SessionKeeper.Scheduler scheduler =
      new SessionKeeper.Scheduler() {
        @Override
        public void schedule(Runnable step, long delayMs) {
          tasks.add(new Task(now + delayMs, sequence++, step));
        }

        @Override
        public void shutdown() {
          tasks.clear();
        }
      };

  private void advance(long ms) {
    long until = now + ms;
    while (!tasks.isEmpty() && tasks.peek().dueAt() <= until) {
      Task task = tasks.poll();
      now = Math.max(now, task.dueAt());
      task.step().run();
    }
    now = until;
  }

  // --- the faked harness ----------------------------------------------------------------------

  private record Renamed(String key, String qualifiedId, EntityFacts facts) {}

  private final class FakeDesk implements FrontDeskKeeper.Desk {
    final List<AgentLaunchRequest> launches = new ArrayList<>();
    final Map<String, Command> commands = new LinkedHashMap<>();
    final Map<String, String> keyOfCommand = new HashMap<>();
    final List<String> terminated = new ArrayList<>();
    final List<Renamed> renamed = new ArrayList<>();
    final List<String> forgotten = new ArrayList<>();
    boolean transcriptPresent = true;

    @Override
    public Command launch(AgentLaunchRequest request) {
      launches.add(request);
      String sessionId =
          request.resumeSessionId() != null
              ? request.resumeSessionId()
              : UUID.randomUUID().toString();
      Command command =
          Command.running(
                  UUID.randomUUID().toString(),
                  CommandKind.TERMINAL,
                  null,
                  null,
                  null,
                  "agent",
                  "claude",
                  true,
                  "CLAUDE",
                  request.surface().key(),
                  Instant.ofEpochMilli(now))
              .withSession(
                  new AgentSessionRef(
                      sessionId, AgentSessionSource.PINNED, null, null, Instant.ofEpochMilli(now)));
      commands.put(command.id(), command);
      keyOfCommand.put(
          command.id(),
          request.sessionKey() == null ? SessionCredentials.FRONT_DESK_KEY : request.sessionKey());
      return command;
    }

    @Override
    public Optional<Command> find(String commandId) {
      return Optional.ofNullable(commands.get(commandId));
    }

    @Override
    public boolean liveSessionExists() {
      return false; // nobody else's desk: these tests are about the multiplex
    }

    @Override
    public boolean transcriptExists(Command command, String sessionId) {
      return transcriptPresent;
    }

    @Override
    public boolean terminate(String commandId) {
      terminated.add(commandId);
      Command command = commands.get(commandId);
      if (command == null || !command.isRunning()) {
        return false;
      }
      commands.put(
          commandId, command.finished(CommandStatus.TERMINATED, 143, Instant.ofEpochMilli(now)));
      return true;
    }

    @Override
    public void setEntity(String key, String qualifiedId, EntityFacts facts) {
      renamed.add(new Renamed(key, qualifiedId, facts));
    }

    @Override
    public void forgetEntity(String key) {
      forgotten.add(key);
    }

    /** The running command launched for {@code key}, if any. */
    Optional<Command> running(String key) {
      return commands.values().stream()
          .filter(Command::isRunning)
          .filter(command -> key.equals(keyOfCommand.get(command.id())))
          .findFirst();
    }

    List<AgentLaunchRequest> launchesFor(String key) {
      return launches.stream().filter(request -> key.equals(request.sessionKey())).toList();
    }

    void exit(String key, int code) {
      Command command = running(key).orElseThrow();
      commands.put(
          command.id(), command.finished(CommandStatus.EXITED, code, Instant.ofEpochMilli(now)));
    }
  }

  private final FakeDesk desk = new FakeDesk();
  private final List<DaemonMessage> sent = new ArrayList<>();

  private final SessionCredentialStore credentials =
      new SessionCredentialStore(() -> "qits_tok_container", () -> "tok-container", () -> now);

  private final FrontDeskKeeper frontDesk =
      new FrontDeskKeeper(
          Lifecycle.ON_DEMAND,
          new FrontDeskCredential(DeliveryKeeper.Settings.OFF, credentials),
          desk,
          TIMING,
          scheduler,
          () -> now,
          sent::add);

  private final SessionKeeper keeper =
      new SessionKeeper(frontDesk, credentials, desk, TIMING, scheduler, () -> now, sent::add);

  /** The keeper as {@link ControlSocket} starts it once the provision succeeded. */
  private void startBoth() {
    frontDesk.start();
    keeper.start();
    advance(0);
  }

  private void credentialsFor(String... keys) {
    credentials.replace(
        new SessionCredentials(
            Arrays.stream(keys)
                .map(key -> new SessionCredentials.Credential(key, "qits_tok_" + key, "tok-" + key))
                .toList()));
  }

  private static Sessions.Entity entity(String qualifiedId, String status) {
    return new Sessions.Entity(qualifiedId, "Refine " + qualifiedId, status, false, null);
  }

  private static Sessions.Session refinement(String key, String state, Sessions.Entity entity) {
    return new Sessions.Session(key, "epic.chat", state, null, entity);
  }

  private void frame(Sessions.Session... sessions) {
    keeper.apply(new Sessions(List.of(sessions)));
    advance(0);
  }

  private List<SessionReport> reports() {
    return sent.stream()
        .filter(SessionReport.class::isInstance)
        .map(SessionReport.class::cast)
        .toList();
  }

  private SessionReport lastReport() {
    List<SessionReport> reports = reports();
    return reports.getLast();
  }

  private String state(String key) {
    return lastReport().find(key).orElseThrow().state();
  }

  private static Map<String, String> overlay(String key) {
    Map<String, String> expected = new HashMap<>(DaemonSecrets.SCRUB);
    expected.put("QITS_TOKEN", "qits_tok_" + key);
    expected.put("QITS_TOKEN_SUBJECT", "tok-" + key);
    return expected;
  }

  // --- the tests ------------------------------------------------------------------------------

  @Test
  void beforeAnySessionsFrameNothingIsKeptAndNothingReported() {
    credentialsFor(SessionCredentials.FRONT_DESK_KEY, A);
    startBoth();
    keeper.reportCurrent();
    advance(TIMING.resetAfterMs());

    assertTrue(desk.launches.isEmpty(), "an ON_DEMAND container under a capability-2 qits");
    assertTrue(reports().isEmpty(), "and qits hears nothing it would not understand");
  }

  @Test
  void aRefinementKeyLaunchesAsItsEntitysEpicChatOnItsOwnCredential() {
    credentialsFor(A);
    startBoth();
    frame(refinement(A, "RUNNING", entity("qits-1234", "REPORTED")));

    assertEquals(1, desk.launches.size());
    assertEquals(
        new AgentLaunchRequest(
                AgentMcpScope.REPOSITORY,
                AgentSurface.EPIC_CHAT,
                AgentLaunchMode.INTERACTIVE,
                null,
                null,
                false,
                false,
                null,
                null,
                overlay(A))
            .withSessionKey(
                A, "qits-1234", new EntityFacts("Refine qits-1234", "REPORTED", false, null)),
        desk.launches.getFirst());

    Command command = desk.running(A).orElseThrow();
    assertEquals(
        new SessionReport.Session(
            A, "RUNNING", command.id(), command.currentSession().sessionId(), null),
        lastReport().find(A).orElseThrow());
    assertEquals(A, keeper.sessionKeyOf(command.id()), "the API can name the command's key");
  }

  @Test
  void twoKeysRunIndependently() {
    credentialsFor(A, B);
    startBoth();
    frame(
        refinement(A, "RUNNING", entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));
    assertEquals(1, desk.launchesFor(A).size());
    assertEquals(1, desk.launchesFor(B).size());
    String sessionA = desk.running(A).orElseThrow().currentSession().sessionId();

    // A crashes; B is untouched, and A comes back on its own backoff, resuming its own session.
    desk.exit(A, 1);
    advance(TIMING.pollMs());
    assertEquals("STARTING", state(A));
    assertEquals("RUNNING", state(B));
    advance(TIMING.initialBackoffMs());
    assertEquals(2, desk.launchesFor(A).size());
    assertEquals(sessionA, desk.launchesFor(A).getLast().resumeSessionId());
    assertEquals(1, desk.launchesFor(B).size(), "B was never relaunched");
    assertTrue(desk.terminated.isEmpty());
  }

  @Test
  void stoppedStopsOnlyItsKeyAndForgetsItsEntity() {
    credentialsFor(A, B);
    startBoth();
    frame(
        refinement(A, "RUNNING", entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));
    String commandA = desk.running(A).orElseThrow().id();

    frame(
        refinement(A, "STOPPED", entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));
    assertEquals(List.of(commandA), desk.terminated, "A's session, SIGTERMed at once");
    assertEquals(List.of(A), desk.forgotten);

    advance(TIMING.pollMs());
    assertEquals("STOPPED", state(A));
    assertEquals("RUNNING", state(B));
    advance(TIMING.maxBackoffMs() * 2);
    assertEquals(1, desk.launchesFor(A).size(), "a stopped key is not relaunched");
    assertEquals(1, desk.launchesFor(B).size());

    // Wanted again: relaunched, resuming the session it had.
    String sessionA = desk.commands.get(commandA).currentSession().sessionId();
    frame(
        refinement(A, "RUNNING", entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));
    assertEquals(2, desk.launchesFor(A).size());
    assertEquals(sessionA, desk.launchesFor(A).getLast().resumeSessionId());
  }

  @Test
  void aKeyLeftOutOfTheFrameIsStoppedAndLeavesTheReport() {
    credentialsFor(A, B);
    startBoth();
    frame(
        refinement(A, "RUNNING", entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));
    String commandB = desk.running(B).orElseThrow().id();

    frame(refinement(A, "RUNNING", entity("qits-1", "REPORTED")));
    assertEquals(List.of(commandB), desk.terminated);
    assertEquals(List.of(B), desk.forgotten);
    advance(TIMING.pollMs());
    assertTrue(desk.running(B).isEmpty());
    assertTrue(lastReport().find(B).isEmpty(), "a dropped key is not reported");
    assertEquals("RUNNING", state(A));
    advance(TIMING.maxBackoffMs() * 2);
    assertEquals(1, desk.launchesFor(B).size(), "and never relaunched");
  }

  @Test
  void changedFactsRenameOnlyThatKeysSession() {
    credentialsFor(A, B);
    startBoth();
    frame(
        refinement(A, "RUNNING", entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));

    // The same frame again (qits sends it periodically): nothing renamed, nothing relaunched.
    frame(
        refinement(A, "RUNNING", entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));
    assertTrue(desk.renamed.isEmpty());

    frame(
        refinement(A, "RUNNING", new Sessions.Entity("qits-1", "Refine qits-1", "REFINED", true, "AGENT_WAITING")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));
    assertEquals(
        List.of(
            new Renamed(A, "qits-1", new EntityFacts("Refine qits-1", "REFINED", true, "AGENT_WAITING"))),
        desk.renamed);
    assertEquals(2, desk.launches.size(), "a rename relaunches nothing");
    assertTrue(desk.terminated.isEmpty());
  }

  @Test
  void aKeyWithoutACredentialFailsClosed() {
    credentialsFor(A); // nothing for B
    startBoth();
    frame(
        refinement(A, "RUNNING", entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));

    assertEquals(1, desk.launches.size(), "B launched nothing, never on another credential");
    assertEquals(A, desk.launches.getFirst().sessionKey());
    SessionReport.Session b = lastReport().find(B).orElseThrow();
    assertEquals("FAILED", b.state());
    assertEquals(
        "epic.chat session qits-2 not launched: no session credential for '" + B + "'",
        b.detail());
    assertTrue(
        sent.stream()
            .filter(DaemonLog.class::isInstance)
            .map(DaemonLog.class::cast)
            .anyMatch(log -> log.level().equals("WARN") && log.message().equals(b.detail())));

    // Issued later: picked up on the ladder's backoff.
    credentialsFor(A, B);
    advance(TIMING.initialBackoffMs() * 2);
    assertEquals(1, desk.launchesFor(B).size());
    assertEquals(overlay(B), desk.launchesFor(B).getFirst().environment());
    assertEquals("RUNNING", state(B));
  }

  @Test
  void aRefinementNeverRunsOnTheContainersTokenInLegacyMode() {
    // No sessionCredentials frame at all, and no grace window: legacy for the front desk, but a
    // refinement key has no legacy credential.
    startBoth();
    frame(refinement(A, "RUNNING", entity("qits-1", "REPORTED")));
    assertTrue(desk.launches.isEmpty());
    assertEquals("FAILED", state(A));
  }

  @Test
  void everyChangeIsReported() {
    credentialsFor(A);
    startBoth();
    frame(refinement(A, "RUNNING", entity("qits-1", "REPORTED")));
    int afterLaunch = reports().size();

    desk.exit(A, 1);
    advance(TIMING.pollMs());
    assertEquals(afterLaunch + 1, reports().size(), "the exit");
    assertEquals(
        new SessionReport.Session(A, "STARTING", null, null, "exited (exit 1); relaunching in 5000 ms"),
        lastReport().find(A).orElseThrow());

    advance(TIMING.initialBackoffMs());
    assertEquals(afterLaunch + 2, reports().size(), "the relaunch");
    assertEquals("RUNNING", state(A));

    advance(TIMING.pollMs() * 10);
    assertEquals(afterLaunch + 2, reports().size(), "no change, no report");

    keeper.reportCurrent();
    assertEquals(afterLaunch + 3, reports().size(), "a reconnect hears the current state");
    assertEquals(
        List.of(SessionCredentials.FRONT_DESK_KEY, A),
        lastReport().sessions().stream().map(SessionReport.Session::key).toList(),
        "front desk first, then the frame's keys");
  }

  @Test
  void aFrameBeforeTheCheckoutIsHeldAndReportedAsWaiting() {
    credentialsFor(A);
    frame(refinement(A, "RUNNING", entity("qits-1", "REPORTED")));
    assertTrue(desk.launches.isEmpty(), "nothing launches before the provision");
    assertEquals(
        new SessionReport.Session(A, "STARTING", null, null, "waiting for the checkout"),
        lastReport().find(A).orElseThrow());

    startBoth();
    assertEquals(1, desk.launches.size());
    assertEquals("RUNNING", state(A));
  }

  @Test
  void aFailedProvisionReportsEveryWantedKeyFailed() {
    credentialsFor(A);
    frame(
        new Sessions.Session(SessionCredentials.FRONT_DESK_KEY, "project.work", "RUNNING", null, null),
        refinement(A, "RUNNING", entity("qits-1", "REPORTED")),
        refinement(B, "STOPPED", entity("qits-2", "REPORTED")));
    keeper.refuse("the boot provision failed");
    assertEquals("FAILED", state(SessionCredentials.FRONT_DESK_KEY));
    assertEquals("FAILED", state(A));
    assertEquals("STOPPED", state(B));
    assertEquals("the boot provision failed", lastReport().find(A).orElseThrow().detail());
  }

  @Test
  void theServersResumeIdIsPreferredWhileItsTranscriptExists() {
    List<String> prompts = new ArrayList<>();
    frontDesk.promptResumesTo(prompts::add);
    credentialsFor(A);
    startBoth();
    frame(
        new Sessions.Session(
            A, "epic.chat", "RUNNING", "11111111-1111-1111-1111-111111111111", entity("qits-1", "REPORTED")));
    assertEquals(
        "11111111-1111-1111-1111-111111111111", desk.launches.getFirst().resumeSessionId());

    desk.transcriptPresent = false;
    desk.exit(A, 1);
    advance(TIMING.pollMs() + TIMING.initialBackoffMs());
    assertNull(desk.launches.getLast().resumeSessionId(), "no transcript, a fresh session");
    assertTrue(
        logs().getLast().message().endsWith(
            "launched fresh: transcript of 11111111-1111-1111-1111-111111111111 missing"),
        logs().getLast().message());
    assertTrue(prompts.isEmpty(), "a refinement is never told it was interrupted");
  }

  /**
   * The desk's first launch in this daemon process resumes the session qits named: the container
   * was recreated, and that one turn goes to the desk's inbox — the reason for each later resume
   * likewise.
   */
  @Test
  void aFrontDeskResumingTheNamedSessionIsToldTheContainerWasRecreated() {
    List<String> prompts = new ArrayList<>();
    frontDesk.promptResumesTo(prompts::add);
    credentialsFor(SessionCredentials.FRONT_DESK_KEY);
    startBoth();
    String named = "33333333-3333-3333-3333-333333333333";
    frame(
        new Sessions.Session(
            SessionCredentials.FRONT_DESK_KEY, "project.work", "RUNNING", named, null));

    assertEquals(named, desk.launches.getFirst().resumeSessionId());
    assertEquals(List.of(interrupted("the desk container was restarted or recreated")), prompts);
    assertTrue(logs().isEmpty(), "a resumed launch has no fresh-launch reason to log");

    desk.exit(SessionCredentials.FRONT_DESK_KEY, 3);
    advance(TIMING.pollMs() + TIMING.initialBackoffMs());
    assertEquals(named, desk.launches.getLast().resumeSessionId());
    assertEquals(interrupted("the session exited (exit 3)"), prompts.getLast());
    assertEquals(2, prompts.size());
  }

  /** A fresh first launch of the desk types nothing and logs why it resumed nothing. */
  @Test
  void aFreshFrontDeskIsToldNothingAndLogsWhy() {
    List<String> prompts = new ArrayList<>();
    frontDesk.promptResumesTo(prompts::add);
    credentialsFor(SessionCredentials.FRONT_DESK_KEY);
    startBoth();
    frame(
        new Sessions.Session(
            SessionCredentials.FRONT_DESK_KEY, "project.work", "RUNNING", null, null));

    assertEquals(1, desk.launches.size());
    assertNull(desk.launches.getFirst().resumeSessionId());
    assertTrue(prompts.isEmpty());
    assertEquals(
        List.of(new DaemonLog("INFO", "front desk launched fresh: no previous session")), logs());
  }

  private List<DaemonLog> logs() {
    return sent.stream().filter(DaemonLog.class::isInstance).map(DaemonLog.class::cast).toList();
  }

  private static String interrupted(String reason) {
    return "You were interrupted (reason: "
        + reason
        + "). Re-read /tmp/qits-delivery-brief.md, it may have changed, then carry on where you"
        + " stopped.";
  }

  /**
   * Only the front desk keeps a record on the claude volume (qits-1619): a refinement key neither
   * resumes the recorded session nor writes its own over it.
   */
  @Test
  void refinementLaddersNeitherReadNorWriteTheRecord(@TempDir Path mount) throws Exception {
    String recorded = "66666666-6666-6666-6666-666666666666";
    FrontDeskSessionRecord record = FrontDeskSessionRecord.of(mount.toString(), "p-1");
    Files.createDirectories(record.path().getParent());
    Files.writeString(record.path(), recorded + "\n");
    FrontDeskKeeper recordingDesk =
        new FrontDeskKeeper(
            Lifecycle.ON_DEMAND,
            new FrontDeskCredential(DeliveryKeeper.Settings.OFF, credentials),
            desk,
            TIMING,
            scheduler,
            () -> now,
            sent::add,
            record);
    SessionKeeper multiplex =
        new SessionKeeper(
            recordingDesk, credentials, desk, TIMING, scheduler, () -> now, sent::add);
    credentialsFor(SessionCredentials.FRONT_DESK_KEY, A);
    recordingDesk.start();
    multiplex.start();
    multiplex.apply(
        new Sessions(
            List.of(
                new Sessions.Session(
                    SessionCredentials.FRONT_DESK_KEY, "project.work", "RUNNING", null, null),
                refinement(A, "RUNNING", entity("qits-1", "REPORTED")))));
    advance(0);

    assertNull(desk.launchesFor(A).getFirst().resumeSessionId(), "the desk's record is not A's");
    assertEquals(
        recorded,
        desk.running(SessionCredentials.FRONT_DESK_KEY).orElseThrow().currentSession().sessionId(),
        "the desk resumed it");

    desk.exit(A, 1);
    advance(TIMING.pollMs() + TIMING.initialBackoffMs() + TIMING.pollMs() * 3);
    assertEquals(2, desk.launchesFor(A).size());
    assertEquals(
        recorded, Files.readString(record.path()).strip(), "A's sessions are not recorded");
    try (Stream<Path> files = Files.list(record.path().getParent())) {
      assertEquals(List.of(record.path()), files.toList(), "and no record of A's own");
    }
  }

  @Test
  void anUnknownSurfaceFailsTheKeyAndNothingElse() {
    credentialsFor(A, B);
    startBoth();
    frame(
        new Sessions.Session(A, "no.such.surface", "RUNNING", null, entity("qits-1", "REPORTED")),
        refinement(B, "RUNNING", entity("qits-2", "REPORTED")));
    assertEquals("FAILED", state(A));
    assertEquals("RUNNING", state(B));
  }

  // --- the front desk in the multiplex --------------------------------------------------------

  @Test
  void theFrontDeskEntryReplacesTheLifecycle() {
    credentialsFor(SessionCredentials.FRONT_DESK_KEY);
    startBoth();
    assertTrue(desk.launches.isEmpty(), "ON_DEMAND, no frame: no desk");

    Sessions.Session running =
        new Sessions.Session(SessionCredentials.FRONT_DESK_KEY, "project.work", "RUNNING", null, null);
    frame(running);
    assertEquals(1, desk.launches.size());
    AgentLaunchRequest launch = desk.launches.getFirst();
    assertEquals(AgentSurface.PROJECT_WORK, launch.surface());
    assertNull(launch.sessionKey(), "the desk is the container's own entity");
    assertEquals(overlay(SessionCredentials.FRONT_DESK_KEY), launch.environment());
    String kept = frontDesk.keptCommandId().orElseThrow();
    assertEquals("RUNNING", state(SessionCredentials.FRONT_DESK_KEY));
    assertEquals(kept, lastReport().find(SessionCredentials.FRONT_DESK_KEY).orElseThrow().commandId());
    assertEquals(SessionCredentials.FRONT_DESK_KEY, keeper.sessionKeyOf(kept));

    // Left out of the next frame: the kept desk is terminated and stays down.
    frame();
    advance(TIMING.pollMs() * 2);
    assertEquals(List.of(kept), desk.terminated);
    assertEquals("STOPPED", state(SessionCredentials.FRONT_DESK_KEY));
    advance(TIMING.maxBackoffMs() * 2);
    assertEquals(1, desk.launches.size());
    assertTrue(frontDesk.keptCommandId().isEmpty());
  }

  @Test
  void deliveryStillKeepsTheDeskWhateverTheFrameSays() {
    FrontDeskKeeper delivering =
        new FrontDeskKeeper(
            Lifecycle.ON_DEMAND,
            new FrontDeskCredential(
                DeliveryKeeper.Settings.parse(
                    Optional.of("on"), Optional.empty(), Optional.empty()),
                credentials),
            desk,
            TIMING,
            scheduler,
            () -> now,
            sent::add);
    SessionKeeper multiplex =
        new SessionKeeper(delivering, credentials, desk, TIMING, scheduler, () -> now, sent::add);
    credentialsFor(SessionCredentials.FRONT_DESK_KEY);
    delivering.start();
    multiplex.start();
    multiplex.apply(
        new Sessions(
            List.of(
                new Sessions.Session(
                    SessionCredentials.FRONT_DESK_KEY, "project.work", "STOPPED", null, null))));
    advance(TIMING.pollMs() * 3);
    assertEquals(1, desk.launches.size(), "the delivery loop needs its desk");
    assertTrue(desk.terminated.isEmpty());
  }
}
