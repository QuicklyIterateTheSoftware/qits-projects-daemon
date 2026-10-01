package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdaemon.protocol.AgentActivity;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.AgentState;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks in {@link HookWebhook}'s decide-and-relay logic — the HTTP-free half that turns one hook
 * payload into at-most-one {@link AgentActivity} frame. Drives the package-private {@code handle}
 * seam directly, so no real port is bound.
 */
class HookWebhookTest {

  private final List<DaemonMessage> sent = new ArrayList<>();
  private final HookWebhook webhook = new HookWebhook(null, 13337, sent::add);

  /** A second webhook with an {@link HookWebhook#setAgentLaunch(java.util.function.BiConsumer)}
   * listener wired, recording each (commandId, state) it was told — see the forwarding tests below. */
  private final Map<String, String> activity = new java.util.LinkedHashMap<>();

  private final HookWebhook notifying = new HookWebhook(null, 13337, sent::add);

  {
    notifying.setAgentLaunch((commandId, state) -> activity.put(commandId, state));
  }

  private AgentActivity lastSent() {
    return (AgentActivity) sent.get(sent.size() - 1);
  }

  private JsonObject payload(String event) {
    return new JsonObject()
        .put("hook_event_name", event)
        .put("session_id", "11111111-1111-1111-1111-111111111111")
        .put("transcript_path", "projects/-workspace/s.jsonl")
        .put("source", "startup");
  }

  @Test
  void mapsEachEventToItsState() {
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertEquals(AgentState.IDLE, lastSent().state());
    assertEquals("SessionStart", lastSent().hookEvent());
    assertEquals("cmd-1", lastSent().commandId());

    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    assertEquals(AgentState.BUSY, lastSent().state());

    webhook.handle("Stop", payload("Stop"), "cmd-1");
    assertEquals(AgentState.IDLE, lastSent().state());

    webhook.handle("Notification", payload("Notification"), "cmd-1");
    assertEquals(AgentState.WAITING, lastSent().state());

    webhook.handle("SessionEnd", payload("SessionEnd"), "cmd-1");
    assertEquals(AgentState.ENDED, lastSent().state());
  }

  @Test
  void forwardsSessionIdentityFields() {
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertEquals("11111111-1111-1111-1111-111111111111", lastSent().sessionId());
    assertEquals("projects/-workspace/s.jsonl", lastSent().transcriptPath());
    assertEquals("startup", lastSent().source());
  }

  @Test
  void dropsUninterestingEvents() {
    webhook.handle("SubagentStop", payload("SubagentStop"), "cmd-1");
    webhook.handle("PreToolUse", payload("PreToolUse"), "cmd-1");
    assertTrue(sent.isEmpty());
  }

  @Test
  void dropsPayloadWithNoCommandCorrelation() {
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), null);
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "  ");
    assertTrue(sent.isEmpty());
  }

  @Test
  void stopAfterNotificationKeepsWaiting() {
    webhook.handle("Notification", payload("Notification"), "cmd-1");
    assertEquals(AgentState.WAITING, lastSent().state());
    int before = sent.size();
    // The turn-end Stop must not downgrade the pending permission prompt.
    webhook.handle("Stop", payload("Stop"), "cmd-1");
    assertEquals(before, sent.size());
    assertEquals(AgentState.WAITING, lastSent().state());
  }

  @Test
  void reportCurrentReplaysLastStatePerCommand() {
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    webhook.handle("Notification", payload("Notification"), "cmd-2");
    sent.clear();
    webhook.reportCurrent();
    assertEquals(2, sent.size());
  }

  @Test
  void sessionEndEvictsFromReplay() {
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    webhook.handle("SessionEnd", payload("SessionEnd"), "cmd-1");
    sent.clear();
    webhook.reportCurrent();
    assertTrue(sent.isEmpty());
  }

  // --- forwarding to AgentLaunchService (qits-617) -----------------------------------------------

  @Test
  void sessionStartAndStopForwardIdle() {
    notifying.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertEquals(AgentState.IDLE, activity.get("cmd-1"));

    activity.clear();
    notifying.handle("Stop", payload("Stop"), "cmd-1");
    assertEquals(AgentState.IDLE, activity.get("cmd-1"));
  }

  @Test
  void sessionEndForwardsEnded() {
    notifying.handle("SessionEnd", payload("SessionEnd"), "cmd-1");
    assertEquals(AgentState.ENDED, activity.get("cmd-1"));
  }

  @Test
  void stopWhileWaitingForwardsTheStoredWaitingNotTheRawStop() {
    notifying.handle("Notification", payload("Notification"), "cmd-1");
    assertEquals(AgentState.WAITING, activity.get("cmd-1"));

    activity.clear();
    // The dropped Stop still reaches the launch service — with the state actually stored
    // (WAITING), never the raw event's IDLE, or a queued rename would type into the open prompt.
    notifying.handle("Stop", payload("Stop"), "cmd-1");
    assertEquals(AgentState.WAITING, activity.get("cmd-1"));
  }

  @Test
  void aThrowingListenerNeverEscapesHandle() {
    HookWebhook throwing = new HookWebhook(null, 13337, sent::add);
    throwing.setAgentLaunch(
        (commandId, state) -> {
          throw new RuntimeException("boom");
        });
    // Must not throw out of handle(): the hook's HTTP response always has to reach 200.
    throwing.handle("SessionStart", payload("SessionStart"), "cmd-1");
  }

  @Test
  void noListenerWiredIsANoOp() {
    // webhook (the class field) never had setAgentLaunch called — handle must still work.
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertEquals(AgentState.IDLE, lastSent().state());
  }
}
