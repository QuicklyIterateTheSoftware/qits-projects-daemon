package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import eu.wohlben.qits.commands.GuardedInput;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DeliveryKeeper} against the same faked harness {@link FrontDeskKeeperTest} uses ({@link
 * FrontDeskKeeper.Desk}), a faked {@link DeliveryKeeper.Session} and a virtual clock: the on/off
 * and token gate, the {@code project.delivery} launch and its overlay, the tick when idle and the
 * skip when busy, and the ladder it shares with the front desk.
 */
class DeliveryKeeperTest {

  private static final FrontDeskKeeper.Timing TIMING = FrontDeskKeeper.Timing.DEFAULT;
  private static final Duration INTERVAL = Duration.ofMinutes(10);
  private static final String TOKEN = "delivery-token-value";
  private static final String SUBJECT = "desk-delivery:p-1";

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

  private final class FakeDesk implements FrontDeskKeeper.Desk, DeliveryKeeper.Session {
    final List<AgentLaunchRequest> launches = new ArrayList<>();
    final Map<String, Command> commands = new HashMap<>();
    final Map<String, String> states = new HashMap<>();
    final List<String> typed = new ArrayList<>();
    boolean signedIn = true;
    boolean draft;

    @Override
    public Command launch(AgentLaunchRequest request) {
      if (!signedIn) {
        throw new AgentNotSignedInException(AgentType.CLAUDE);
      }
      launches.add(request);
      Command command =
          Command.running(
                  UUID.randomUUID().toString(),
                  CommandKind.TERMINAL,
                  null,
                  null,
                  null,
                  "delivery desk",
                  "claude",
                  true,
                  "CLAUDE",
                  request.surface().key(),
                  Instant.ofEpochMilli(now))
              .withSession(
                  new AgentSessionRef(
                      UUID.randomUUID().toString(),
                      AgentSessionSource.PINNED,
                      null,
                      null,
                      Instant.ofEpochMilli(now)));
      commands.put(command.id(), command);
      // The SessionStart hook reports IDLE.
      states.put(command.id(), "IDLE");
      return command;
    }

    @Override
    public Optional<Command> find(String commandId) {
      return Optional.ofNullable(commands.get(commandId));
    }

    @Override
    public boolean liveSessionExists() {
      return liveCommandId().isPresent();
    }

    @Override
    public boolean transcriptExists(Command command, String sessionId) {
      return true;
    }

    @Override
    public Optional<String> liveCommandId() {
      return commands.values().stream()
          .filter(Command::isRunning)
          .filter(command -> "project.delivery".equals(command.agentSurface()))
          .map(Command::id)
          .findFirst();
    }

    @Override
    public Optional<String> activityState(String commandId) {
      return Optional.ofNullable(states.get(commandId));
    }

    @Override
    public GuardedInput send(String commandId, String text) {
      if (draft) {
        return GuardedInput.HELD_FOR_DRAFT;
      }
      typed.add(text);
      return GuardedInput.WRITTEN;
    }

    String live() {
      return liveCommandId().orElseThrow();
    }

    void exitLive(int code) {
      Command live = commands.get(live());
      commands.put(
          live.id(), live.finished(CommandStatus.EXITED, code, Instant.ofEpochMilli(now)));
    }
  }

  private final FakeDesk desk = new FakeDesk();
  private final List<DaemonMessage> sent = new ArrayList<>();

  private DeliveryKeeper keeper(String delivery, String token) {
    return new DeliveryKeeper(
        DeliveryKeeper.Settings.parse(
            Optional.ofNullable(delivery),
            Optional.of("PT10M"),
            Optional.ofNullable(token),
            Optional.of(SUBJECT)),
        desk,
        desk,
        TIMING,
        scheduler,
        () -> now,
        sent::add);
  }

  private List<DaemonLog> logs() {
    return sent.stream().filter(DaemonLog.class::isInstance).map(DaemonLog.class::cast).toList();
  }

  // --- the gate -------------------------------------------------------------------------------

  @Test
  void offLaunchesNothing() {
    keeper("off", TOKEN).start();
    advance(INTERVAL.toMillis() * 3);
    assertEquals(0, desk.launches.size());
    assertTrue(tasks.isEmpty(), "nothing is scheduled, not even a tick");
    assertTrue(sent.isEmpty());
  }

  @Test
  void unsetLaunchesNothing() {
    keeper(null, TOKEN).start();
    advance(INTERVAL.toMillis() * 3);
    assertEquals(0, desk.launches.size());
    assertTrue(tasks.isEmpty());
  }

  @Test
  void onWithoutATokenLaunchesNothing() {
    keeper("on", " ").start();
    advance(INTERVAL.toMillis() * 3);
    assertEquals(0, desk.launches.size());
    assertTrue(tasks.isEmpty());
  }

  // --- the launch -----------------------------------------------------------------------------

  @Test
  void onWithATokenLaunchesProjectDeliveryWithTheDeliveryTokenAsItsOwn() {
    keeper("on", TOKEN).start();
    advance(0);

    assertEquals(1, desk.launches.size());
    assertEquals(
        new AgentLaunchRequest(
            AgentMcpScope.REPOSITORY,
            AgentSurface.PROJECT_DELIVERY,
            AgentLaunchMode.INTERACTIVE,
            null,
            null,
            false,
            false,
            null,
            null,
            Map.of("QITS_TOKEN", TOKEN, "QITS_TOKEN_SUBJECT", SUBJECT)),
        desk.launches.getFirst());
  }

  @Test
  void aLiveProjectWorkSessionIsNotTheDeliveryDesk() {
    // The front desk is up; the delivery keeper's live check matches project.delivery only.
    Command work =
        Command.running(
            "work-1",
            CommandKind.TERMINAL,
            null,
            null,
            null,
            "front desk",
            "claude",
            true,
            "CLAUDE",
            "project.work",
            Instant.ofEpochMilli(now));
    desk.commands.put(work.id(), work);

    keeper("on", TOKEN).start();
    advance(0);
    assertEquals(1, desk.launches.size());
    assertEquals(AgentSurface.PROJECT_DELIVERY, desk.launches.getFirst().surface());
  }

  // --- the tick -------------------------------------------------------------------------------

  @Test
  void theTickIsTypedWhenTheDeskIsIdle() {
    keeper("on", TOKEN).start();
    advance(0);

    advance(INTERVAL.toMillis() - 1);
    assertTrue(desk.typed.isEmpty(), "not before the interval");
    advance(1);
    assertEquals(
        List.of("Run one pass of the delivery loop (tick " + Instant.ofEpochMilli(now) + ")."),
        desk.typed);

    advance(INTERVAL.toMillis());
    assertEquals(2, desk.typed.size(), "and again every interval");
  }

  @Test
  void theTickIsSkippedWhileTheDeskIsBusyAndNeverQueued() {
    keeper("on", TOKEN).start();
    advance(0);
    String live = desk.live();

    desk.states.put(live, "BUSY");
    advance(INTERVAL.toMillis());
    assertTrue(desk.typed.isEmpty(), "busy: skipped");

    // The pass finishes right after the skipped tick: nothing is owed, the next tick is on time.
    desk.states.put(live, "IDLE");
    advance(INTERVAL.toMillis() - 1);
    assertTrue(desk.typed.isEmpty(), "a skipped tick is not queued");
    advance(1);
    assertEquals(1, desk.typed.size());

    desk.states.put(live, "WAITING");
    advance(INTERVAL.toMillis());
    assertEquals(1, desk.typed.size(), "a permission prompt is not idle");

    desk.states.remove(live);
    advance(INTERVAL.toMillis());
    assertEquals(1, desk.typed.size(), "nothing heard is not idle");
  }

  @Test
  void theTickIsSkippedWhileAPersonHasADraft() {
    keeper("on", TOKEN).start();
    advance(0);
    desk.draft = true;
    advance(INTERVAL.toMillis());
    assertTrue(desk.typed.isEmpty());
  }

  @Test
  void noTickWithoutARunningDesk() {
    desk.signedIn = false;
    keeper("on", TOKEN).start();
    advance(INTERVAL.toMillis() * 2);
    assertTrue(desk.typed.isEmpty());
    assertEquals(
        "delivery desk not signed in: run the login command on the runners page",
        logs().getFirst().message());
  }

  // --- the ladder -----------------------------------------------------------------------------

  @Test
  void theBackoffDoublesAndResets() {
    keeper("on", TOKEN).start();
    advance(0);

    for (long expected : List.of(5_000L, 10_000L, 20_000L)) {
      int before = desk.launches.size();
      desk.exitLive(1);
      advance(TIMING.pollMs());
      advance(expected - 1);
      assertEquals(before, desk.launches.size(), "not before " + expected + " ms");
      advance(1);
      assertEquals(before + 1, desk.launches.size(), "relaunched after " + expected + " ms");
    }
    assertEquals("delivery desk relaunched (exit 1)", logs().getFirst().message());

    // Ten minutes up resets the ladder to 5 s.
    advance(TIMING.resetAfterMs());
    desk.exitLive(137);
    advance(TIMING.pollMs());
    int before = desk.launches.size();
    advance(TIMING.initialBackoffMs());
    assertEquals(before + 1, desk.launches.size(), "the backoff reset after 10 min up");
    assertEquals(
        Map.of("QITS_TOKEN", TOKEN, "QITS_TOKEN_SUBJECT", SUBJECT),
        desk.launches.getLast().environment(),
        "every relaunch carries the overlay");
  }

  @Test
  void stopEndsSupervisionAndTicks() {
    DeliveryKeeper keeper = keeper("on", TOKEN);
    keeper.start();
    advance(0);
    keeper.stop();
    desk.exitLive(143);
    advance(INTERVAL.toMillis() * 2);
    assertEquals(1, desk.launches.size());
    assertTrue(desk.typed.isEmpty());
  }

  // --- the settings ---------------------------------------------------------------------------

  @Test
  void settingsParseLeniently() {
    assertFalse(parse(null, null).enabled());
    assertFalse(parse("off", null).enabled());
    assertFalse(parse("sometimes", null).enabled(), "a typo is off");
    assertTrue(parse("on", null).enabled());
    assertTrue(parse("ON", null).enabled());

    assertEquals(DeliveryKeeper.DEFAULT_INTERVAL, parse("on", null).interval());
    assertEquals(Duration.ofMinutes(10), DeliveryKeeper.DEFAULT_INTERVAL);
    assertEquals(Duration.ofMinutes(2), parse("on", "PT2M").interval());
    assertEquals(DeliveryKeeper.DEFAULT_INTERVAL, parse("on", "ten minutes").interval());
    assertEquals(DeliveryKeeper.DEFAULT_INTERVAL, parse("on", "PT0S").interval());

    DeliveryKeeper.Settings noSubject =
        DeliveryKeeper.Settings.parse(
            Optional.of("on"), Optional.empty(), Optional.of(TOKEN), Optional.empty());
    assertEquals(
        Map.of("QITS_TOKEN", TOKEN, "QITS_TOKEN_SUBJECT", ""),
        noSubject.environment(),
        "a blank subject removes the container's own rather than naming a different token");
  }

  private static DeliveryKeeper.Settings parse(String delivery, String interval) {
    return DeliveryKeeper.Settings.parse(
        Optional.ofNullable(delivery),
        Optional.ofNullable(interval),
        Optional.of(TOKEN),
        Optional.empty());
  }
}
