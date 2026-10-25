package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.commands.GuardedInput;
import eu.wohlben.qits.projectsdaemon.FrontDeskInbox.Kind;
import eu.wohlben.qits.projectsdaemon.FrontDeskInbox.Outcome;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The front desk's inbox (qits-1572): a turn typed at once into an idle desk, queued behind a busy
 * one and flushed one per {@code IDLE}, held for a draft, refused without a desk or past capacity;
 * and the last answer read out of a raw transcript. Activity comes from a real {@link HookWebhook}, wired as {@link
 * ControlSocket#wireAgents} wires it.
 */
class FrontDeskInboxTest {

  private static final String KEPT = "cmd-kept";

  private long now = 1_000_000;

  /** The keeper's command, or none. */
  private Optional<String> kept = Optional.of(KEPT);

  private final HookWebhook hooks = new HookWebhook(null, 13337, message -> {});

  private final class FakeSession implements DeliveryKeeper.Session {
    final Set<String> running = new HashSet<>(Set.of(KEPT));
    final List<String> typed = new ArrayList<>();
    boolean draft;

    @Override
    public boolean running(String commandId) {
      return running.contains(commandId);
    }

    @Override
    public Optional<String> activityState(String commandId) {
      return hooks.lastState(commandId);
    }

    @Override
    public GuardedInput send(String commandId, String text) {
      if (!running.contains(commandId)) {
        return GuardedInput.NOT_RUNNING;
      }
      if (draft) {
        return GuardedInput.HELD_FOR_DRAFT;
      }
      typed.add(text);
      return GuardedInput.WRITTEN;
    }
  }

  /** Runs every flush at once, on the caller's thread. */
  private static final class DirectScheduler implements SessionKeeper.Scheduler {
    @Override
    public void schedule(Runnable step, long delayMs) {
      step.run();
    }

    @Override
    public void shutdown() {}
  }

  private final FakeSession session = new FakeSession();
  private List<String> transcript = List.of();

  private final FrontDeskInbox inbox =
      new FrontDeskInbox(
          () -> kept, session, commandId -> transcript, new DirectScheduler(), () -> now);

  {
    hooks.setActivityListener(inbox::onActivity);
  }

  private void hook(String event) {
    hooks.handle(event, new JsonObject().put("session_id", "sess-1"), KEPT);
  }

  /** One finished turn: the prompt starts it, the Stop ends it. */
  private void turn() {
    hook("UserPromptSubmit");
    hook("Stop");
  }

  @Test
  void anIdleDeskIsTypedIntoAtOnce() {
    hook("SessionStart");

    Outcome outcome = inbox.prompt("What is on the board?");

    assertEquals(new Outcome(Kind.DELIVERED, KEPT, "IDLE", null), outcome);
    assertEquals(List.of("What is on the board?"), session.typed);
    assertEquals(0, inbox.queued());
  }

  @Test
  void aBusyDeskQueuesAndEveryIdleFlushesOneTurn() {
    hook("SessionStart");
    hook("UserPromptSubmit");

    assertEquals(new Outcome(Kind.QUEUED, KEPT, "BUSY", 1), inbox.prompt("first"));
    assertEquals(new Outcome(Kind.QUEUED, KEPT, "BUSY", 2), inbox.prompt("second"));
    assertTrue(session.typed.isEmpty(), "nothing is typed into a busy desk");

    hook("Stop");
    assertEquals(List.of("first"), session.typed, "one IDLE, one turn");
    assertEquals(1, inbox.queued());

    turn();
    assertEquals(List.of("first", "second"), session.typed);
    assertEquals(0, inbox.queued());
    assertTrue(inbox.pending(), "typed, not yet started");

    turn();
    assertEquals(2, session.typed.size(), "an empty queue flushes nothing");
    assertFalse(inbox.pending());
  }

  @Test
  void aQueuedTurnIsKeptBehindTheOnesBeforeItEvenWhenTheDeskIsIdle() {
    hook("SessionStart");
    session.draft = true;

    assertEquals(Kind.QUEUED, inbox.prompt("first").kind(), "a draft holds it");
    session.draft = false;
    assertEquals(
        new Outcome(Kind.QUEUED, KEPT, "IDLE", 2),
        inbox.prompt("second"),
        "FIFO: the second never overtakes the first");
    assertTrue(session.typed.isEmpty());
  }

  @Test
  void aTurnHeldForADraftStaysQueuedForTheNextIdle() {
    hook("SessionStart");
    hook("UserPromptSubmit");
    inbox.prompt("queued");

    session.draft = true;
    hook("Stop");
    assertTrue(session.typed.isEmpty(), "a person's draft is never typed over");
    assertEquals(1, inbox.queued(), "and the turn keeps its place");

    session.draft = false;
    hooks.handle(
        "Notification",
        new JsonObject().put("notification_type", "idle_prompt").put("session_id", "sess-1"),
        KEPT);
    assertEquals(List.of("queued"), session.typed, "the next IDLE flushes it");
    assertEquals(0, inbox.queued());
  }

  @Test
  void aSecondPromptWhileTheFirstHasNotStartedIsQueued() {
    hook("SessionStart");
    assertEquals(Kind.DELIVERED, inbox.prompt("first").kind());

    // No UserPromptSubmit yet: the stored state still reads IDLE.
    assertEquals(new Outcome(Kind.QUEUED, KEPT, "IDLE", 1), inbox.prompt("second"));
    assertEquals(List.of("first"), session.typed);

    turn();
    assertEquals(List.of("first", "second"), session.typed);
  }

  @Test
  void aTypedTurnStopsBlockingWhenNoHookConfirmsIt() {
    hook("SessionStart");
    inbox.prompt("first");
    assertTrue(inbox.pending());

    now += FrontDeskInbox.START_GRACE_MS;
    assertFalse(inbox.pending(), "a hook that never came does not wedge the desk");
    assertEquals(Kind.DELIVERED, inbox.prompt("second").kind());
  }

  @Test
  void noKeptCommandIsNoDesk() {
    kept = Optional.empty();
    assertEquals(new Outcome(Kind.NO_DESK, null, null, null), inbox.prompt("hello"));

    kept = Optional.of("cmd-exited");
    assertEquals(Kind.NO_DESK, inbox.prompt("hello").kind(), "named but not running");
    assertEquals(0, inbox.queued(), "nothing is queued for a desk that is not there");
  }

  @Test
  void aFullQueueRefusesTheNextTurn() {
    hook("SessionStart");
    hook("UserPromptSubmit");
    for (int i = 1; i <= FrontDeskInbox.CAPACITY; i++) {
      assertEquals(i, inbox.prompt("turn " + i).position());
    }

    assertEquals(new Outcome(Kind.FULL, KEPT, "BUSY", null), inbox.prompt("one too many"));
    assertEquals(FrontDeskInbox.CAPACITY, inbox.queued());
  }

  @Test
  void anotherCommandsActivityFlushesNothing() {
    hook("SessionStart");
    hook("UserPromptSubmit");
    inbox.prompt("queued");

    hooks.handle("Stop", new JsonObject(), "cmd-a-persons-session");
    assertTrue(session.typed.isEmpty());
  }

  // --- the last answer ------------------------------------------------------------------------

  private static List<String> fixture() throws IOException {
    try (InputStream in =
        FrontDeskInboxTest.class.getResourceAsStream("/front-desk-transcript.jsonl")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  @Test
  void theLastAnswerIsTheTextAfterTheLastRealPrompt() throws IOException {
    hook("SessionStart");
    transcript = fixture();

    FrontDeskInbox.Answer answer = inbox.lastAnswer().orElseThrow();

    assertEquals(KEPT, answer.commandId());
    assertEquals("sess-1", answer.sessionId());
    assertEquals("IDLE", answer.state());
    assertEquals(
        "Checking the board.\n\nOne ticket is in progress: qits-1572.",
        answer.text(),
        "text blocks only: no thinking, no tool_use, nothing before the prompt");
    assertEquals("2026-10-24T09:10:09.000Z", answer.at());
  }

  @Test
  void aPromptWithNoAnswerYetHasNoText() throws IOException {
    List<String> lines = new ArrayList<>(fixture());
    lines.add(
        "{\"type\":\"user\",\"sessionId\":\"sess-1\",\"timestamp\":\"2026-10-24T09:20:00Z\","
            + "\"message\":{\"role\":\"user\",\"content\":\"And now?\"}}");
    transcript = lines;

    FrontDeskInbox.Answer answer = inbox.lastAnswer().orElseThrow();

    assertNull(answer.text());
    assertNull(answer.at());
    assertEquals("sess-1", answer.sessionId());
    assertNull(answer.state(), "no hook heard yet");
  }

  @Test
  void noKeptCommandHasNoAnswer() {
    kept = Optional.empty();
    assertTrue(inbox.lastAnswer().isEmpty());
  }
}
