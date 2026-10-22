package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentConfigurationDocument;
import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentNotSignedInException;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.AgentSessionSource;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.GuardedInput;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Lifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * The delivery loop as turns in the front desk session (qits-1440): {@link FrontDeskKeeper} with the
 * {@link FrontDeskCredential} built from the delivery settings, and the {@link DeliveryKeeper} tick
 * over the keeper's own command — wired the way {@link ControlSocket} wires them, against a faked
 * harness and a virtual clock. The gate, the launch overlay, the keeper's own session beside a
 * person's, the tick when idle and the skip otherwise, and the brief.
 */
class DeliveryKeeperTest {

  private static final FrontDeskKeeper.Timing TIMING = FrontDeskKeeper.Timing.DEFAULT;
  private static final Duration INTERVAL = Duration.ofMinutes(10);
  private static final String TOKEN = "delivery-token-value";
  private static final String SUBJECT = "desk-delivery:p-1";

  /** What the front desk launches with while delivering. */
  private static final Map<String, String> DELIVERY_OVERLAY =
      Map.of(
          "QITS_TOKEN",
          TOKEN,
          "QITS_TOKEN_SUBJECT",
          SUBJECT,
          "QITS_DELIVERY_TOKEN",
          "",
          "QITS_DELIVERY_TOKEN_SUBJECT",
          "");

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

  /** One virtual worker per component, as production gives each its own thread. */
  private final class VirtualScheduler implements FrontDeskKeeper.Scheduler {
    private boolean shutdown;

    @Override
    public void schedule(Runnable step, long delayMs) {
      if (!shutdown) {
        tasks.add(new Task(now + delayMs, sequence++, () -> runUnlessShutdown(step)));
      }
    }

    private void runUnlessShutdown(Runnable step) {
      if (!shutdown) {
        step.run();
      }
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }
  }

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
    final List<String> typedInto = new ArrayList<>();
    final List<String> typed = new ArrayList<>();
    boolean signedIn = true;
    boolean draft;

    @Override
    public Command launch(AgentLaunchRequest request) {
      if (!signedIn) {
        throw new AgentNotSignedInException(AgentType.CLAUDE);
      }
      launches.add(request);
      Command command = session(request.surface().key());
      // The SessionStart hook reports IDLE.
      states.put(command.id(), "IDLE");
      return command;
    }

    Command session(String surface) {
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
                  surface,
                  Instant.ofEpochMilli(now))
              .withSession(
                  new AgentSessionRef(
                      UUID.randomUUID().toString(),
                      AgentSessionSource.PINNED,
                      null,
                      null,
                      Instant.ofEpochMilli(now)));
      commands.put(command.id(), command);
      return command;
    }

    @Override
    public Optional<Command> find(String commandId) {
      return Optional.ofNullable(commands.get(commandId));
    }

    @Override
    public boolean liveSessionExists() {
      return commands.values().stream()
          .anyMatch(command -> command.isRunning() && "project.work".equals(command.agentSurface()));
    }

    @Override
    public boolean transcriptExists(Command command, String sessionId) {
      return true;
    }

    @Override
    public boolean running(String commandId) {
      Command command = commands.get(commandId);
      return command != null && command.isRunning();
    }

    /** When set, the states come from a real webhook's store instead of {@link #states}. */
    HookWebhook hooks;

    @Override
    public Optional<String> activityState(String commandId) {
      return hooks != null ? hooks.lastState(commandId) : Optional.ofNullable(states.get(commandId));
    }

    @Override
    public GuardedInput send(String commandId, String text) {
      if (draft) {
        return GuardedInput.HELD_FOR_DRAFT;
      }
      typedInto.add(commandId);
      typed.add(text);
      return GuardedInput.WRITTEN;
    }

    void exit(String commandId, int code) {
      Command live = commands.get(commandId);
      commands.put(
          live.id(), live.finished(CommandStatus.EXITED, code, Instant.ofEpochMilli(now)));
    }
  }

  private final FakeDesk desk = new FakeDesk();

  private FrontDeskKeeper frontDesk;
  private DeliveryKeeper tick;

  /** Both halves, wired as {@link ControlSocket#wireAgents} wires them, and started. */
  private void start(Lifecycle lifecycle, String delivery, String token) {
    DeliveryKeeper.Settings settings =
        DeliveryKeeper.Settings.parse(
            Optional.ofNullable(delivery),
            Optional.of("PT10M"),
            Optional.ofNullable(token),
            Optional.of(SUBJECT));
    frontDesk =
        new FrontDeskKeeper(
            lifecycle,
            new FrontDeskCredential(settings),
            desk,
            TIMING,
            new VirtualScheduler(),
            () -> now,
            message -> {});
    tick =
        new DeliveryKeeper(
            settings, frontDesk::keptCommandId, desk, new VirtualScheduler(), () -> now);
    frontDesk.start();
    tick.start();
  }

  private String kept() {
    return frontDesk.keptCommandId().orElseThrow();
  }

  // --- the gate -------------------------------------------------------------------------------

  @Test
  void offOnDemandLaunchesAndTicksNothing() {
    start(Lifecycle.ON_DEMAND, "off", TOKEN);
    advance(INTERVAL.toMillis() * 3);
    assertEquals(0, desk.launches.size());
    assertTrue(tasks.isEmpty(), "nothing is scheduled, not even a tick");
  }

  @Test
  void offAlwaysOnLaunchesTheScrubbedFrontDeskAndNeverTicksIt() {
    start(Lifecycle.ALWAYS_ON, null, TOKEN);
    advance(INTERVAL.toMillis() * 3);
    assertEquals(1, desk.launches.size());
    assertEquals(DeliveryCredentials.SCRUB, desk.launches.getFirst().environment());
    assertTrue(desk.typed.isEmpty(), "delivery off: no tick");
  }

  @Test
  void onWithoutATokenIsOff() {
    start(Lifecycle.ON_DEMAND, "on", " ");
    advance(INTERVAL.toMillis() * 3);
    assertEquals(0, desk.launches.size());
    assertTrue(tasks.isEmpty());
  }

  // --- the launch -----------------------------------------------------------------------------

  @Test
  void onWithATokenLaunchesTheFrontDeskWithTheDeliveryToken() {
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);

    assertEquals(1, desk.launches.size());
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
            DELIVERY_OVERLAY),
        desk.launches.getFirst());
  }

  @Test
  void deliveryKeepsTheFrontDeskEvenOnDemandAndNeverLaunchesProjectDelivery() {
    start(Lifecycle.ON_DEMAND, "on", TOKEN);
    advance(0);
    assertEquals(1, desk.launches.size(), "the loop needs a desk to run in");

    String first = kept();
    desk.exit(first, 1);
    advance(TIMING.pollMs() + TIMING.initialBackoffMs());
    assertEquals(2, desk.launches.size(), "and it is kept, not launched once");
    assertNotEquals(first, kept());

    advance(INTERVAL.toMillis() * 3);
    assertTrue(
        desk.launches.stream().allMatch(r -> r.surface().equals(AgentSurface.PROJECT_WORK)),
        "one desk, one session: nothing launches project.delivery");
    assertTrue(
        desk.launches.stream().allMatch(r -> r.environment().equals(DELIVERY_OVERLAY)),
        "every relaunch carries the delivery overlay");
  }

  @Test
  void aPersonsProjectWorkSessionDoesNotStopTheKeeperAndNeverGetsTheTick() {
    // A person opened the desk panel first. Their session is scrubbed and holds no delivery rights.
    Command person = desk.session("project.work");
    desk.states.put(person.id(), "IDLE");

    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);
    assertEquals(1, desk.launches.size(), "the keeper launches its own beside the person's");
    assertNotEquals(person.id(), kept());

    advance(INTERVAL.toMillis());
    assertEquals(List.of(kept()), desk.typedInto, "the tick goes to the keeper's command only");

    // The person's session ending changes nothing for the keeper's.
    desk.exit(person.id(), 0);
    advance(TIMING.resetAfterMs());
    assertEquals(1, desk.launches.size());
  }

  // --- the tick -------------------------------------------------------------------------------

  @Test
  void theTickIsTypedWhenTheDeskIsIdle() {
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);

    advance(INTERVAL.toMillis() - 1);
    assertTrue(desk.typed.isEmpty(), "not before the interval");
    advance(1);
    assertEquals(
        List.of(
            "Run one pass of the delivery loop: read /tmp/qits-delivery-brief.md and follow it."
                + " (tick "
                + Instant.ofEpochMilli(now)
                + ")"),
        desk.typed);

    advance(INTERVAL.toMillis());
    assertEquals(2, desk.typed.size(), "and again every interval");
  }

  @Test
  void theTickIsSkippedWhileTheDeskIsBusyAndNeverQueued() {
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);
    String live = kept();

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
  void theFirstTwoConsecutiveSkipsDoNotEscalate() {
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);
    String live = kept();

    desk.states.put(live, "BUSY");
    advance(INTERVAL.toMillis());
    assertEquals(1, tick.consecutiveSkips());

    advance(INTERVAL.toMillis());
    assertEquals(2, tick.consecutiveSkips(), "two in a row: still not escalated");
  }

  @Test
  void theThirdConsecutiveSkipEscalates() {
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);
    String live = kept();

    desk.states.put(live, "BUSY");
    advance(INTERVAL.toMillis());
    advance(INTERVAL.toMillis());
    advance(INTERVAL.toMillis());
    assertEquals(3, tick.consecutiveSkips(), "the third skip in a row");
  }

  @Test
  void aWriteResetsTheConsecutiveSkipCount() {
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);
    String live = kept();

    desk.states.put(live, "BUSY");
    advance(INTERVAL.toMillis());
    advance(INTERVAL.toMillis());
    assertEquals(2, tick.consecutiveSkips());

    desk.states.put(live, "IDLE");
    advance(INTERVAL.toMillis());
    assertEquals(0, tick.consecutiveSkips(), "a written tick resets the count");

    desk.states.put(live, "BUSY");
    advance(INTERVAL.toMillis());
    advance(INTERVAL.toMillis());
    assertEquals(2, tick.consecutiveSkips(), "counting again from zero, not from the old run");
  }

  @Test
  void anIdlePromptAfterAPassDoesNotStopTheNextTick() {
    // Measured live (qits-1440): Claude Code posts an idle_prompt Notification about a minute after
    // every finished turn. It must leave the desk IDLE, or every tick after the first is skipped.
    desk.hooks = new HookWebhook(null, 13337, message -> {});
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);
    String live = kept();
    desk.hooks.handle("SessionStart", new io.vertx.core.json.JsonObject(), live);

    advance(INTERVAL.toMillis());
    assertEquals(1, desk.typed.size());
    desk.hooks.handle("UserPromptSubmit", new io.vertx.core.json.JsonObject(), live);
    desk.hooks.handle("Stop", new io.vertx.core.json.JsonObject(), live);
    desk.hooks.handle(
        "Notification",
        new io.vertx.core.json.JsonObject()
            .put("hook_event_name", "Notification")
            .put("notification_type", "idle_prompt")
            .put("message", "Claude is waiting for your input"),
        live);

    advance(INTERVAL.toMillis());
    assertEquals(2, desk.typed.size(), "the next tick is sent");
  }

  @Test
  void theTickIsSkippedWhileAPersonHasADraft() {
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);
    desk.draft = true;
    advance(INTERVAL.toMillis());
    assertTrue(desk.typed.isEmpty());
  }

  @Test
  void noTickWhileTheKeeperHasNoDesk() {
    desk.signedIn = false;
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(INTERVAL.toMillis() * 2);
    assertTrue(frontDesk.keptCommandId().isEmpty());
    assertTrue(desk.typed.isEmpty(), "never launched, never typed into");
  }

  @Test
  void aCommandThatIsNotRunningIsNotTypedInto() {
    // The keeper still names a command that exited between its polls: the tick checks it anyway.
    Command exited = desk.session("project.work");
    desk.states.put(exited.id(), "IDLE");
    desk.exit(exited.id(), 0);
    new DeliveryKeeper(
            DeliveryKeeper.Settings.parse(
                Optional.of("on"), Optional.of("PT1M"), Optional.of(TOKEN), Optional.empty()),
            () -> Optional.of(exited.id()),
            desk,
            new VirtualScheduler(),
            () -> now)
        .start();
    advance(Duration.ofMinutes(3).toMillis());
    assertTrue(desk.typed.isEmpty());
  }

  @Test
  void stopEndsTicks() {
    start(Lifecycle.ALWAYS_ON, "on", TOKEN);
    advance(0);
    tick.stop();
    advance(INTERVAL.toMillis() * 2);
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
    assertFalse(DeliveryKeeper.Settings.OFF.runnable());

    assertEquals(DeliveryKeeper.DEFAULT_INTERVAL, parse("on", null).interval());
    assertEquals(Duration.ofMinutes(10), DeliveryKeeper.DEFAULT_INTERVAL);
    assertEquals(Duration.ofMinutes(2), parse("on", "PT2M").interval());
    assertEquals(DeliveryKeeper.DEFAULT_INTERVAL, parse("on", "ten minutes").interval());
    assertEquals(DeliveryKeeper.DEFAULT_INTERVAL, parse("on", "PT0S").interval());
  }

  private static DeliveryKeeper.Settings parse(String delivery, String interval) {
    return DeliveryKeeper.Settings.parse(
        Optional.ofNullable(delivery),
        Optional.ofNullable(interval),
        Optional.of(TOKEN),
        Optional.empty());
  }

  // --- the brief ------------------------------------------------------------------------------

  private static AgentSurfaceConfigurations document(String deliveryPrompt) {
    return AgentSurfaceConfigurations.of(
        AgentConfigurationDocument.parse(
            """
            {
              "version": 2,
              "generatedAt": "2026-10-22T10:00:00Z",
              "surfaces": [
                {
                  "configuration": {
                    "surface": "project.delivery",
                    "harness": "CLAUDE",
                    "model": "",
                    "effort": "",
                    "remoteControl": true,
                    "permissionMode": "SKIP_PERMISSIONS",
                    "activityTracking": true,
                    "systemPrompt": %s,
                    "initialPrompt": "",
                    "mcpServers": []
                  }
                }
              ]
            }
            """
                .formatted(deliveryPrompt),
            "test"));
  }

  @Test
  void theBriefIsTheDeliverySurfacesPrompt(@TempDir Path dir) throws Exception {
    Path brief = dir.resolve("qits-delivery-brief.md");
    DeliveryKeeper.writeBrief(brief, document("\"You are the delivery desk.\\nShip it.\""), "qits");

    assertEquals("You are the delivery desk.\nShip it.\n", Files.readString(brief));
    assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(brief)));
  }

  @Test
  void theBriefFallsBackWhenTheDocumentCarriesNone(@TempDir Path dir) throws Exception {
    Path brief = dir.resolve("qits-delivery-brief.md");

    DeliveryKeeper.writeBrief(brief, AgentSurfaceConfigurations.shipped(), "qits");
    String fallback = Files.readString(brief);
    assertTrue(fallback.contains("qits projects delivery --project qits"), fallback);
    assertTrue(fallback.contains("one pass"), fallback);

    DeliveryKeeper.writeBrief(brief, document("\"  \""), "qits");
    assertEquals(fallback, Files.readString(brief), "a blank prompt is no prompt");
  }

  @Test
  void theTickTurnPointsAtTheBrief() {
    assertEquals(Path.of("/tmp/qits-delivery-brief.md"), DeliveryKeeper.BRIEF_PATH);
    assertTrue(
        DeliveryKeeper.tickTurn(Instant.EPOCH).length() < 800, "a turn stays under the limit");
  }
}
