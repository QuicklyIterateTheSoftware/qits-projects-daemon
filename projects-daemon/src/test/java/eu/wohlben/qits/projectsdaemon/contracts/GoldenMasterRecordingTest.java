package eu.wohlben.qits.projectsdaemon.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Records qits-projects-daemon's provider golden masters</b> (ticket qits-1149) — {@code
 * golden-masters/} at the repository root, the source of the published golden-master packages
 * consumers write their pacts against. Modeled on qits-edge-service's class of the same name.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it starts the state ({@link
 * ProviderStates}), asks the daemon's API and renders {@code
 * golden-masters/<state-slug>/<operationId>.json}; then it renders {@code
 * golden-masters/index.json} in the format qits-projects-service set (format version 1). The path is
 * the route template, a query would be recorded apart as {@code query}, and an operation that takes
 * no body records none.
 *
 * <p><b>Nothing is frozen.</b> The state fixes every value, so the body is recorded as it is and the
 * index's {@code frozen} lists are empty.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** The application name, as the index and the golden-master packages name it. */
  static final String PROVIDER = "qits-projects-daemon";

  /** One recorded interaction. {@code path} is the route template; its {@code {params}} come from the state. */
  record Interaction(String state, String operationId, String method, String path, int status) {}

  /**
   * The daemon publishes no OpenAPI document — its API is a raw Vert.x server with no JAX-RS — so
   * the operationIds are this table's, and renaming one is a contract change all the same.
   */
  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.A_DAEMON_WHOSE_HARNESSES_WERE_PROBED,
              "listAvailableAgents",
              "GET",
              "/projects/container/{projectId}/agents/available",
              200));

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path root;

  @Test
  void goldenMastersMatchTheProvider() throws Exception {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();
    ProviderStates states = new ProviderStates();

    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    for (Interaction interaction : INTERACTIONS) {
      JsonNode body;
      Map<String, String> params;
      try (ProviderStates.Setup setup = states.setUp(interaction.state(), root)) {
        params = setup.params();
        body = call(interaction, params, setup.api().port());
      }
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
      params.forEach(frozenParams::put);
      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", frozenParams);
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(frozenParams)) {
        failures.add("State '" + interaction.state() + "' gave different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.putArray("ids");
      frozen.putArray("instants");
      frozen.putArray("strings");
      frozen.putNull("listFilteredTo");
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(body), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  /** The daemon's answer, asked with the bearer the state names, as qits-projects asks it. */
  private static JsonNode call(Interaction interaction, Map<String, String> params, int port)
      throws Exception {
    String path = interaction.path();
    for (Map.Entry<String, String> param : params.entrySet()) {
      path = path.replace("{" + param.getKey() + "}", param.getValue());
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + params.get("apiToken"))
            .method(interaction.method(), HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + path
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + response.body());
    }
    return JSON.readTree(response.body());
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
