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

  private static final FrontDeskKeeper.Timing TIMING = FrontDeskKeeper.Timing.DEFAULT;

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

  private final FrontDeskKeeper.Scheduler scheduler =
      new FrontDeskKeeper.Scheduler() {
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
            null),
        request);
    assertTrue(logs().isEmpty(), "a first launch is not a relaunch");
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
