package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.agents.AgentSessionNodeDto;
import eu.wohlben.qits.projectsdaemon.agents.AgentSubagentDto;
import eu.wohlben.qits.projectsdaemon.agents.AgentType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;

/**
 * The agent surface's response bodies, built by hand with {@code io.vertx.core.json} exactly as
 * {@link CommandJson} and {@link ProjectsJson} are.
 *
 * <p><strong>Every key here is a wire contract.</strong> These bodies deserialize into
 * qits-projects' own session DTOs, which the SPA consumes — a renamed key is a broken Agents view
 * that nothing in this reactor would notice, so the tests assert them as literal strings; a test
 * that read them off the records would rename itself along with the bug.
 *
 * <p>Same conventions as its siblings: absent optionals are omitted rather than emitted as null,
 * lists are always present (empty rather than absent), primitives are always present, enums go out
 * as {@code name()}, and an {@link Instant} as its ISO-8601 string.
 */
final class AgentJson {

  private AgentJson() {}

  /** {@code POST /agents} — the launched command, in the same shape the commands routes use. */
  static JsonObject launched(
      eu.wohlben.qits.projectsdaemon.commands.Command command, String projectId, String repoName) {
    return new JsonObject().put("command", CommandJson.command(command, projectId, repoName));
  }

  /** {@code GET /agents/available} — the harnesses this daemon can launch, and the default. */
  static JsonObject available(AgentType defaultAgent) {
    JsonArray agents = new JsonArray();
    for (AgentType type : AgentType.values()) {
      agents.add(type.name());
    }
    return new JsonObject().put("agents", agents).put("defaultAgent", defaultAgent.name());
  }

  /** {@code GET /agent-sessions} — the session tree. */
  static JsonObject sessions(List<AgentSessionNodeDto> sessions) {
    JsonArray array = new JsonArray();
    for (AgentSessionNodeDto session : sessions) {
      array.add(session(session));
    }
    return new JsonObject().put("sessions", array);
  }

  private static JsonObject session(AgentSessionNodeDto node) {
    JsonObject json = new JsonObject().put("sessionId", node.sessionId());
    putIfPresent(json, "firstRecordedAt", iso(node.firstRecordedAt()));
    putIfPresent(json, "forkedFromSessionId", node.forkedFromSessionId());
    if (node.messageCount() != null) {
      // Absent means "not swept yet", which the UI renders differently from a swept zero.
      json.put("messageCount", node.messageCount());
    }
    putIfPresent(json, "newestCommandId", node.newestCommandId());
    JsonArray subagents = new JsonArray();
    for (AgentSubagentDto subagent : node.subagents()) {
      subagents.add(subagent(subagent));
    }
    json.put("subagents", subagents);
    JsonArray children = new JsonArray();
    for (AgentSessionNodeDto child : node.children()) {
      children.add(session(child));
    }
    return json.put("children", children);
  }

  private static JsonObject subagent(AgentSubagentDto subagent) {
    JsonObject json =
        new JsonObject()
            .put("agentId", subagent.agentId())
            .put("messageCount", subagent.messageCount());
    putIfPresent(json, "agentType", subagent.agentType());
    putIfPresent(json, "description", subagent.description());
    putIfPresent(json, "firstTimestamp", iso(subagent.firstTimestamp()));
    return json;
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static void putIfPresent(JsonObject json, String key, String value) {
    if (value != null) {
      json.put(key, value);
    }
  }
}
