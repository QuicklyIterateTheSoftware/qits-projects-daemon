package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentNotSignedInException;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.AgentSessionSource;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Lifecycle;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FrontDeskKeeper} against a faked harness ({@link FrontDeskKeeper.Desk}) and a virtual
 * clock: the launch, the no-double-launch rule, the relaunch ladder, the not-signed-in retry, the
 * resume, and that {@code ON_DEMAND} launches nothing. No real {@code claude}, no real waits.
 */
class FrontDeskKeeperTest {

  private static final SessionKeeper.Timing TIMING = SessionKeeper.Timing.DEFAULT;

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

  private boolean shutdown;

  private final SessionKeeper.Scheduler scheduler =
      new SessionKeeper.Scheduler() {
        @Override
        public void schedule(Runnable step, long delayMs) {
          if (!shutdown) {
            tasks.add(new Task(now + delayMs, sequence++, step));
          }
        }

        @Override
        public void shutdown() {
          shutdown = true;
          tasks.clear();
        }
      };

  /** Advance the clock by {@code ms}, running everything that falls due on the way. */
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

  private final class FakeDesk implements FrontDeskKeeper.Desk {
    final List<AgentLaunchRequest> launches = new ArrayList<>();
    final List<Long> launchTimes = new ArrayList<>();
    final Map<String, Command> commands = new HashMap<>();
    boolean signedIn = true;
    boolean foreignLive;
    boolean transcriptPresent = true;
    final List<Long> refreshTimes = new ArrayList<>();
    boolean refreshThrows;

    @Override
    public void refreshCredentials() {
      refreshTimes.add(now);
      if (refreshThrows) {
        throw new IllegalStateException("preflight blew up");
      }
    }

    @Override
    public Command launch(AgentLaunchRequest request) {
      if (!signedIn) {
        throw new AgentNotSignedInException(AgentType.CLAUDE);
      }
      launches.add(request);
      launchTimes.add(now);
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
                  "front desk",
                  "claude",
                  true,
                  "CLAUDE",
                  request.surface().key(),
                  Instant.ofEpochMilli(now))
              .withSession(
                  new AgentSessionRef(
                      sessionId, AgentSessionSource.PINNED, null, null, Instant.ofEpochMilli(now)));
      commands.put(command.id(), command);
      return command;
    }

    @Override
    public Optional<Command> find(String commandId) {
      return Optional.ofNullable(commands.get(commandId));
    }

    @Override
    public boolean liveSessionExists() {
      return foreignLive || commands.values().stream().anyMatch(Command::isRunning);
    }

    @Override
    public boolean transcriptExists(Command command, String sessionId) {
      return transcriptPresent;
    }

    final List<String> terminated = new ArrayList<>();

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

    Command last() {
      return commands.values().stream()
          .max((a, b) -> a.launchedAt().compareTo(b.launchedAt()))
          .orElseThrow();
    }

    void exitLast(int code) {
      Command last = lastRunning();
      commands.put(
          last.id(), last.finished(CommandStatus.EXITED, code, Instant.ofEpochMilli(now)));
    }

    Command lastRunning() {
      return commands.values().stream().filter(Command::isRunning).findFirst().orElseThrow();
    }
  }

  private final FakeDesk desk = new FakeDesk();
  private final List<DaemonMessage> sent = new ArrayList<>();

  private FrontDeskKeeper keeper(Lifecycle lifecycle) {
    return new FrontDeskKeeper(lifecycle, desk, TIMING, scheduler, () -> now, sent::add);
  }

  private List<DaemonLog> logs() {
    return sent.stream().filter(DaemonLog.class::isInstance).map(DaemonLog.class::cast).toList();
  }

  // --- the tests ------------------------------------------------------------------------------

  @Test
  void alwaysOnLaunchesTheProjectWorkDeskAtBoot() {
    keeper(Lifecycle.ALWAYS_ON).start();
    advance(0);

    assertEquals(1, desk.launches.size());
    AgentLaunchRequest request = desk.launches.getFirst();
    assertEquals(
        new AgentLaunchRequest(
            AgentMcpScope.REPOSITORY,
            AgentSurface.PROJECT_WORK,
            AgentLaunchMode.INTERACTIVE,
            null,
            null,
            false,
            false,
            null,
            null,
            DaemonSecrets.SCRUB),
        request);
    assertTrue(logs().isEmpty(), "a first launch is not a relaunch");
  }

  /**
   * Delivery off: the project.work desk never holds the delivery token (qits-1495, qits-1440) nor
   * the API token (qits-1482). In legacy mode it runs on the container's own token — overlaid
   * explicitly, since it is no longer inherited — which is what it ran on before.
   */
  @Test
  void theProjectWorkLaunchCarriesTheScrubOverlay() {
    withCredential("off", null).start();
    advance(0);

    Map<String, String> overlay = desk.launches.getFirst().environment();
    assertEquals("", overlay.get("QITS_DELIVERY_TOKEN"), "an empty value removes the variable");
    assertEquals("", overlay.get("QITS_DELIVERY_TOKEN_SUBJECT"));
    assertEquals("", overlay.get("QITS_PROJECTS_DAEMON_API_TOKEN"));
    assertEquals(OWN_TOKEN, overlay.get("QITS_TOKEN"), "legacy: the container's own, explicitly");
    assertEquals(OWN_SUBJECT, overlay.get("QITS_TOKEN_SUBJECT"));
  }

  @Test
  void noDoubleLaunchWhileASessionIsLive() {
    desk.foreignLive = true; // the SPA's desk is already up
    keeper(Lifecycle.ALWAYS_ON).start();
    advance(TIMING.resetAfterMs() * 3);
    assertEquals(0, desk.launches.size(), "a live project.work session counts as the desk");

    // The SPA's session ends: the keeper takes over.
    desk.foreignLive = false;
    advance(TIMING.pollMs());
    assertEquals(1, desk.launches.size());

    // And its own live session is never launched twice either.
    advance(TIMING.resetAfterMs() * 3);
    assertEquals(1, desk.launches.size());
  }

  @Test
  void deliveryOffNeverNamesAForeignSessionAsItsOwn() {
    desk.foreignLive = true;
    FrontDeskKeeper keeper = keeper(Lifecycle.ALWAYS_ON);
    keeper.start();
    advance(TIMING.resetAfterMs());
    assertTrue(keeper.keptCommandId().isEmpty(), "the SPA's desk is watched, not kept");
  }

  // --- delivering (qits-1440) -----------------------------------------------------------------

  private static final String TOKEN = "delivery-token-value";

  private FrontDeskKeeper delivering(Lifecycle lifecycle) {
    return new FrontDeskKeeper(
        lifecycle,
        new FrontDeskCredential(
            DeliveryKeeper.Settings.parse(
                Optional.of("on"),
                Optional.of(TOKEN),
                Optional.of("desk-delivery:p-1")),
            SessionCredentialStore.withoutOwnToken()),
        desk,
        TIMING,
        scheduler,
        () -> now,
        sent::add);
  }

  @Test
  void deliveringLaunchesItsOwnDeskBesideAPersonsAndTracksIt() {
    desk.foreignLive = true; // a person's project.work session, started from the desk panel
    FrontDeskKeeper keeper = delivering(Lifecycle.ON_DEMAND);
    keeper.start();
    advance(0);

    assertEquals(1, desk.launches.size(), "a person's session is not the delivering desk");
    assertEquals(AgentSurface.PROJECT_WORK, desk.launches.getFirst().surface());
    assertEquals(overlay(TOKEN, "desk-delivery:p-1"), desk.launches.getFirst().environment());
    String first = desk.lastRunning().id();
    assertEquals(Optional.of(first), keeper.keptCommandId());

    advance(TIMING.resetAfterMs() * 3);
    assertEquals(1, desk.launches.size(), "its own live session is never launched twice");

    desk.exitLast(1);
    advance(TIMING.pollMs());
    assertTrue(keeper.keptCommandId().isEmpty(), "between the exit and the relaunch: none");
    advance(TIMING.initialBackoffMs());
    assertEquals(2, desk.launches.size(), "relaunched although the person's is still up");
    assertEquals(
        desk.commands.get(first).currentSession().sessionId(),
        desk.launches.getLast().resumeSessionId(),
        "resuming the keeper's own session");
    assertEquals(Optional.of(desk.lastRunning().id()), keeper.keptCommandId());
  }

  @Test
  void theCredentialSeamDecidesTheOverlay() {
    assertEquals(DaemonSecrets.SCRUB, FrontDeskCredential.SCRUBBED.launchOverlay());
    assertFalse(FrontDeskCredential.SCRUBBED.delivering());
    FrontDeskCredential noToken =
        new FrontDeskCredential(
            DeliveryKeeper.Settings.parse(Optional.of("on"), Optional.of(" "), Optional.empty()),
            SessionCredentialStore.withoutOwnToken());
    assertFalse(noToken.delivering());
    assertEquals(DaemonSecrets.SCRUB, noToken.launchOverlay(), "no token, no rights");
    FrontDeskCredential noSubject =
        new FrontDeskCredential(
            DeliveryKeeper.Settings.parse(Optional.of("on"), Optional.of(TOKEN), Optional.empty()),
            SessionCredentialStore.withoutOwnToken());
    assertTrue(noSubject.delivering());
    assertEquals(
        "",
        noSubject.launchOverlay().get("QITS_TOKEN_SUBJECT"),
        "a blank subject removes the container's own rather than naming a different token");
  }

  // --- session credentials (qits-1482) ---------------------------------------------------------

  /** The container's own token, which a legacy launch without the delivery rights runs on. */
  private static final String OWN_TOKEN = "qits_tok_container";

  private static final String OWN_SUBJECT = "tok-agent-container-p-1";

  private final SessionCredentialStore credentials =
      new SessionCredentialStore(() -> OWN_TOKEN, () -> OWN_SUBJECT, () -> now);

  /** A keeper over {@link #credentials}, delivery {@code delivery}, token {@code token}. */
  private FrontDeskKeeper withCredential(String delivery, String token) {
    return withCredential(Lifecycle.ALWAYS_ON, delivery, token);
  }

  private FrontDeskKeeper withCredential(Lifecycle lifecycle, String delivery, String token) {
    return new FrontDeskKeeper(
        lifecycle,
        new FrontDeskCredential(
            DeliveryKeeper.Settings.parse(
                Optional.ofNullable(delivery),
                Optional.ofNullable(token),
                Optional.of("desk-delivery:p-1")),
            credentials),
        desk,
        TIMING,
        scheduler,
        () -> now,
        sent::add);
  }

  /** The overlay a front-desk launch carries: every secret removed, then {@code token} laid on. */
  private static Map<String, String> overlay(String token, String subject) {
    Map<String, String> expected = new HashMap<>(DaemonSecrets.SCRUB);
    expected.put("QITS_TOKEN", token);
    expected.put("QITS_TOKEN_SUBJECT", subject);
    return expected;
  }

  private static SessionCredentials frame(SessionCredentials.Credential... entries) {
    return new SessionCredentials(List.of(entries));
  }

  private static SessionCredentials.Credential cred(String key, String token, String subject) {
    return new SessionCredentials.Credential(key, token, subject);
  }

  /** Legacy and delivering: exactly what qits-1440 shipped — the delivery token from the env. */
  @Test
  void legacyDeliveringRunsOnTheDeliveryTokenFromTheEnvironment() {
    withCredential("on", TOKEN).start();
    advance(0);
    assertEquals(overlay(TOKEN, "desk-delivery:p-1"), desk.launches.getFirst().environment());
  }

  /** Per-session mode: the front-desk credential, whatever the env's delivery token says. */
  @Test
  void perSessionModeLaunchesOnTheFrontDeskCredential() {
    credentials.replace(
        frame(
            cred("front-desk", "qits_tok_dd", "tok-desk-delivery-p-1"),
            cred("desk-agents", "qits_tok_desk", "tok-desk-session-p-1")));
    withCredential("on", TOKEN).start();
    advance(0);

    assertEquals(1, desk.launches.size());
    assertEquals(
        overlay("qits_tok_dd", "tok-desk-delivery-p-1"), desk.launches.getFirst().environment());
  }

  /**
   * Per-session mode needs no env token for the loop: a desk kept only for delivery launches on
   * its front-desk credential once the frame is there, and waits quietly before it.
   */
  @Test
  void deliveryWithoutAnEnvTokenWaitsForTheFrame() {
    withCredential(Lifecycle.ON_DEMAND, "on", null).start();
    advance(TIMING.pollMs() * 5);
    assertEquals(0, desk.launches.size(), "legacy without a token: no rights, no desk");
    assertTrue(logs().isEmpty());

    credentials.replace(frame(cred("front-desk", "qits_tok_dd", "tok-dd")));
    advance(TIMING.pollMs());
    assertEquals(1, desk.launches.size());
    assertEquals(overlay("qits_tok_dd", "tok-dd"), desk.launches.getFirst().environment());
  }

  /**
   * Per-session mode with no front-desk credential fails closed: no launch, a WARN naming the key,
   * the ordinary backoff ladder — never the delivery token nor the container's.
   */
  @Test
  void aMissingFrontDeskCredentialFailsClosedOnTheBackoffLadder() {
    credentials.replace(frame(cred("desk-agents", "qits_tok_desk", "tok-desk")));
    withCredential("on", TOKEN).start();
    advance(0);

    assertEquals(0, desk.launches.size(), "nothing launched without its credential");
    assertTrue(desk.refreshTimes.isEmpty(), "and not even the preflight ran");
    assertEquals(
        "front desk not launched: no session credential for 'front-desk'",
        logs().getFirst().message());
    assertFalse(logs().getFirst().message().contains("qits_tok"), "a key, never a token");

    advance(TIMING.initialBackoffMs());
    assertEquals(0, desk.launches.size());
    assertEquals(2, logs().size());

    credentials.replace(frame(cred("front-desk", "qits_tok_dd", "tok-dd")));
    advance(TIMING.initialBackoffMs() * 2 - 1);
    assertEquals(0, desk.launches.size(), "not before the doubled backoff");
    advance(1);
    assertEquals(1, desk.launches.size());
    assertEquals(overlay("qits_tok_dd", "tok-dd"), desk.launches.getFirst().environment());
  }

  /** Inside the grace window the desk waits for the frame, quietly and without a backoff step. */
  @Test
  void insideTheGraceWindowTheDeskWaitsForTheFrame() {
    credentials.openWindow(java.time.Duration.ofSeconds(20));
    withCredential("on", TOKEN).start();
    advance(10_000);
    assertEquals(0, desk.launches.size(), "no launch while qits may still send credentials");
    assertTrue(logs().isEmpty(), "waiting is not a failure");

    credentials.replace(frame(cred("front-desk", "qits_tok_dd", "tok-dd")));
    advance(TIMING.pollMs());
    assertEquals(1, desk.launches.size(), "retried on the poll, not on a backoff step");
    assertEquals(overlay("qits_tok_dd", "tok-dd"), desk.launches.getFirst().environment());
  }

  /** No frame within the window (an older qits): legacy, one poll after it closes. */
  @Test
  void afterTheGraceWindowTheDeskComesUpInLegacyMode() {
    credentials.openWindow(java.time.Duration.ofSeconds(20));
    withCredential("on", TOKEN).start();
    advance(20_000 - 1);
    assertEquals(0, desk.launches.size());
    advance(TIMING.pollMs());
    assertEquals(1, desk.launches.size());
    assertEquals(overlay(TOKEN, "desk-delivery:p-1"), desk.launches.getFirst().environment());
  }

  /**
   * A new frame that changes the front-desk credential (the loop toggled) restarts the kept
   * session: terminated, relaunched at once — no backoff — resuming its transcript, on the new
   * credential. An unchanged frame restarts nothing.
   */
  @Test
  void aChangedFrontDeskCredentialRestartsTheSessionResumingIt() {
    credentials.replace(frame(cred("front-desk", "qits_tok_session", "tok-desk-session")));
    FrontDeskKeeper keeper = withCredential("on", null);
    keeper.start();
    advance(0);
    String first = keeper.keptCommandId().orElseThrow();
    String sessionId = desk.commands.get(first).currentSession().sessionId();

    credentials.replace(frame(cred("front-desk", "qits_tok_session", "tok-desk-session")));
    advance(TIMING.pollMs() * 3);
    assertTrue(desk.terminated.isEmpty(), "the same credential again restarts nothing");

    credentials.replace(frame(cred("front-desk", "qits_tok_dd", "tok-desk-delivery")));
    advance(TIMING.pollMs());
    assertEquals(List.of(first), desk.terminated, "the kept command, terminated");
    advance(TIMING.pollMs());
    assertEquals(2, desk.launches.size(), "relaunched on the next poll, no backoff");
    assertEquals(sessionId, desk.launches.getLast().resumeSessionId(), "resuming the transcript");
    assertEquals(
        overlay("qits_tok_dd", "tok-desk-delivery"), desk.launches.getLast().environment());
    assertEquals("front desk restarted on its new session credential", logs().getLast().message());

    advance(TIMING.pollMs() * 3);
    assertEquals(1, desk.terminated.size(), "and it stays up on the new one");
  }

  /** Legacy mode reads its credential from the environment, which never changes: no restart. */
  @Test
  void legacyModeNeverRestarts() {
    FrontDeskKeeper keeper = withCredential("on", TOKEN);
    keeper.start();
    advance(TIMING.resetAfterMs() * 2);
    assertEquals(1, desk.launches.size());
    assertTrue(desk.terminated.isEmpty());
  }

  @Test
  void relaunchesAfterAnExitWithADoublingBackoffThatResets() {
    keeper(Lifecycle.ALWAYS_ON).start();
    advance(0);
    assertEquals(1, desk.launches.size());

    // A crash loop: each session dies right away. Delays go 5s, 10s, 20s, ... capped at 5 min.
    List<Long> expected =
        List.of(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L);
    for (int i = 0; i < expected.size(); i++) {
      desk.exitLast(1);
      long exitedAt = now;
      advance(TIMING.pollMs()); // the keeper notices
      long noticedAt = now;
      advance(expected.get(i) - 1);
      assertEquals(i + 1, desk.launches.size(), "not before the backoff, round " + i);
      advance(1);
      assertEquals(i + 2, desk.launches.size(), "relaunched after the backoff, round " + i);
      assertEquals(noticedAt + expected.get(i), desk.launchTimes.getLast());
      assertTrue(exitedAt <= noticedAt);
    }
    List<DaemonLog> logs = logs();
    assertEquals(expected.size(), logs.size(), "one DaemonLog per relaunch");
    assertEquals("INFO", logs.getFirst().level());
    assertEquals("front desk relaunched (exit 1)", logs.getFirst().message());

    // A session that stays up 10 minutes resets the ladder to 5 s.
    advance(TIMING.resetAfterMs());
    desk.exitLast(137);
    advance(TIMING.pollMs());
    int before = desk.launches.size();
    advance(TIMING.initialBackoffMs());
    assertEquals(before + 1, desk.launches.size(), "the backoff reset after 10 min up");
    assertEquals("front desk relaunched (exit 137)", logs().getLast().message());
  }

  @Test
  void aRelaunchResumesThePreviousSessionWhileItsTranscriptExists() {
    keeper(Lifecycle.ALWAYS_ON).start();
    advance(0);
    String first = desk.lastRunning().currentSession().sessionId();

    desk.exitLast(0);
    advance(TIMING.pollMs() + TIMING.initialBackoffMs());
    assertEquals(first, desk.launches.getLast().resumeSessionId());

    desk.transcriptPresent = false;
    desk.exitLast(0);
    advance(TIMING.pollMs() + TIMING.maxBackoffMs());
    assertEquals(3, desk.launches.size());
    assertNull(desk.launches.getLast().resumeSessionId(), "no transcript, no resume");
  }

  @Test
  void notSignedInWarnsAndRetriesEveryFiveMinutes() {
    desk.signedIn = false;
    keeper(Lifecycle.ALWAYS_ON).start();
    advance(0);
    assertEquals(0, desk.launches.size());
    assertEquals(1, logs().size());
    assertEquals("WARN", logs().getFirst().level());
    assertEquals(
        "front desk not signed in: run the login command on the runners page",
        logs().getFirst().message());

    advance(TIMING.notSignedInRetryMs() - 1);
    assertEquals(1, logs().size(), "not before five minutes");
    advance(1);
    assertEquals(2, logs().size(), "asked again after five minutes");

    desk.signedIn = true;
    advance(TIMING.notSignedInRetryMs());
    assertEquals(1, desk.launches.size(), "launches once somebody signed in");
  }

  @Test
  void theTokenIsRefreshedBeforeEveryLaunchBootAndRelaunch() {
    keeper(Lifecycle.ALWAYS_ON).start();
    advance(0);
    assertEquals(List.of(now), desk.refreshTimes, "refreshed right before the boot launch");

    desk.exitLast(1);
    advance(TIMING.pollMs() + TIMING.initialBackoffMs());
    assertEquals(2, desk.launches.size());
    assertEquals(2, desk.refreshTimes.size(), "and again before the relaunch");
    assertEquals(desk.launchTimes.getLast(), desk.refreshTimes.getLast());
  }

  @Test
  void noRefreshWhileSomebodyElsesDeskIsLive() {
    desk.foreignLive = true;
    keeper(Lifecycle.ALWAYS_ON).start();
    advance(TIMING.resetAfterMs());
    assertTrue(desk.refreshTimes.isEmpty(), "nothing to launch, nothing to refresh");
  }

  @Test
  void aFailingPreflightStillLaunches() {
    desk.refreshThrows = true;
    keeper(Lifecycle.ALWAYS_ON).start();
    advance(0);
    assertEquals(1, desk.refreshTimes.size());
    assertEquals(1, desk.launches.size(), "a preflight failure never holds the desk back");
  }

  @Test
  void onDemandLaunchesNothing() {
    keeper(Lifecycle.ON_DEMAND).start();
    advance(TIMING.resetAfterMs() * 10);
    assertEquals(0, desk.launches.size());
    assertTrue(tasks.isEmpty());
    assertTrue(sent.isEmpty());
  }

  @Test
  void stopEndsSupervision() {
    FrontDeskKeeper keeper = keeper(Lifecycle.ALWAYS_ON);
    keeper.start();
    advance(0);
    keeper.stop();
    desk.exitLast(143);
    advance(TIMING.maxBackoffMs() * 2);
    assertEquals(1, desk.launches.size(), "a stopped keeper relaunches nothing");
  }

  @Test
  void lifecycleParsesLeniently() {
    assertEquals(Lifecycle.ON_DEMAND, Lifecycle.parse(null));
    assertEquals(Lifecycle.ON_DEMAND, Lifecycle.parse(Optional.empty()));
    assertEquals(Lifecycle.ON_DEMAND, Lifecycle.parse(Optional.of(" ")));
    assertEquals(Lifecycle.ALWAYS_ON, Lifecycle.parse(Optional.of("ALWAYS_ON")));
    assertEquals(Lifecycle.ON_DEMAND, Lifecycle.parse(Optional.of("ON_DEMAND")));
    assertEquals(Lifecycle.ON_DEMAND, Lifecycle.parse(Optional.of("SOMETIMES")));
  }

  @Test
  void transcriptLookupFindsTheClaudeSessionUnderTheConfigDir(@TempDir Path mount)
      throws Exception {
    String sessionId = UUID.randomUUID().toString();
    assertFalse(FrontDeskKeeper.transcriptExists(mount.toString(), null, sessionId));
    Path dir = mount.resolve(".claude/projects/-workspace");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(sessionId + ".jsonl"), "{}\n");
    assertTrue(FrontDeskKeeper.transcriptExists(mount.toString(), null, sessionId));
    assertFalse(
        FrontDeskKeeper.transcriptExists(mount.toString(), null, UUID.randomUUID().toString()));
  }
}
