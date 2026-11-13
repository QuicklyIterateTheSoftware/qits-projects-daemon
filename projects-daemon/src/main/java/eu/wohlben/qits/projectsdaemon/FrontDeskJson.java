package eu.wohlben.qits.projectsdaemon;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * The {@code /front-desk} bodies (qits-1572), and the {@code /named-agents} ones (qits-2154), whose
 * prompt and last answer are the desk's bodies unchanged. qits-projects passes them through
 * verbatim, so the field names are a contract: every field is present, {@code null} included.
 */
final class FrontDeskJson {

  private FrontDeskJson() {}

  /** {@code POST /front-desk/prompt}'s 200: typed now, or queued at a 1-based position. */
  static JsonObject prompted(FrontDeskInbox.Outcome outcome) {
    return new JsonObject()
        .put("commandId", outcome.commandId())
        .put("state", outcome.state())
        .put("delivered", outcome.kind() == FrontDeskInbox.Kind.DELIVERED)
        .put("queued", outcome.kind() == FrontDeskInbox.Kind.QUEUED)
        .put("position", outcome.position());
  }

  /** {@code GET /front-desk/last-answer}'s 200; {@code text} and {@code at} null with no answer. */
  static JsonObject answer(FrontDeskInbox.Answer answer) {
    return new JsonObject()
        .put("commandId", answer.commandId())
        .put("sessionId", answer.sessionId())
        .put("state", answer.state())
        .put("text", answer.text())
        .put("at", answer.at());
  }

  /**
   * {@code GET /named-agents}'s 200: {@code {"agents": [{name, key, surface, state, commandId,
   * sessionId}]}}, in the frame's order; {@code commandId} and {@code sessionId} null when none.
   */
  static JsonObject namedAgents(List<NamedAgentHost.Agent> agents) {
    JsonArray array = new JsonArray();
    for (NamedAgentHost.Agent agent : agents) {
      array.add(
          new JsonObject()
              .put("name", agent.name())
              .put("key", agent.key())
              .put("surface", agent.surface())
              .put("state", agent.state())
              .put("commandId", agent.commandId())
              .put("sessionId", agent.sessionId()));
    }
    return new JsonObject().put("agents", array);
  }

  /**
   * A refusal: {@code reason} is the contract's field; {@code message} repeats it so the body still
   * has this server's one error shape ({@link ProjectsJson#error}).
   */
  static JsonObject refused(String reason) {
    return ProjectsJson.error(reason).put("reason", reason);
  }
}
