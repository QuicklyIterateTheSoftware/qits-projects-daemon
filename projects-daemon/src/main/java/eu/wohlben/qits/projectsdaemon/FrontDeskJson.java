package eu.wohlben.qits.projectsdaemon;

import io.vertx.core.json.JsonObject;

/**
 * The {@code /front-desk} bodies (qits-1572). qits-projects passes them through verbatim, so the
 * field names are a contract: every field is present, {@code null} included.
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
   * A refusal: {@code reason} is the contract's field; {@code message} repeats it so the body still
   * has this server's one error shape ({@link ProjectsJson#error}).
   */
  static JsonObject refused(String reason) {
    return ProjectsJson.error(reason).put("reason", reason);
  }
}
