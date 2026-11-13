package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.commands.GuardedInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link NamedAgentHost} (qits-2154): one inbox per named agent over its own kept command, with the
 * desk's rules plus the one a named agent needs — a prompt while it is still launching waits for its
 * first {@code IDLE} — and nothing for a name the frame does not carry. Also the per-agent session
 * record the multiplex keeps for it ({@link FrontDeskSessionRecord#ofNamedAgent}).
 */
class NamedAgentHostTest {

  private List<NamedAgentHost.Agent> roster = new ArrayList<>();
  private final Map<String, String> states = new HashMap<>();
  private final List<String> typed = new ArrayList<>();
  private long now = 1_000_000;

  private final DeliveryKeeper.Session session =
      new DeliveryKeeper.Session() {
        @Override
        public boolean running(String commandId) {
          return roster.stream().anyMatch(agent -> commandId.equals(agent.commandId()));
        }

        @Override
        public Optional<String> activityState(String commandId) {
          return Optional.ofNullable(states.get(commandId));
        }

        @Override
        public GuardedInput send(String commandId, String text) {
          typed.add(commandId + ":" + text);
          return GuardedInput.WRITTEN;
        }
      };

  private final NamedAgentHost host =
      new NamedAgentHost(
          () -> roster,
          session,
          commandId ->
              List.of(
                  "{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}",
                  "{\"type\":\"assistant\",\"sessionId\":\"s-"
                      + commandId
                      + "\",\"timestamp\":\"2026-11-13T10:00:00Z\","
                      + "\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"from "
                      + commandId
                      + "\"}]}}"),
          new SessionKeeper.Scheduler() {
            @Override
            public void schedule(Runnable step, long delayMs) {
              step.run();
            }

            @Override
            public void shutdown() {}
          },
          () -> now);

  private static NamedAgentHost.Agent agent(String name, String state, String commandId) {
    return new NamedAgentHost.Agent(
        name, "agent:" + name, "project." + name, state, commandId, null);
  }

  @Test
  void aPromptWhileTheAgentLaunchesWaitsForItsFirstIdle() {
    roster = List.of(agent("ui-builder", "STARTING", null));

    FrontDeskInbox.Outcome queued = host.prompt("ui-builder", "build the page").orElseThrow();
    assertEquals(FrontDeskInbox.Kind.QUEUED, queued.kind());
    assertNull(queued.commandId());
    assertEquals(1, queued.position());
    assertEquals(
        FrontDeskInbox.Kind.QUEUED, host.prompt("ui-builder", "and the tab").orElseThrow().kind());
    assertTrue(typed.isEmpty());

    // It comes up; the SessionStart hook's IDLE types the first turn, and only that one.
    roster = List.of(agent("ui-builder", "RUNNING", "cmd-ui"));
    states.put("cmd-ui", "IDLE");
    host.onActivity("cmd-ui", "IDLE");
    assertEquals(List.of("cmd-ui:build the page"), typed);

    host.onActivity("cmd-ui", "BUSY");
    host.onActivity("cmd-ui", "IDLE");
    assertEquals(List.of("cmd-ui:build the page", "cmd-ui:and the tab"), typed);
  }

  @Test
  void aStoppedOrFailedAgentRefusesAndAnUnknownOneIsNoAgentAtAll() {
    roster = List.of(agent("ui-builder", "STOPPED", null), agent("other", "FAILED", null));
    assertEquals(
        FrontDeskInbox.Kind.NO_DESK, host.prompt("ui-builder", "hi").orElseThrow().kind());
    assertEquals(FrontDeskInbox.Kind.NO_DESK, host.prompt("other", "hi").orElseThrow().kind());
    assertTrue(host.prompt("reviewer", "hi").isEmpty());
    assertTrue(host.lastAnswer("reviewer").isEmpty());
  }

  @Test
  void eachAgentHasItsOwnQueueAndHearsOnlyItsOwnCommand() {
    roster =
        List.of(agent("ui-builder", "RUNNING", "cmd-ui"), agent("reviewer", "RUNNING", "cmd-rv"));
    states.put("cmd-ui", "BUSY");
    states.put("cmd-rv", "BUSY");
    host.prompt("ui-builder", "u1");
    host.prompt("reviewer", "r1");

    states.put("cmd-rv", "IDLE");
    host.onActivity("cmd-rv", "IDLE");
    assertEquals(List.of("cmd-rv:r1"), typed, "the reviewer's IDLE flushes only the reviewer's");

    host.onActivity("cmd-desk", "IDLE");
    assertEquals(1, typed.size(), "somebody else's command flushes nothing");
  }

  @Test
  void theQueueHoldsSixteenTurns() {
    roster = List.of(agent("ui-builder", "STARTING", null));
    for (int i = 0; i < FrontDeskInbox.CAPACITY; i++) {
      assertEquals(
          FrontDeskInbox.Kind.QUEUED, host.prompt("ui-builder", "t" + i).orElseThrow().kind());
    }
    assertEquals(FrontDeskInbox.Kind.FULL, host.prompt("ui-builder", "more").orElseThrow().kind());
  }

  @Test
  void theLastAnswerOutlivesTheSessionThatGaveIt() {
    roster = List.of(agent("ui-builder", "STOPPED", null));
    FrontDeskInbox.Answer none = host.lastAnswer("ui-builder").orElseThrow();
    assertEquals(new FrontDeskInbox.Answer(null, null, null, null, null), none);

    roster = List.of(agent("ui-builder", "RUNNING", "cmd-ui"));
    states.put("cmd-ui", "IDLE");
    FrontDeskInbox.Answer running = host.lastAnswer("ui-builder").orElseThrow();
    assertEquals("from cmd-ui", running.text());
    assertEquals("cmd-ui", running.commandId());
    assertEquals("s-cmd-ui", running.sessionId());

    roster = List.of(agent("ui-builder", "STOPPED", null));
    assertEquals("from cmd-ui", host.lastAnswer("ui-builder").orElseThrow().text());
  }

  // --- the session record a named agent keeps -----------------------------------------------------

  @Test
  void aNamedAgentRecordsItsSessionBesideTheDesksButApart(@TempDir Path mount) throws Exception {
    FrontDeskSessionRecord record =
        FrontDeskSessionRecord.ofNamedAgent(mount.toString(), "p-1", "ui-builder");
    assertEquals(
        mount.resolve(".qits/named-agents/p-1/ui-builder.session"), record.path());
    assertNull(record.read());
    record.write("11111111-1111-1111-1111-111111111111");
    assertEquals(
        "11111111-1111-1111-1111-111111111111", Files.readString(record.path()).strip());
    assertEquals(
        "11111111-1111-1111-1111-111111111111",
        FrontDeskSessionRecord.forSessionKey(mount.toString(), "p-1", "agent:ui-builder").read());
    assertTrue(
        Files.notExists(FrontDeskSessionRecord.of(mount.toString(), "p-1").path()),
        "the desk's record is untouched");
  }

  @Test
  void onlyANamedAgentsKeyKeepsARecord(@TempDir Path mount) {
    String root = mount.toString();
    assertSame(
        FrontDeskSessionRecord.NONE,
        FrontDeskSessionRecord.forSessionKey(root, "p-1", "0b7c4e1a-5d1f-4c3e-9a77-2f0e8c1d4b6a"));
    assertSame(FrontDeskSessionRecord.NONE, FrontDeskSessionRecord.forSessionKey(root, "p-1", null));
    assertSame(
        FrontDeskSessionRecord.NONE, FrontDeskSessionRecord.forSessionKey(root, "p-1", "agent:../x"));
    assertSame(
        FrontDeskSessionRecord.NONE, FrontDeskSessionRecord.ofNamedAgent(null, "p-1", "ui-builder"));
    assertSame(
        FrontDeskSessionRecord.NONE, FrontDeskSessionRecord.ofNamedAgent(root, "../p", "ui-builder"));
  }
}
