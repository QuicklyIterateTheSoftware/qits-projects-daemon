package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonCodec;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The control socket's half of the multiplex (qits-770): it announces capability 3 in its {@code
 * hello}, and a {@code sessions} frame off the wire reaches the {@link SessionKeeper} — which then
 * launches on the credential an earlier {@code sessionCredentials} frame off the same wire carried.
 * Frames are handed to {@link ControlSocket#onFrame} as the JSON text qits sends; no socket is
 * dialled.
 */
class ControlSocketSessionsTest {

  private static final String KEY = "0b7c4e1a-5d1f-4c3e-9a77-2f0e8c1d4b6a";

  /** Runs what is due now, and leaves every later step (the ladder's polls) unrun. */
  private final Deque<Runnable> due = new ArrayDeque<>();

  private final SessionKeeper.Scheduler scheduler =
      new SessionKeeper.Scheduler() {
        @Override
        public void schedule(Runnable step, long delayMs) {
          if (delayMs <= 0) {
            due.add(step);
          }
        }

        @Override
        public void shutdown() {
          due.clear();
        }
      };

  private void runDue() {
    while (!due.isEmpty()) {
      due.poll().run();
    }
  }

  private final List<AgentLaunchRequest> launches = new ArrayList<>();

  private final SessionKeeper.Desk desk =
      new SessionKeeper.Desk() {
        @Override
        public Command launch(AgentLaunchRequest request) {
          launches.add(request);
          return Command.running(
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
              Instant.EPOCH);
        }

        @Override
        public Optional<Command> find(String commandId) {
          return Optional.empty();
        }

        @Override
        public boolean transcriptExists(Command command, String sessionId) {
          return false;
        }
      };

  private static String json(DaemonMessage message) {
    return new JsonObject(DaemonCodec.encode(message)).encode();
  }

  @Test
  void helloAnnouncesCapabilityThree() {
    ControlSocket socket = new ControlSocket();
    socket.projectId = "proj-1";
    assertEquals(3, socket.hello().capabilityVersion());
    assertEquals(DaemonProtocol.CAPABILITY_VERSION, socket.hello().capabilityVersion());
    assertEquals("proj-1", socket.hello().projectId());
  }

  @Test
  void aSessionsFrameReachesTheKeeperAndLaunchesOnTheWiresCredential() {
    ControlSocket socket = new ControlSocket();
    List<DaemonMessage> sent = new ArrayList<>();
    SessionKeeper keeper =
        new SessionKeeper(
            null,
            socket.sessionCredentials,
            desk,
            SessionKeeper.Timing.DEFAULT,
            scheduler,
            System::currentTimeMillis,
            sent::add);
    socket.sessionKeeper = keeper;
    keeper.start();

    socket.onFrame(
        json(
            new SessionCredentials(
                List.of(new SessionCredentials.Credential(KEY, "qits_tok_refine", "tok-refine")))));
    socket.onFrame(
        json(
            new Sessions(
                List.of(
                    new Sessions.Session(
                        KEY,
                        "epic.chat",
                        "RUNNING",
                        null,
                        new Sessions.Entity("qits-1234", "Refine", "REPORTED", false, null))))));
    runDue();

    assertEquals(1, launches.size());
    AgentLaunchRequest launch = launches.getFirst();
    assertEquals(AgentSurface.EPIC_CHAT, launch.surface());
    assertEquals(KEY, launch.sessionKey());
    assertEquals("qits-1234", launch.entityId());
    assertEquals("qits_tok_refine", launch.environment().get("QITS_TOKEN"));

    SessionReport report =
        sent.stream()
            .filter(SessionReport.class::isInstance)
            .map(SessionReport.class::cast)
            .reduce((first, second) -> second)
            .orElseThrow();
    assertEquals("RUNNING", report.find(KEY).orElseThrow().state());
  }

  @Test
  void anUndecodableFrameIsDroppedWithoutReachingTheKeeper() {
    ControlSocket socket = new ControlSocket();
    List<DaemonMessage> sent = new ArrayList<>();
    socket.sessionKeeper =
        new SessionKeeper(
            null,
            socket.sessionCredentials,
            desk,
            SessionKeeper.Timing.DEFAULT,
            scheduler,
            System::currentTimeMillis,
            sent::add);
    socket.onFrame("{\"type\":\"sessions\",");
    socket.onFrame("{\"type\":\"noSuchFrame\"}");
    runDue();
    assertTrue(sent.isEmpty());
    assertTrue(launches.isEmpty());
  }
}
