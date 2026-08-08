package eu.wohlben.qits.projectsdaemon;

import io.vertx.core.json.JsonObject;

/**
 * The API's envelope bodies — the ones that belong to no capability module. Hand-built {@code
 * JsonObject}s for the same reason {@link CommandJson} and {@link AgentJson} are: the native daemon
 * carries no Jackson, and a databind reflection registration is exactly the kind of thing that has
 * to be declared to the image builder.
 */
final class ProjectsJson {

  private ProjectsJson() {}

  /** Every non-2xx body on this server, so a caller has one error shape to read. */
  static JsonObject error(String message) {
    return new JsonObject().put("message", message);
  }

  /** The body of a 202: the work was taken, and reports itself over the control socket. */
  static JsonObject accepted() {
    return new JsonObject().put("accepted", true);
  }
}
