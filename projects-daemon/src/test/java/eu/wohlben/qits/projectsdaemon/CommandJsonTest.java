package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.agents.AgentLaunchRecord;
import eu.wohlben.qits.agents.AgentPermissionMode;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandKind;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two keys the configuration epic adds to a command body — the surface it was started from and
 * the record of what it actually ran with — and the cases in which each is absent.
 *
 * <p>The surface is what let the frontend stop matching {@code " (tickets desk)"} in a display name
 * to tell a tickets session from an epics one — that match is deleted now, so the name below is a
 * label the test happens to use, not a key anything reads.
 *
 * <p>The record is the whole point of writing one: it is stored on a command inside the container,
 * and {@code GET /commands} is the only door out. Its keys are asserted as literals, and its key
 * <em>set</em> is asserted whole, because the one property the record must never lose is that there
 * is no field in it a credential could travel in.
 */
class CommandJsonTest {

  @Test
  void anAgentCommandReportsTheSurfaceItWasStartedFrom() {
    JsonObject body =
        CommandJson.command(
            Command.running(
                "cmd-1",
                CommandKind.CHAT,
                "main",
                "abc1234def",
                null,
                "Claude Code (tickets desk)",
                "claude --print",
                false,
                "CLAUDE",
                "project.tickets",
                Instant.parse("2026-09-09T10:00:00Z")),
            "proj-7",
            "qits-qits");

    assertEquals("project.tickets", body.getString("agentSurface"));
    assertEquals("abc1234", body.getString("shortCommitHash"));
  }

  @Test
  void aCommandWithNoSurfaceOmitsTheKey() {
    JsonObject body =
        CommandJson.command(
            Command.running(
                "cmd-2",
                CommandKind.TERMINAL,
                "main",
                null,
                "build",
                "Build",
                "make",
                true,
                null,
                Instant.parse("2026-09-09T10:00:00Z")),
            "proj-7",
            "qits-qits");

    assertFalse(
        body.containsKey("agentSurface"),
        "absent rather than null: a non-agent command has no surface, and so does every agent"
            + " command launched before the surface became a value that travels");
    assertFalse(
        body.containsKey("agentLaunchRecord"),
        "and it has nothing to say about what it ran with either — which must stay tellable from a"
            + " session that ran with an empty configuration");
  }

  @Test
  void anAgentCommandAnswersWhatItWasLaunchedWith() {
    // The record is written at launch inside the container; GET /commands is the only door out of
    // it. Without this key it could never be read, which is what the epic's per-surface
    // verification reads instead of logs, and what makes "a container keeps the document it was
    // born with" a safe rule rather than an opaque one.
    JsonObject record = serve(RECORD).getJsonObject("agentLaunchRecord");

    // Literal keys and literal values, like every other field on this wire.
    assertEquals("project.tickets", record.getString("surface"));
    assertEquals("CLAUDE", record.getString("harness"));
    assertEquals("opus", record.getString("model"));
    assertEquals("high", record.getString("effort"));
    assertEquals("SKIP_PERMISSIONS", record.getString("permissionMode"));
    assertEquals(true, record.getBoolean("remoteControl"));
    assertEquals("project.tickets on main", record.getString("remoteControlName"));
    assertEquals(true, record.getBoolean("activityTracking"));
    assertEquals(true, record.getBoolean("configured"));
    assertEquals(new JsonArray().add("note"), record.getJsonArray("notes"));
    JsonObject attached = record.getJsonArray("mcpServers").getJsonObject(0);
    assertEquals("repository", attached.getString("server"));
    assertEquals(true, attached.getBoolean("readOnly"));
  }

  @Test
  void anAttachedExternalServerIsAnsweredByKeyAndCarriesNoCredential() {
    // The record was built to have NO SHAPE a credential could travel in: an attached catalog entry
    // is a key, not a url with a header stripped and not a redacted value. This pins that on what
    // the API serves — the exact key set, so a field added upstream that reintroduced a url, a
    // header name or a header value fails here rather than shipping quietly.
    JsonObject record = serve(RECORD).getJsonObject("agentLaunchRecord");

    assertEquals(new JsonArray().add("stripe"), record.getJsonArray("externalMcpServers"));
    assertEquals(
        Set.of(
            "surface",
            "harness",
            "model",
            "effort",
            "permissionMode",
            "remoteControl",
            "remoteControlName",
            "activityTracking",
            "mcpServers",
            "externalMcpServers",
            "configured",
            "notes"),
        record.fieldNames(),
        "the whole served record, and nothing credential-shaped in it");
    assertEquals(
        Set.of("server", "readOnly"),
        record.getJsonArray("mcpServers").getJsonObject(0).fieldNames(),
        "an attached platform server is named and fenced, never addressed — the url is not served");
  }

  @Test
  void aRecordThatIsNotJsonIsOmittedRatherThanFailingTheRead() {
    // Cannot happen: AgentLaunchRecord.toJson is the only writer on this path. Guarded anyway,
    // because one unreadable row must not take the whole Commands list down with it.
    assertFalse(serve("not json at all").containsKey("agentLaunchRecord"));
  }

  /** One agent command carrying {@code record}, serialized the way the API answers it. */
  private static JsonObject serve(String record) {
    return CommandJson.command(
        Command.running(
            "cmd-3",
            CommandKind.CHAT,
            "main",
            "abc1234def",
            null,
            "Claude Code",
            "claude --print",
            false,
            "CLAUDE",
            "project.tickets",
            record,
            Instant.parse("2026-09-09T10:00:00Z")),
        "proj-7",
        "qits-qits");
  }

  /**
   * A launch record as the library writes it — built through {@link AgentLaunchRecord} rather than
   * as a JSON literal, so this test tracks the record's real shape instead of a copy of it.
   */
  private static final String RECORD =
      new AgentLaunchRecord(
              "project.tickets",
              AgentType.CLAUDE,
              "opus",
              "high",
              AgentPermissionMode.SKIP_PERMISSIONS,
              true,
              "project.tickets on main",
              true,
              List.of(new AgentLaunchRecord.AttachedServer("repository", true)),
              List.of("stripe"),
              true,
              List.of("note"))
          .toJson();
}
