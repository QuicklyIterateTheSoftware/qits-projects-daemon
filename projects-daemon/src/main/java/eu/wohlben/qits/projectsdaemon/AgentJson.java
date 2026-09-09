package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentNotSignedInException;
import eu.wohlben.qits.agents.AgentSessionNodeDto;
import eu.wohlben.qits.agents.AgentSubagentDto;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

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
      eu.wohlben.qits.commands.Command command, String projectId, String repoName) {
    return new JsonObject().put("command", CommandJson.command(command, projectId, repoName));
  }

  /**
   * {@code GET /agents/available} — the harnesses this daemon can launch, the default, and what
   * each harness in <em>this container's image</em> can actually be configured with.
   *
   * <p><b>The capability half is answered from a report taken once, at boot.</b> Producing it means
   * running the harness binaries ({@code claude --help}, {@code kimi provider list --json}), which
   * is why it cannot be produced per request and cannot be produced host-side at all: the binaries
   * live in the image, and the editor that needs the values is a platform-wide route with no
   * container in front of it. qits-projects caches what this answers, keyed by harness and image
   * version, and the editor's model and effort dropdowns read that cache.
   *
   * <p>It matters that this daemon answers it and not only the workspace one: a project's agent
   * container may run a different image build than any given workspace, and the editor should read a
   * catalogue reflecting what will actually run each surface.
   *
   * <p>{@code imageVersion} and {@code reportedBy} name where the report came from. Both are emitted
   * even when blank, because the host fills a blank from the pin it created this container with —
   * absent and empty are the same thing there, and a daemon that names them wins.
   *
   * @param capabilities one report per harness, or empty for a daemon that could not take one. An
   *     empty array is the <em>absent</em> case the host treats as "nothing to record", never a
   *     failure
   */
  static JsonObject available(
      AgentType defaultAgent,
      String imageVersion,
      String reportedBy,
      List<HarnessCapabilities> capabilities) {
    JsonArray agents = new JsonArray();
    for (AgentType type : AgentType.values()) {
      agents.add(type.name());
    }
    JsonArray reports = new JsonArray();
    for (HarnessCapabilities report : capabilities == null ? List.<HarnessCapabilities>of() : capabilities) {
      // The library builds the object; nothing here reshapes it. A relay that reshaped would be a
      // third place the contract with AgentHarnessCapabilityDto could drift.
      reports.add(report.toJson());
    }
    return new JsonObject()
        .put("agents", agents)
        .put("defaultAgent", defaultAgent.name())
        .put("imageVersion", imageVersion == null ? "" : imageVersion)
        .put("reportedBy", reportedBy == null ? "" : reportedBy)
        .put("capabilities", reports);
  }

  /**
   * {@code POST /agents} — the launch request, with every enum validated the way a query parameter
   * is: an unparseable value is a 400 rather than a silent default.
   *
   * <h2>The surface, and the desk it replaced</h2>
   *
   * <p>{@code surface} is where in the product the session was started from — the key its
   * configuration is stored under and the value that comes back on the command. It is required:
   * an unknown one is a 400 here ({@link AgentSurface#of}), and a missing one is a 400 from the
   * library, which no longer guesses one from the request's shape.
   *
   * <p><b>{@code desk} is gone.</b> It was a wire-level compatibility mapping and nothing else —
   * {@code AgentDesk} had already retired into the surface vocabulary, and this door translated its
   * two names ({@code EPICS}, {@code TICKETS}) for one release so a frontend that had not shipped
   * the new field kept working. Every frontend sends {@code surface} now, so the translation comes
   * out (task 56a914b7) and the surface is the only steering key left in the system. A caller still
   * sending {@code desk} is ignored rather than served: an unnamed surface is refused, which is the
   * answer it should get.
   */
  static AgentLaunchRequest launchRequest(JsonObject json) {
    return new AgentLaunchRequest(
        parseEnum(json.getString("scope"), AgentMcpScope::valueOf, "scope"),
        surfaceOf(json.getString("surface")),
        parseEnum(json.getString("mode"), AgentLaunchMode::valueOf, "mode"),
        json.getString("initialContext"),
        json.getString("resumeSessionId"),
        Boolean.TRUE.equals(json.getBoolean("fork")),
        Boolean.TRUE.equals(json.getBoolean("deliverTaskPrompt")),
        parseEnum(json.getString("agentType"), AgentType::valueOf, "agentType"));
  }

  /**
   * The surface a launch names, or null for "the caller named none" — which the launch itself
   * refuses, so the 400 distinguishes an unknown surface from an absent one.
   */
  private static AgentSurface surfaceOf(String surface) {
    if (surface == null || surface.isBlank()) {
      return null;
    }
    // Unknown is refused, by AgentSurface.of, with the message the API answers as a 400.
    return AgentSurface.of(surface);
  }

  /**
   * Enum-valued request fields. Null and blank mean "not stated" and are left for the launch to
   * default; anything else must parse.
   */
  private static <T> T parseEnum(String raw, Function<String, T> of, String name) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return of.apply(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidCommandRequestException("Invalid " + name + ": " + raw);
    }
  }

  /**
   * The 409 body a launch against a harness nobody has signed in answers with.
   *
   * <p><b>{@code error} is a required discriminator and is the whole point of this shape.</b> The
   * caller has to tell "nobody is signed in" from "this daemon is broken" without reading prose: a
   * frontend matching the case on {@code message} would be the same display-string-as-contract
   * mistake the {@code " (tickets desk)"} match was, and it would freeze a sentence written for a
   * human. With the key, the sentence is free to be reworded and translated.
   *
   * <p>{@code agentType} is there so the caller can name the harness — and open the right sign-in
   * terminal at {@code POST /agents/sign-in} — without parsing it out of the message.
   *
   * <p>{@code message} keeps the key every other error body on this server uses, so a client with no
   * special handling still shows something true.
   */
  static JsonObject notSignedIn(AgentNotSignedInException refusal) {
    return new JsonObject()
        .put("error", "not-signed-in")
        .put("agentType", refusal.harness() == null ? null : refusal.harness().name())
        .put("message", refusal.getMessage());
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
