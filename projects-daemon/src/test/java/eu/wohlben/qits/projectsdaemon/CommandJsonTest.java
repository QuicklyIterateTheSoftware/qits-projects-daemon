package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandKind;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The one key the surface work adds to a command body — and the one case in which it is absent.
 *
 * <p>It is what let the frontend stop matching {@code " (tickets desk)"} in a display name to tell
 * a tickets session from an epics one — that match is deleted now, so the name below is a label the
 * test happens to use, not a key anything reads.
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
  }
}
