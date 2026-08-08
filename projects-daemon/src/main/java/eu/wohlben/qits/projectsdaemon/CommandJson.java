package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.commands.ActionResolver;
import eu.wohlben.qits.projectsdaemon.commands.AgentSessionRef;
import eu.wohlben.qits.projectsdaemon.commands.Command;
import eu.wohlben.qits.projectsdaemon.commands.CommandLogLine;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;

/**
 * Serializes {@code qits-commands}' result records to the JSON {@link ProjectsApi} answers with.
 * One of the module's two serializers, alongside {@link ProjectsJson}, and deliberately the same
 * shape: hand-built {@code JsonObject}s, because the native daemon carries no Jackson and a
 * databind reflection registration is exactly what the image builder would have to be told
 * about.
 *
 * <p><b>Every key here is a wire contract, not a naming choice.</b> qits-projects deserializes
 * these bodies straight into its own DTO tree, which is what the SPA's Commands list, terminal and
 * chat views consume. Renaming a key here breaks that UX rather than failing a build.
 *
 * <h2>The two keys the daemon has to synthesize</h2>
 *
 * <p>{@code projectId} and {@code repoName} are not on {@link Command} — inside the container they
 * are ambient, so a command does not carry them. They come from {@link DaemonProjectContext}
 * instead, and are put back into every body so a caller can attribute a command without asking a
 * second question.
 *
 * <p>{@code shortCommitHash} is derived rather than stored. It is computed here rather than left
 * to the caller, because a missing component decodes to null — the Commands list would silently
 * lose its commit column.
 *
 * <p>Instants are ISO-8601 strings, which is what Jackson's {@code JavaTimeModule} reads back into
 * {@link Instant}; the alternative (epoch millis) decodes too, but not into the same value for a
 * null.
 */
final class CommandJson {

  private CommandJson() {}

  /** One command, in the shape qits-projects' own command DTO reconstructs. */
  static JsonObject command(Command command, String projectId, String repoName) {
    JsonArray sessions = new JsonArray();
    for (AgentSessionRef session : command.agentSessions()) {
      sessions.add(agentSession(session));
    }
    JsonObject body =
        new JsonObject()
            .put("id", command.id())
            .put("projectId", projectId)
            .put("repoName", repoName)
            .put("branch", command.branch())
            .put("actionName", command.actionName())
            .put("status", command.status().name())
            .put("interactive", command.interactive())
            .put("kind", command.kind().name())
            .put("launchedAt", iso(command.launchedAt()))
            .put("agentSessions", sessions);
    // Absent optionals are omitted rather than emitted as explicit nulls, matching ProjectsJson:
    // Jackson maps a missing component to null when it reconstructs a record, so the caller sees the
    // same value either way.
    putIfPresent(body, "commitHash", command.commitHash());
    putIfPresent(body, "shortCommitHash", shortCommitHash(command.commitHash()));
    putIfPresent(body, "actionId", command.actionId());
    if (command.exitCode() != null) {
      body.put("exitCode", command.exitCode());
    }
    putIfPresent(body, "finishedAt", iso(command.finishedAt()));
    return body;
  }

  /** {@code GET /commands} — the list, newest first. */
  static JsonObject commands(List<Command> commands, String projectId, String repoName) {
    JsonArray entries = new JsonArray();
    for (Command command : commands) {
      // Each row is wrapped in an entry with a single `command` component, so the list can grow
      // per-row metadata later without moving the command body.
      entries.add(new JsonObject().put("command", command(command, projectId, repoName)));
    }
    return new JsonObject().put("entries", entries);
  }

  /** {@code POST /commands} — the launch response, in the same envelope as the list. */
  static JsonObject launched(Command command, String projectId, String repoName) {
    return new JsonObject().put("command", command(command, projectId, repoName));
  }

  /** {@code GET /commands/{id}/log} — the captured lines in order. */
  static JsonObject log(List<CommandLogLine> lines) {
    JsonArray entries = new JsonArray();
    for (CommandLogLine line : lines) {
      JsonObject entry =
          new JsonObject()
              .put("sequence", line.sequence())
              .put("channel", line.channel().name())
              .put("content", line.content())
              .put("timestamp", iso(line.timestamp()));
      if (line.severity() != null) {
        entry.put("severity", line.severity().name());
      }
      entries.add(entry);
    }
    return new JsonObject().put("lines", entries);
  }

  /** One entry of a command's ordered agent-session list. */
  private static JsonObject agentSession(AgentSessionRef session) {
    JsonObject body =
        new JsonObject()
            .put("sessionId", session.sessionId())
            .put("source", session.source().name())
            .put("recordedAt", iso(session.recordedAt()));
    putIfPresent(body, "forkedFromSessionId", session.forkedFromSessionId());
    putIfPresent(body, "transcriptPath", session.transcriptPath());
    return body;
  }

  /**
   * {@code GET /commands/actions} — what this container declares, and therefore what {@code POST
   * /commands} will accept. Empty for now: see {@link NoDeclaredActions} for why the seam exists
   * with nothing behind it.
   */
  static JsonObject actions(List<ActionResolver.ResolvedAction> actions) {
    JsonArray entries = new JsonArray();
    for (ActionResolver.ResolvedAction action : actions) {
      entries.add(
          new JsonObject()
              .put("id", action.id())
              .put("name", action.name())
              .put("interactive", action.interactive()));
    }
    return new JsonObject().put("actions", entries);
  }

  /** Derived rather than stored, so the DTO component is never null. */
  private static String shortCommitHash(String commitHash) {
    if (commitHash == null) {
      return null;
    }
    return commitHash.length() >= 7 ? commitHash.substring(0, 7) : commitHash;
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static void putIfPresent(JsonObject body, String key, String value) {
    if (value != null) {
      body.put(key, value);
    }
  }
}
