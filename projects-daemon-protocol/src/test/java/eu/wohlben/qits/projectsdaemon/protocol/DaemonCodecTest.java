package eu.wohlben.qits.projectsdaemon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire contract's fast, framework-free guard: every message survives {@code encode → decode}
 * unchanged, and the discriminator round-trips through the {@link DaemonProtocol.Type} constants.
 *
 * <p><b>This is also the drift detector for the vendored copy.</b> qits-projects vendors this
 * module's sources rather than consuming a published jar (AGENTS.md, "The vendoring contract"), so
 * nothing at build time notices when the two copies diverge — this test, run on both sides, is what
 * does.
 */
class DaemonCodecTest {

  private static DaemonMessage roundTrip(DaemonMessage message) {
    return DaemonCodec.decode(DaemonCodec.encode(message));
  }

  @Test
  void helloRoundTrips() {
    Hello hello =
        new Hello(
            "proj-1",
            "qits-qits",
            DaemonProtocol.CAPABILITY_VERSION,
            "1.0.0-SNAPSHOT",
            "2026-08-08T09:14:03Z");
    assertEquals(hello, roundTrip(hello));
    assertEquals(
        DaemonProtocol.Type.HELLO, DaemonCodec.encode(hello).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void helloFromAnOlderDaemonDecodesMissingBuildIdentityAsNull() {
    // An older image predating the build-identity fields sends a Hello without them; the map simply
    // lacks those keys and they must decode to null, so the backend still records the connection.
    var map =
        new LinkedHashMap<>(
            DaemonCodec.encode(new Hello("proj-1", "qits-qits", 1, "1.0.0", "2026-08-08T09:14:03Z")));
    map.remove(DaemonProtocol.Field.DAEMON_VERSION);
    map.remove(DaemonProtocol.Field.DAEMON_BUILD_TIME);
    assertEquals(new Hello("proj-1", "qits-qits", 1, null, null), DaemonCodec.decode(map));
  }

  @Test
  void heartbeatRoundTrips() {
    Heartbeat heartbeat = new Heartbeat("proj-1");
    assertEquals(heartbeat, roundTrip(heartbeat));
  }

  @Test
  void clientLogRoundTrips() {
    DaemonLog log = new DaemonLog("INFO", "hello from projects-daemon");
    assertEquals(log, roundTrip(log));
  }

  @Test
  void commandChunkRoundTripsBothStreams() {
    CommandChunk out = new CommandChunk("c1", Stream.STDOUT, "line\n");
    CommandChunk err = new CommandChunk("c1", Stream.STDERR, "oops\n");
    assertEquals(out, roundTrip(out));
    assertEquals(err, roundTrip(err));
  }

  @Test
  void commandExitRoundTrips() {
    CommandExit exit = new CommandExit("c1", 137);
    assertEquals(exit, roundTrip(exit));
  }

  @Test
  void projectInfoRoundTrips() {
    ProjectInfo info = new ProjectInfo("proj-1", "qits-qits", "abc123", true);
    assertEquals(info, roundTrip(info));
    assertEquals(
        DaemonProtocol.Type.PROJECT_INFO, DaemonCodec.encode(info).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void provisionedRoundTrips() {
    Provisioned provisioned = new Provisioned("proj-1", "abc123");
    assertEquals(provisioned, roundTrip(provisioned));
    assertEquals(
        DaemonProtocol.Type.PROVISIONED,
        DaemonCodec.encode(provisioned).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void provisionFailedRoundTrips() {
    ProvisionFailed failed = new ProvisionFailed("proj-1", "git clone exited 128");
    assertEquals(failed, roundTrip(failed));
    assertEquals(
        DaemonProtocol.Type.PROVISION_FAILED,
        DaemonCodec.encode(failed).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void ackRoundTrips() {
    assertEquals(new Ack(), roundTrip(new Ack()));
  }

  @Test
  void runCommandRoundTripsArgvAndEnv() {
    RunCommand command =
        new RunCommand("c1", List.of("git", "rev-parse", "HEAD"), "/workspace", Map.of("FOO", "bar"));
    assertEquals(command, roundTrip(command));
  }

  @Test
  void runCommandToleratesNullCollections() {
    RunCommand decoded = (RunCommand) roundTrip(new RunCommand("c1", null, null, null));
    assertEquals(List.of(), decoded.argv());
    assertEquals(Map.of(), decoded.env());
  }

  @Test
  void describeRoundTrips() {
    Describe describe = new Describe("c1");
    assertEquals(describe, roundTrip(describe));
  }

  @Test
  void agentActivityRoundTrips() {
    AgentActivity sessionStart =
        new AgentActivity(
            "cmd-1",
            "11111111-1111-1111-1111-111111111111",
            DaemonProtocol.AgentState.IDLE,
            "SessionStart",
            "startup",
            "projects/-workspace/session.jsonl",
            1_700_000_000_000L);
    AgentActivity busy =
        new AgentActivity(
            "cmd-1", null, DaemonProtocol.AgentState.BUSY, "UserPromptSubmit", null, null, 42L);
    assertEquals(sessionStart, roundTrip(sessionStart));
    assertEquals(busy, roundTrip(busy));
    assertEquals(
        DaemonProtocol.Type.AGENT_ACTIVITY,
        DaemonCodec.encode(busy).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void projectChangedRoundTrips() {
    ProjectChanged changed = new ProjectChanged("proj-1", "COMMANDS");
    assertEquals(changed, roundTrip(changed));
    assertEquals(
        DaemonProtocol.Type.PROJECT_CHANGED,
        DaemonCodec.encode(changed).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void projectChangedToleratesAnUnknownTopic() {
    // The backend drops a topic it has no view for; the codec must still carry it, so the drop is a
    // decision the backend makes rather than a decode failure that kills the frame.
    ProjectChanged future = new ProjectChanged("proj-1", "SOMETHING_NEWER");
    assertEquals(future, roundTrip(future));
  }

  @Test
  void openStreamRoundTrips() {
    OpenStream open = new OpenStream("Zm9vYmFy", "/projects/daemon/stream/Zm9vYmFy");
    assertEquals(open, roundTrip(open));
    assertEquals(
        DaemonProtocol.Type.OPEN_STREAM, DaemonCodec.encode(open).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void sessionCredentialsRoundTrip() {
    SessionCredentials frame =
        new SessionCredentials(
            List.of(
                new SessionCredentials.Credential(
                    SessionCredentials.FRONT_DESK_KEY, "qits_tok_desk", "tok-desk-session-proj-1"),
                new SessionCredentials.Credential(
                    "0b7c4e1a-entity", "qits_tok_refine", "tok-refinement-row-9")));
    assertEquals(frame, roundTrip(frame));
    Map<String, Object> wire = DaemonCodec.encode(frame);
    assertEquals(DaemonProtocol.Type.SESSION_CREDENTIALS, wire.get(DaemonProtocol.Field.TYPE));
    assertEquals(
        List.of(
            Map.of("key", "front-desk", "token", "qits_tok_desk", "subject", "tok-desk-session-proj-1"),
            Map.of("key", "0b7c4e1a-entity", "token", "qits_tok_refine", "subject", "tok-refinement-row-9")),
        wire.get(DaemonProtocol.Field.CREDENTIALS));
  }

  @Test
  void anEmptySessionCredentialsListRoundTripsAsEmpty() {
    // Replace semantics: an empty frame is the server saying "no session has a credential now".
    SessionCredentials empty = new SessionCredentials(List.of());
    assertEquals(empty, roundTrip(empty));
    assertEquals(
        new SessionCredentials(List.of()),
        DaemonCodec.decode(Map.of(DaemonProtocol.Field.TYPE, "sessionCredentials")));
  }

  @Test
  void sessionCredentialsSkipEntriesWithoutAKey() {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put(DaemonProtocol.Field.TYPE, DaemonProtocol.Type.SESSION_CREDENTIALS);
    Map<String, Object> keyless = new LinkedHashMap<>();
    keyless.put("token", "t");
    wire.put(
        DaemonProtocol.Field.CREDENTIALS,
        List.of(keyless, "not an object", Map.of("key", "front-desk", "token", "t2")));
    assertEquals(
        new SessionCredentials(List.of(new SessionCredentials.Credential("front-desk", "t2", ""))),
        DaemonCodec.decode(wire));
  }

  @Test
  void aCredentialNeverPrintsItsToken() {
    SessionCredentials frame =
        new SessionCredentials(
            List.of(new SessionCredentials.Credential("front-desk", "qits_tok_secret", "subj")));
    org.junit.jupiter.api.Assertions.assertFalse(frame.toString().contains("qits_tok_secret"));
    org.junit.jupiter.api.Assertions.assertTrue(frame.toString().contains("subj"));
  }

  @Test
  void capabilityTwoIsTheOneThatUnderstandsSessionCredentials() {
    assertEquals(2, DaemonProtocol.CAPABILITY_VERSION);
    assertEquals("sessionCredentials", DaemonProtocol.Type.SESSION_CREDENTIALS);
    assertEquals("front-desk", SessionCredentials.FRONT_DESK_KEY);
    assertEquals("desk-agents", SessionCredentials.DESK_AGENTS_KEY);
  }

  @Test
  void theCrossRepoPathsAreTheOnesBothSidesAgreedOn() {
    // Append-only: qits-projects serves these prefixes and the daemon is handed urls built from
    // them. Changing either breaks every already-running container, which is why they are asserted
    // as literals rather than read off the constants they name.
    assertEquals("/projects/daemon/", DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX);
    assertEquals("/projects/container/", DaemonProtocol.CONTAINER_PROXY_PATH_PREFIX);
  }

  @Test
  void decodeRejectsMissingType() {
    assertThrows(IllegalArgumentException.class, () -> DaemonCodec.decode(Map.of()));
  }

  @Test
  void decodeRejectsUnknownType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonCodec.decode(Map.of(DaemonProtocol.Field.TYPE, "nope")));
  }
}
