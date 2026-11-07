package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.LogChannel;
import eu.wohlben.qits.commands.LogSeverity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Writes {@code docs/openapi.yml} (repo root; this module is {@code projects-daemon/}, so {@code
 * ../docs} resolves there), the same way the Quarkus services do with their own {@code
 * OpenApiSchemaExportTest} — see qits-deployments-service's for the committed format this one
 * follows.
 *
 * <p>{@link ProjectsApi} carries no JAX-RS at all (see its class javadoc): it is a raw {@code
 * vertx-core} {@link io.vertx.core.http.HttpServer}, so there is no {@code /q/openapi} to dump.
 * This class renders the document by hand from a route table ({@link #OPERATIONS}) built by
 * reading {@link ProjectsApi}'s own dispatch code, rather than from any runtime introspection.
 *
 * <p>It also GUARDS that table against the code: for every operation it starts a daemon with
 * {@link ContractSeams#startApiWithFrontDesk} — commands, agents and the front desk all wired —
 * and sends the request the table describes (the fixed path params substituted, a minimal body on
 * a POST), chosen so nothing it sends actually spawns a harness or a command (an unresolved action
 * id, an agent launch naming no surface, a sign-in naming an invalid harness). The only failure
 * this cares about is {@link ProjectsApi#onRequest} answering the unknown-route 404 — {@code
 * {"message":"No such endpoint"}}, {@link ProjectsJson#error}'s shape — which would mean the table
 * documents a path the daemon no longer serves. Any other status, a legitimate 404 ("Command not
 * found") included, means the table and the server still agree, which is all this asserts; it
 * never checks whether an operation has a golden master or a pact.
 *
 * <p>The document is written before the guard runs, so a route-table drift still leaves {@code
 * docs/openapi.yml} on disk reflecting the table as written — see the build note below for why
 * that ordering matters to a reader of a failed run.
 */
class OpenApiSchemaExportTest {

  private static final String TITLE = "qits-projects-daemon";
  private static final String BASE = "/projects/container/{projectId}";
  private static final String BEARER_SCHEME = "bearerAuth";

  private static final String PROJECT_ID = "openapi-guard-project";
  private static final String API_TOKEN = "openapi-guard-token";
  private static final String FIXED_COMMAND_ID = "openapi-guard-command";

  /** One query or path parameter beyond the universal {@code projectId}. */
  private record Param(String name, String in, List<String> enumValues) {
    private static Param path(String name) {
      return new Param(name, "path", null);
    }

    private static Param query(String name, List<String> enumValues) {
      return new Param(name, "query", enumValues);
    }
  }

  /** A request body this operation accepts, both as its documented schema and as a sample. */
  private record Body(
      boolean required, List<String> requiredProps, List<String> properties, String sample) {}

  /** One documented, and guarded, operation. */
  private record Op(
      String operationId,
      String method,
      String pathSuffix,
      String summary,
      List<Param> params,
      Body body,
      Map<Integer, String> responses) {

    /** The path, with every {@code {param}} filled in for the guard's own request. */
    String samplePath() {
      String path = BASE + pathSuffix;
      path = path.replace("{projectId}", PROJECT_ID);
      path = path.replace("{commandId}", FIXED_COMMAND_ID);
      return path;
    }
  }

  private static final List<String> COMMAND_STATUS_NAMES =
      Arrays.stream(CommandStatus.values()).map(Enum::name).toList();
  private static final List<String> LOG_SEVERITY_NAMES =
      Arrays.stream(LogSeverity.values()).map(Enum::name).toList();
  private static final List<String> LOG_CHANNEL_NAMES =
      Arrays.stream(LogChannel.values()).map(Enum::name).toList();

  /**
   * The twelve operations, in the order {@link ProjectsApi}'s own javadoc lists them. Every status
   * recorded here is one {@link ProjectsApi#dispatchCommand}, {@link ProjectsApi#dispatchAgent} or
   * {@link ProjectsApi#dispatchFrontDesk} can actually answer for that call — including the three
   * surfaces' shared "not available yet" 503 and their shared {@code RuntimeException -> 500}
   * ladders, which apply to every operation of their surface alike.
   */
  private static final List<Op> OPERATIONS =
      List.of(
          new Op(
              "listCommands",
              "get",
              "/commands",
              "The command list",
              List.of(Param.query("status", COMMAND_STATUS_NAMES)),
              null,
              Map.of(
                  200, "The commands, newest launched first",
                  400, "Invalid status",
                  500, "Internal error",
                  503, "Commands are not available yet")),
          new Op(
              "launchCommand",
              "post",
              "/commands",
              "Launch a declared action by id",
              List.of(),
              new Body(true, List.of("actionId"), List.of("actionId"), "{\"actionId\":\"nope\"}"),
              Map.of(
                  200, "The launched command",
                  400, "actionId is missing or names no declared action",
                  500, "Internal error",
                  503, "Commands are not available yet, or the checkout is unavailable")),
          new Op(
              "listCommandActions",
              "get",
              "/commands/actions",
              "What this container declares",
              List.of(),
              null,
              Map.of(
                  200, "The declared actions",
                  500, "Internal error",
                  503, "Commands are not available yet")),
          new Op(
              "getCommand",
              "get",
              "/commands/{commandId}",
              "One command",
              List.of(Param.path("commandId")),
              null,
              Map.of(
                  200, "The command",
                  404, "Command not found",
                  500, "Internal error",
                  503, "Commands are not available yet")),
          new Op(
              "getCommandLog",
              "get",
              "/commands/{commandId}/log",
              "Its captured output",
              List.of(
                  Param.path("commandId"),
                  Param.query("severity", LOG_SEVERITY_NAMES),
                  Param.query("channel", LOG_CHANNEL_NAMES)),
              null,
              Map.of(
                  200, "The captured log lines",
                  400, "Invalid severity or channel",
                  404, "Command not found",
                  500, "Internal error",
                  503, "Commands are not available yet")),
          new Op(
              "terminateCommand",
              "post",
              "/commands/{commandId}/terminate",
              "End it",
              List.of(Param.path("commandId")),
              new Body(false, List.of(), List.of(), "{}"),
              Map.of(
                  200, "The terminated command",
                  404, "Command not found",
                  500, "Internal error",
                  503, "Commands are not available yet")),
          new Op(
              "listAvailableAgents",
              "get",
              "/agents/available",
              "The harnesses, and the default",
              List.of(),
              null,
              Map.of(
                  200, "The available harnesses and the boot-time capability report",
                  500, "Internal error",
                  503, "Coding agents are not available yet")),
          new Op(
              "launchAgent",
              "post",
              "/agents",
              "Launch a coding agent",
              List.of(),
              new Body(false, List.of(), List.of("scope", "surface"), "{}"),
              Map.of(
                  200, "The launched agent command",
                  400, "The request names no scope or no surface, or is otherwise invalid",
                  409, "The harness is not signed in",
                  500, "Internal error",
                  503,
                      "Coding agents are not available yet, or qits has not yet sent session"
                          + " credentials")),
          new Op(
              "openAgentSignIn",
              "post",
              "/agents/sign-in",
              "Open a harness's sign-in terminal",
              List.of(),
              new Body(false, List.of(), List.of("agentType"), "{\"agentType\":\"nope\"}"),
              Map.of(
                  200, "The sign-in terminal command",
                  400, "Invalid agentType",
                  500, "Internal error",
                  503, "Coding agents are not available yet")),
          new Op(
              "listAgentSessions",
              "get",
              "/agent-sessions",
              "The session lineage tree",
              List.of(),
              null,
              Map.of(
                  200, "The session lineage tree",
                  500, "Internal error",
                  503, "Coding agents are not available yet")),
          new Op(
              "promptFrontDesk",
              "post",
              "/front-desk/prompt",
              "Type a turn into the kept front desk, or queue it",
              List.of(),
              new Body(true, List.of("text"), List.of("text"), "{\"text\":\"ping\"}"),
              Map.of(
                  200, "Delivered at once, or queued",
                  400, "text is missing or blank",
                  409, "The front desk is not running",
                  429, "The front desk inbox is full",
                  500, "Internal error",
                  503, "The front desk is not available yet")),
          new Op(
              "getFrontDeskLastAnswer",
              "get",
              "/front-desk/last-answer",
              "What the kept front desk answered last",
              List.of(),
              null,
              Map.of(
                  200, "The latest answer",
                  404, "No front desk is kept",
                  500, "Internal error",
                  503, "The front desk is not available yet")));

  /**
   * Writes the document, then guards it. In that order on purpose: a route-table drift must still
   * leave {@code docs/openapi.yml} on disk as the table currently reads, so a failed run's diff
   * shows what the table claims rather than nothing at all.
   */
  @Test
  void exportOpenApiSchemaAndGuardRouteTable() throws Exception {
    writeDocument();
    guardRouteTable();
  }

  private void writeDocument() throws IOException {
    String yaml = YamlWriter.render(document());
    // The module is `projects-daemon/`, so this resolves to the repo root's docs/.
    Path docsDir = Path.of("../docs");
    if (!Files.exists(docsDir)) {
      Files.createDirectories(docsDir);
    }
    String header =
        "# THIS FILE IS GENERATED BY OpenApiSchemaExportTest — DO NOT EDIT MANUALLY.\n"
            + "# Run `JAVA_TOOL_OPTIONS=-Xmx1g ./mvnw -B -ntp -s .qits-maven-settings.xml -pl"
            + " projects-daemon -am -Dtest=OpenApiSchemaExportTest"
            + " -Dsurefire.failIfNoSpecifiedTests=false test` to regenerate.\n";
    Files.writeString(docsDir.resolve("openapi.yml"), header + yaml);
  }

  /** The OpenAPI 3.0.3 document, as a plain ordered tree {@link YamlWriter} renders. */
  private static Map<String, Object> document() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("openapi", "3.0.3");
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("title", TITLE);
    info.put("version", "1.0.0");
    root.put("info", info);

    Map<String, Object> paths = new LinkedHashMap<>();
    for (Op op : OPERATIONS) {
      String path = BASE + op.pathSuffix();
      @SuppressWarnings("unchecked")
      Map<String, Object> byMethod =
          (Map<String, Object>) paths.computeIfAbsent(path, k -> new LinkedHashMap<String, Object>());
      byMethod.put(op.method(), operationObject(op));
    }
    root.put("paths", paths);

    Map<String, Object> components = new LinkedHashMap<>();
    Map<String, Object> securitySchemes = new LinkedHashMap<>();
    Map<String, Object> bearerAuth = new LinkedHashMap<>();
    bearerAuth.put("type", "http");
    bearerAuth.put("scheme", "bearer");
    securitySchemes.put(BEARER_SCHEME, bearerAuth);
    components.put("securitySchemes", securitySchemes);
    root.put("components", components);
    return root;
  }

  private static Map<String, Object> operationObject(Op op) {
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("operationId", op.operationId());
    operation.put("summary", op.summary());

    List<Object> parameters = new ArrayList<>();
    parameters.add(pathParam("projectId"));
    for (Param param : op.params()) {
      if ("path".equals(param.in())) {
        parameters.add(pathParam(param.name()));
      } else {
        parameters.add(queryParam(param.name(), param.enumValues()));
      }
    }
    operation.put("parameters", parameters);

    if (op.body() != null) {
      operation.put("requestBody", requestBodyObject(op.body()));
    }

    Map<String, Object> responses = new LinkedHashMap<>();
    // Map.of has no defined order; sort by status so the document is stable from run to run.
    List<Integer> statuses = op.responses().keySet().stream().sorted().toList();
    for (Integer status : statuses) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("description", op.responses().get(status));
      Map<String, Object> content = new LinkedHashMap<>();
      Map<String, Object> mediaType = new LinkedHashMap<>();
      Map<String, Object> schema = new LinkedHashMap<>();
      schema.put("type", "object");
      mediaType.put("schema", schema);
      content.put("application/json", mediaType);
      response.put("content", content);
      responses.put(String.valueOf(status), response);
    }
    operation.put("responses", responses);

    List<Object> security = new ArrayList<>();
    Map<String, Object> securityEntry = new LinkedHashMap<>();
    securityEntry.put(BEARER_SCHEME, List.of());
    security.add(securityEntry);
    operation.put("security", security);
    return operation;
  }

  private static Map<String, Object> pathParam(String name) {
    Map<String, Object> param = new LinkedHashMap<>();
    param.put("name", name);
    param.put("in", "path");
    param.put("required", true);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    param.put("schema", schema);
    return param;
  }

  private static Map<String, Object> queryParam(String name, List<String> enumValues) {
    Map<String, Object> param = new LinkedHashMap<>();
    param.put("name", name);
    param.put("in", "query");
    param.put("required", false);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    if (enumValues != null && !enumValues.isEmpty()) {
      schema.put("enum", List.copyOf(enumValues));
    }
    param.put("schema", schema);
    return param;
  }

  private static Map<String, Object> requestBodyObject(Body body) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("required", body.required());
    Map<String, Object> content = new LinkedHashMap<>();
    Map<String, Object> mediaType = new LinkedHashMap<>();
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    if (!body.requiredProps().isEmpty()) {
      schema.put("required", List.copyOf(body.requiredProps()));
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    for (String property : body.properties()) {
      Map<String, Object> propertySchema = new LinkedHashMap<>();
      propertySchema.put("type", "string");
      properties.put(property, propertySchema);
    }
    schema.put("properties", properties);
    // launchAgent's body carries more than scope/surface (mode, resumeSessionId, fork, ...); the
    // rest is irrelevant to this guard and documented as an open object rather than enumerated.
    schema.put("additionalProperties", true);
    mediaType.put("schema", schema);
    content.put("application/json", mediaType);
    requestBody.put("content", content);
    return requestBody;
  }

  /**
   * Starts a daemon with commands, agents and the front desk all wired ({@link
   * ContractSeams#startApiWithFrontDesk}) and sends every operation's request against it, failing
   * only on the unknown-route 404 — see the class javadoc for what that does and does not assert.
   */
  private void guardRouteTable() throws Exception {
    Path root = Files.createTempDirectory("openapi-guard");
    try (ContractSeams.RunningApi api =
        ContractSeams.startApiWithFrontDesk(
            root,
            PROJECT_ID,
            API_TOKEN,
            "test-image",
            "openapi-guard",
            List.<HarnessCapabilities>of(),
            FIXED_COMMAND_ID,
            List.of("fixture front desk transcript line"))) {
      List<String> failures = new ArrayList<>();
      try (HttpClient client = HttpClient.newHttpClient()) {
        for (Op op : OPERATIONS) {
          HttpRequest.Builder builder =
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + op.samplePath()))
                  .header("Authorization", "Bearer " + API_TOKEN);
          String httpMethod = op.method().toUpperCase(Locale.ROOT);
          if (op.body() == null) {
            builder.method(httpMethod, HttpRequest.BodyPublishers.noBody());
          } else {
            builder
                .header("Content-Type", "application/json")
                .method(httpMethod, HttpRequest.BodyPublishers.ofString(op.body().sample()));
          }
          HttpResponse<String> response =
              client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() == 404
              && "{\"message\":\"No such endpoint\"}".equals(response.body())) {
            failures.add(
                op.operationId()
                    + " ("
                    + httpMethod
                    + " "
                    + op.samplePath()
                    + ") answered the unknown-route 404 — the documented path is not one"
                    + " ProjectsApi.onRequest still recognizes");
          }
        }
      }
      if (!failures.isEmpty()) {
        throw new AssertionError(
            "docs/openapi.yml documents operations ProjectsApi no longer serves:\n"
                + String.join("\n", failures));
      }
    }
  }

  /**
   * A minimal, deterministic block-style YAML emitter over a plain tree of {@link Map}, {@link
   * List} and scalars (String/Boolean/Integer) — this module carries no Jackson and {@code
   * jackson-dataformat-yaml} is not on this test's classpath (checked via {@code ./mvnw
   * dependency:tree}), so this writes exactly the shapes {@link #document()} builds and no more.
   *
   * <p>Every string scalar and every status-code key is double-quoted; every other map key is
   * written bare. That is a stricter (and uglier) quoting rule than a hand-written file would use,
   * chosen because it is trivially correct rather than because it is pretty — determinism is what
   * this file is for.
   */
  private static final class YamlWriter {

    private YamlWriter() {}

    static String render(Map<String, Object> root) {
      StringBuilder sb = new StringBuilder();
      writeMap(sb, root, 0);
      return sb.toString();
    }

    private static void writeMap(StringBuilder sb, Map<String, Object> map, int indent) {
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        sb.append(spaces(indent)).append(key(entry.getKey())).append(":");
        writeValue(sb, entry.getValue(), indent);
      }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, int indent) {
      if (value instanceof Map<?, ?> map) {
        if (map.isEmpty()) {
          sb.append(" {}\n");
        } else {
          sb.append("\n");
          writeMap(sb, (Map<String, Object>) map, indent + 2);
        }
      } else if (value instanceof List<?> list) {
        if (list.isEmpty()) {
          sb.append(" []\n");
        } else {
          sb.append("\n");
          writeList(sb, list, indent);
        }
      } else {
        sb.append(" ").append(scalar(value)).append("\n");
      }
    }

    @SuppressWarnings("unchecked")
    private static void writeList(StringBuilder sb, List<?> list, int indent) {
      for (Object item : list) {
        sb.append(spaces(indent)).append("- ");
        if (item instanceof Map<?, ?> map) {
          writeMapInline(sb, (Map<String, Object>) map, indent + 2);
        } else {
          sb.append(scalar(item)).append("\n");
        }
      }
    }

    /** A list item's map: its first key rides the dash's line, the rest indent under it. */
    private static void writeMapInline(StringBuilder sb, Map<String, Object> map, int indent) {
      boolean first = true;
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        if (!first) {
          sb.append(spaces(indent));
        }
        first = false;
        sb.append(key(entry.getKey())).append(":");
        writeValue(sb, entry.getValue(), indent);
      }
    }

    /**
     * Status-code keys ("200", "404", ...) are quoted, like every string scalar; the rest are
     * plain identifiers (operationId, method names, field names) and need no quoting.
     */
    private static String key(String rawKey) {
      return rawKey.chars().allMatch(Character::isDigit) ? quote(rawKey) : rawKey;
    }

    private static String scalar(Object value) {
      if (value instanceof Boolean || value instanceof Integer) {
        return value.toString();
      }
      return quote(String.valueOf(value));
    }

    private static String quote(String value) {
      String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
      return "\"" + escaped + "\"";
    }

    private static String spaces(int indent) {
      return " ".repeat(indent);
    }
  }
}
