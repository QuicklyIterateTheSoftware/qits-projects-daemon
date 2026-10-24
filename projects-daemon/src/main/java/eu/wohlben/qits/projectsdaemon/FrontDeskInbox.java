package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.commands.GuardedInput;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Scheduler;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.AgentState;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Turns typed into the front desk on qits' behalf (qits-1572): {@code POST /front-desk/prompt}
 * lands here, and {@code GET /front-desk/last-answer} reads the desk's answer back.
 *
 * <p><b>Only the keeper's own session.</b> Like {@link DeliveryKeeper}'s tick, a prompt goes to
 * {@link FrontDeskKeeper#keptCommandId()} and never to a {@code project.work} session a person
 * started. No kept command running: {@link Kind#NO_DESK}.
 *
 * <p><b>Typed now, or queued.</b> A prompt is typed at once when the desk is RUNNING, its last hook
 * state is exactly {@code IDLE}, nothing is queued ahead of it, no turn the inbox typed is still
 * waiting to start, and no person holds an unsent draft there. Anything else appends it to a FIFO
 * of {@link #CAPACITY}; a full FIFO answers {@link Kind#FULL}. Every {@code IDLE} activity of the
 * kept command ({@link #onActivity}, fed by {@link HookWebhook}) flushes exactly one queued turn;
 * one held for a draft stays at the head for the next {@code IDLE}. The queue survives a relaunch
 * of the desk: the new session's {@code SessionStart} is an {@code IDLE} like any other.
 *
 * <p><b>Typed but not started.</b> Between the keystrokes and the {@code UserPromptSubmit} that
 * turns the desk {@code BUSY} the stored state still reads {@code IDLE}. A second write in that
 * gap would land in the first turn's input, so a typed turn counts as pending until any non-{@code
 * IDLE} activity arrives, or {@link #START_GRACE_MS} pass (a hook that never comes must not wedge
 * the desk).
 *
 * <p><b>One writer at a time.</b> The inbox and the delivery tick never interleave keystrokes:
 * {@link DeliveryKeeper} runs its whole check-and-type under {@link #exclusive}, the same monitor
 * every inbox write holds, and skips its tick (reason {@code inbox}) while {@link #pending()}.
 */
final class FrontDeskInbox implements DeliveryKeeper.Inbox {

  private static final Logger LOG = Logger.getLogger(FrontDeskInbox.class);

  /** How many turns may wait for the desk. */
  static final int CAPACITY = 16;

  /** How long a typed turn may go without a hook confirming it started before it stops blocking. */
  static final long START_GRACE_MS = 30_000;

  /** What became of one prompt. */
  enum Kind {
    DELIVERED,
    QUEUED,
    FULL,
    NO_DESK
  }

  /**
   * One prompt's outcome.
   *
   * @param commandId the kept command, null for {@link Kind#NO_DESK}
   * @param state its last hook state, null when none was heard
   * @param position the 1-based place in the queue for {@link Kind#QUEUED}, else null
   */
  record Outcome(Kind kind, String commandId, String state, Integer position) {}

  /**
   * The desk's latest answer: the assistant text after the last real user prompt.
   *
   * @param text null when there is none yet
   * @param at the timestamp of the last entry the text came from, null with it
   * @param sessionId the Claude session, when the transcript names one
   */
  record Answer(String commandId, String sessionId, String state, String text, String at) {}

  private final Supplier<Optional<String>> frontDesk;
  private final DeliveryKeeper.Session session;
  private final Function<String, List<String>> transcript;
  private final Scheduler scheduler;
  private final LongSupplier clock;

  private final Object lock = new Object();

  // Guarded by lock.
  private final Deque<String> queue = new ArrayDeque<>();
  private long typedAt = -1;

  /**
   * @param frontDesk the keeper's own command ({@link FrontDeskKeeper#keptCommandId()})
   * @param session the same seam {@link DeliveryKeeper} types through
   * @param transcript the raw TRANSCRIPT channel lines of a command, oldest first
   * @param scheduler where a flush runs, off the hook webhook's event loop
   */
  FrontDeskInbox(
      Supplier<Optional<String>> frontDesk,
      DeliveryKeeper.Session session,
      Function<String, List<String>> transcript,
      Scheduler scheduler,
      LongSupplier clock) {
    this.frontDesk = frontDesk;
    this.session = session;
    this.transcript = transcript;
    this.scheduler = scheduler;
    this.clock = clock;
  }

  /** Type {@code text} into the front desk now if it is idle, else queue it. */
  Outcome prompt(String text) {
    synchronized (lock) {
      Optional<String> kept = frontDesk.get();
      if (kept.isEmpty() || !session.running(kept.get())) {
        return new Outcome(Kind.NO_DESK, null, null, null);
      }
      String commandId = kept.get();
      String state = session.activityState(commandId).orElse(null);
      if (queue.isEmpty() && !awaitingStart() && AgentState.IDLE.equalsIgnoreCase(state)) {
        GuardedInput written = session.send(commandId, text);
        if (written == GuardedInput.WRITTEN) {
          typedAt = clock.getAsLong();
          LOG.debugf("Front desk prompt typed into %s.", commandId);
          return new Outcome(Kind.DELIVERED, commandId, state, null);
        }
        if (written == GuardedInput.NOT_RUNNING) {
          return new Outcome(Kind.NO_DESK, null, null, null);
        }
        // Held for a person's draft: it waits its turn like a busy desk's would.
      }
      if (queue.size() >= CAPACITY) {
        LOG.infof("Front desk prompt refused: the inbox is full (%d turns).", CAPACITY);
        return new Outcome(Kind.FULL, commandId, state, null);
      }
      queue.addLast(text);
      LOG.debugf("Front desk prompt queued at %d (desk %s).", queue.size(), state);
      return new Outcome(Kind.QUEUED, commandId, state, queue.size());
    }
  }

  /**
   * One activity of a command, as {@link HookWebhook} stores it. Only the kept command's matter: a
   * non-IDLE state confirms a typed turn started, an IDLE one flushes one queued turn (off the
   * caller's thread — the webhook answers on the event loop).
   */
  void onActivity(String commandId, String state) {
    if (commandId == null || !frontDesk.get().map(commandId::equals).orElse(false)) {
      return;
    }
    if (!AgentState.IDLE.equalsIgnoreCase(state)) {
      synchronized (lock) {
        typedAt = -1;
      }
      return;
    }
    scheduler.schedule(this::flushOne, 0);
  }

  /** Type the head of the queue, if the desk is idle; keep it when a draft holds it back. */
  void flushOne() {
    synchronized (lock) {
      if (queue.isEmpty() || awaitingStart()) {
        return;
      }
      Optional<String> kept = frontDesk.get();
      if (kept.isEmpty() || !session.running(kept.get())) {
        return;
      }
      String commandId = kept.get();
      if (!session
          .activityState(commandId)
          .map(AgentState.IDLE::equalsIgnoreCase)
          .orElse(false)) {
        return;
      }
      GuardedInput written = session.send(commandId, queue.peekFirst());
      if (written == GuardedInput.WRITTEN) {
        queue.removeFirst();
        typedAt = clock.getAsLong();
        LOG.debugf(
            "Front desk queued turn typed into %s; %d still queued.", commandId, queue.size());
      } else {
        LOG.debugf("Front desk queued turn kept: %s.", written);
      }
    }
  }

  @Override
  public boolean pending() {
    synchronized (lock) {
      return !queue.isEmpty() || awaitingStart();
    }
  }

  @Override
  public void typed() {
    synchronized (lock) {
      typedAt = clock.getAsLong();
    }
  }

  @Override
  public <T> T exclusive(Supplier<T> action) {
    synchronized (lock) {
      return action.get();
    }
  }

  /** How many turns wait. For a test and the log. */
  int queued() {
    synchronized (lock) {
      return queue.size();
    }
  }

  private boolean awaitingStart() {
    if (typedAt < 0) {
      return false;
    }
    if (clock.getAsLong() - typedAt >= START_GRACE_MS) {
      typedAt = -1;
      return false;
    }
    return true;
  }

  /** The kept command's latest answer, or empty when there is no kept command. */
  Optional<Answer> lastAnswer() {
    Optional<String> kept = frontDesk.get();
    if (kept.isEmpty()) {
      return Optional.empty();
    }
    String commandId = kept.get();
    String state = session.activityState(commandId).orElse(null);
    List<String> lines;
    try {
      lines = transcript.apply(commandId);
    } catch (RuntimeException e) {
      LOG.debugf("Could not read the front desk transcript: %s", e.getMessage());
      lines = List.of();
    }
    return Optional.of(answerOf(commandId, state, lines == null ? List.of() : lines));
  }

  /**
   * The answer in a raw Claude JSONL transcript: the {@code text} blocks of the main-chain
   * assistant entries after the last real user prompt, joined by a blank line. A user entry made
   * only of {@code tool_result}s is not a prompt, and neither is a meta or sidechain one; {@code
   * tool_use} and {@code thinking} blocks are never part of the answer. Unreadable lines are
   * skipped.
   */
  static Answer answerOf(String commandId, String state, List<String> lines) {
    List<String> texts = new ArrayList<>();
    String at = null;
    String sessionId = null;
    for (String line : lines) {
      JsonObject entry = parse(line);
      if (entry == null || entry.getBoolean("isSidechain", false)) {
        continue;
      }
      if (entry.getValue("sessionId") instanceof String id && !id.isBlank()) {
        sessionId = id;
      }
      String type = entry.getValue("type") instanceof String t ? t : "";
      JsonObject message =
          entry.getValue("message") instanceof JsonObject m ? m : new JsonObject();
      if ("user".equals(type)) {
        if (!entry.getBoolean("isMeta", false) && isPrompt(message.getValue("content"))) {
          texts.clear();
          at = null;
        }
      } else if ("assistant".equals(type)) {
        List<String> blocks = textBlocks(message.getValue("content"));
        if (!blocks.isEmpty()) {
          texts.addAll(blocks);
          at = entry.getValue("timestamp") instanceof String ts ? ts : at;
        }
      }
    }
    String text = texts.isEmpty() ? null : String.join("\n\n", texts);
    return new Answer(commandId, sessionId, state, text, text == null ? null : at);
  }

  /** Whether a user entry's content is a prompt: a string, or any block but a tool result. */
  private static boolean isPrompt(Object content) {
    if (content instanceof String text) {
      return !text.isBlank();
    }
    if (content instanceof JsonArray blocks) {
      for (Object block : blocks) {
        if (block instanceof JsonObject json && !"tool_result".equals(json.getValue("type"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static List<String> textBlocks(Object content) {
    List<String> texts = new ArrayList<>();
    if (content instanceof String text) {
      if (!text.isBlank()) {
        texts.add(text);
      }
    } else if (content instanceof JsonArray blocks) {
      for (Object block : blocks) {
        if (block instanceof JsonObject json
            && "text".equals(json.getValue("type"))
            && json.getValue("text") instanceof String text
            && !text.isBlank()) {
          texts.add(text);
        }
      }
    }
    return texts;
  }

  private static JsonObject parse(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    try {
      return new JsonObject(line);
    } catch (RuntimeException notJson) {
      return null;
    }
  }

  /** Stop flushing. */
  void stop() {
    scheduler.shutdown();
  }
}
