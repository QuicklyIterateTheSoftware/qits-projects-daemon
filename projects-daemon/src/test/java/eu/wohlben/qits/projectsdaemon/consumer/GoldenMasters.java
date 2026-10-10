package eu.wohlben.qits.projectsdaemon.consumer;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * <b>A provider's recorded answers, as this repository's consumer pacts read them</b> (ticket
 * qits-1149, after epic qits-546). Copied from qits-edge-service, itself adapted from qits-maintenance-service's class of the same name.
 *
 * <p>Two changes from that copy:
 *
 * <ul>
 *   <li><b>Any provider.</b> Every provider publishes its tree as {@code golden-masters/} on the
 *       classpath, so two pinned jars hold two {@code golden-masters/index.json}. Each index is
 *       found through {@link ClassLoader#getResources} and picked by its {@code provider} field,
 *       and its files are read from the same jar.
 *   <li><b>Only what the consumer reads.</b> {@link #interaction} takes the body paths the daemon
 *       consumes and builds the response from those paths alone. A field the daemon never reads is
 *       not in the pact, so the provider is free to change it. An empty set gives a status-only
 *       interaction.
 * </ul>
 *
 * <p>Matchers follow the index's {@code frozen} lists: {@code ids} get a UUID matcher, {@code
 * instants} an ISO-8601 regex, {@code listFilteredTo} a {@code minArrayLike}, every other array an
 * exact length with one merged template, every other leaf a type match.
 */
public final class GoldenMasters {

  /** The consumer, as every pact names it: the repository name. */
  public static final String CONSUMER = "qits-projects-daemon";

  /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
  public static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * A provider: its repository name (the pact's provider) and its application name (the index's).
   */
  public record Provider(String repository, String application) {}

  /** One recorded (state, operation), as the index describes it. */
  public record Operation(
      Provider provider,
      String state,
      String operationId,
      String method,
      String path,
      int status,
      String file,
      Set<String> ids,
      Set<String> instants,
      String listFilteredTo) {}

  /**
   * What made the daemon make the call — the {@code qits-trigger} reference. {@code operation} names
   * an inbound request the daemon serves; {@code schedule} a startup or timed job.
   */
  public record Trigger(String kind, String key, String value) {

    public Trigger {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    public static Trigger operation(String operationId) {
      return new Trigger("operation", "operationId", operationId);
    }

    public static Trigger schedule(String schedule) {
      return new Trigger("schedule", "schedule", schedule);
    }

    Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", CONSUMER);
      ref.put(key, value);
      return ref;
    }
  }

  /** The request the daemon sends, as the pact states it. */
  public record Request(
      String method,
      String path,
      Map<String, Object> query,
      Map<String, Object> headers,
      Object body) {

    public static Request get(String path) {
      return new Request("GET", path, Map.of(), Map.of(), null);
    }
  }

  private static final Map<String, Index> INDEXES = new ConcurrentHashMap<>();

  private record Index(JsonNode tree, URL base) {}

  private GoldenMasters() {}

  /** The interaction's description: the trigger first, so (description, state) stays unique. */
  public static String description(String operationId, Trigger trigger) {
    return trigger.value() + ": " + operationId;
  }

  /** The index entry for one (state, operation); fails naming both when the index has none. */
  public static Operation operation(Provider provider, String state, String operationId) {
    for (JsonNode op : stateNode(provider, state).path("operations")) {
      if (operationId.equals(op.path("operationId").asText())) {
        JsonNode frozen = op.path("frozen");
        JsonNode filtered = frozen.path("listFilteredTo");
        return new Operation(
            provider,
            state,
            operationId,
            op.path("method").asText(),
            op.path("path").asText(),
            op.path("status").asInt(),
            op.path("file").asText(),
            strings(frozen.path("ids")),
            strings(frozen.path("instants")),
            filtered.isTextual() ? filtered.asText() : null);
      }
    }
    throw new IllegalArgumentException(
        provider.application()
            + "'s golden masters record no "
            + operationId
            + " in state '"
            + state
            + "'");
  }

  /** The recorded JSON for one (state, operation) — a fresh tree each call. */
  public static JsonNode json(Provider provider, String state, String operationId) {
    Operation op = operation(provider, state, operationId);
    try (InputStream in = new URL(index(provider).base(), op.file()).openStream()) {
      return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Add the V4 HTTP interaction for one recorded (state, operation), reached from {@code trigger}.
   *
   * @param request what the daemon sends — its own expectation, never the recording's request
   * @param view narrows the recording to what the provider answers THIS request (for example, a
   *     list filtered by the query the daemon sends); identity when the request is the recorded one
   * @param consumes the body paths the daemon reads, {@code $.a.b} or {@code $.list[*].field}; empty
   *     for status only
   */
  public static PactBuilder interaction(
      PactBuilder builder,
      Provider provider,
      String state,
      String operationId,
      Trigger trigger,
      Request request,
      UnaryOperator<JsonNode> view,
      Set<String> consumes) {
    Objects.requireNonNull(
        trigger, "trigger: every interaction names the entry point that makes it");
    Operation op = operation(provider, state, operationId);
    if (!op.method().equalsIgnoreCase(request.method()) || !op.path().equals(request.path())) {
      throw new IllegalArgumentException(
          description(operationId, trigger)
              + ": the request "
              + request.method()
              + " "
              + request.path()
              + " is not the recorded "
              + op.method()
              + " "
              + op.path());
    }
    DslPart body = consumes.isEmpty() ? null : responseBody(op, view, consumes);
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", provider.repository());
    call.put("operationId", operationId);
    references.put("qits-call", call);
    references.put("qits-trigger", trigger.reference());
    return builder.expectsToReceiveHttpInteraction(
        description(operationId, trigger),
        http -> {
          http.state(state, new LinkedHashMap<String, Object>(params(provider, state)));
          http.withRequest(
              r -> {
                r.method(request.method()).path(request.path());
                request.query().forEach(r::queryParameter);
                request.headers().forEach(r::header);
                if (request.body() instanceof DslPart part) {
                  r.body(part);
                } else if (request.body() instanceof String text) {
                  Object type = request.headers().get("Content-Type");
                  if (type instanceof String contentType) {
                    r.body(text, contentType);
                  } else {
                    r.body(text);
                  }
                }
                return r;
              });
          http.willRespondWith(
              r -> {
                r.status(op.status());
                if (body != null) {
                  r.header(
                          "Content-Type", Matchers.regexp("application/json.*", "application/json"))
                      .body(body);
                }
                return r;
              });
          // pact-jvm 4.6's DSL has no setter for an arbitrary comment group, but the V4 model's
          // comments map is mutable and written verbatim.
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  /** The provider state's frozen example params. */
  public static Map<String, String> params(Provider provider, String state) {
    Map<String, String> params = new LinkedHashMap<>();
    stateNode(provider, state)
        .path("params")
        .fields()
        .forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
    return params;
  }

  // --- the body ---------------------------------------------------------------------------------

  static DslPart responseBody(Operation op, UnaryOperator<JsonNode> view, Set<String> consumes) {
    JsonNode recorded = view.apply(json(op.provider(), op.state(), op.operationId()));
    if (!recorded.isObject()) {
      throw new IllegalStateException(where(op) + ": only an object body is supported");
    }
    for (String path : consumes) {
      if (!present(recorded, path)) {
        throw new IllegalStateException(
            where(op) + " records nothing at " + path + ", which the daemon reads");
      }
    }
    PactDslJsonBody root = new PactDslJsonBody();
    fillObject(root, recorded, "$", op, consumes);
    return root;
  }

  /** Whether {@code path} is a consumed path or the parent of one. */
  private static boolean wanted(String path, Set<String> consumes) {
    for (String consumed : consumes) {
      if (consumed.equals(path)
          || consumed.startsWith(path + ".")
          || consumed.startsWith(path + "[*]")) {
        return true;
      }
    }
    return false;
  }

  private static void fillObject(
      PactDslJsonBody target, JsonNode node, String path, Operation op, Set<String> consumes) {
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String name = field.getKey();
      String childPath = path + "." + name;
      if (!wanted(childPath, consumes)) {
        continue;
      }
      JsonNode child = field.getValue();
      if (child.isNull()) {
        target.nullValue(name);
      } else if (child.isObject()) {
        PactDslJsonBody nested = target.object(name);
        fillObject(nested, child, childPath, op, consumes);
        nested.closeObject();
      } else if (child.isArray()) {
        array(target, name, (ArrayNode) child, childPath, op, consumes);
      } else {
        leaf(target, name, child, childPath, op);
      }
    }
  }

  private static void leaf(
      PactDslJsonBody target, String name, JsonNode example, String path, Operation op) {
    if (op.ids().contains(path)) {
      target.uuid(name, example.asText());
    } else if (op.instants().contains(path)) {
      target.stringMatcher(name, ISO_INSTANT, example.asText());
    } else if (example.isTextual()) {
      target.stringType(name, example.asText());
    } else if (example.isNumber()) {
      target.numberType(name, example.numberValue());
    } else if (example.isBoolean()) {
      target.booleanType(name, example.asBoolean());
    } else {
      throw new IllegalStateException(where(op) + ": " + path + " is not a scalar");
    }
  }

  private static void array(
      PactDslJsonBody target,
      String name,
      ArrayNode array,
      String path,
      Operation op,
      Set<String> consumes) {
    int n = array.size();
    if (n == 0) {
      target.array(name).closeArray();
      return;
    }
    String elementPath = path + "[*]";
    boolean filtered = path.equals(op.listFilteredTo());
    if (!array.get(0).isContainerNode()) {
      // A list of scalars, such as a role list: one type-matched template for every element.
      PactDslJsonRootValue value = rootLeaf(array.get(0), elementPath, op);
      if (filtered) {
        target.minArrayLike(name, n, value, n);
      } else {
        target.minMaxArrayLike(name, n, n, value, n);
      }
      return;
    }
    ObjectNode merged = MAPPER.createObjectNode();
    for (JsonNode element : array) {
      if (!element.isObject()) {
        throw new IllegalStateException(
            where(op) + ": " + elementPath + " holds a non-object, not supported yet");
      }
      // Keep the first non-null example of each field: the template stands for every element.
      element
          .fields()
          .forEachRemaining(
              e -> {
                JsonNode known = merged.get(e.getKey());
                if (known == null || known.isNull()) {
                  merged.set(e.getKey(), e.getValue());
                }
              });
    }
    PactDslJsonBody template =
        filtered ? target.minArrayLike(name, n, n) : target.minMaxArrayLike(name, n, n, n);
    fillObject(template, merged, elementPath, op, consumes);
    DslPart closed = template.closeObject();
    ((PactDslJsonArray) closed).closeArray();
  }

  private static PactDslJsonRootValue rootLeaf(JsonNode example, String path, Operation op) {
    if (op.ids().contains(path)) {
      return PactDslJsonRootValue.uuid(example.asText());
    }
    if (op.instants().contains(path)) {
      return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
    }
    if (example.isTextual()) {
      return PactDslJsonRootValue.stringType(example.asText());
    }
    if (example.isNumber()) {
      return PactDslJsonRootValue.numberType(example.numberValue());
    }
    if (example.isBoolean()) {
      return PactDslJsonRootValue.booleanType(example.asBoolean());
    }
    throw new IllegalStateException(where(op) + ": " + path + " holds a " + example.getNodeType());
  }

  /** Whether the recording holds a non-null value at {@code path} (in at least one element). */
  private static boolean present(JsonNode root, String path) {
    if (!path.startsWith("$")) {
      throw new IllegalArgumentException("a consumed path starts with $: " + path);
    }
    return presentAt(root, path.substring(1));
  }

  private static boolean presentAt(JsonNode node, String rest) {
    if (rest.isEmpty()) {
      return node != null && !node.isNull() && !node.isMissingNode();
    }
    if (rest.startsWith("[*]")) {
      if (!node.isArray()) {
        return false;
      }
      if (node.isEmpty()) {
        return true; // an empty list holds every element path vacuously
      }
      for (JsonNode element : node) {
        if (presentAt(element, rest.substring(3))) {
          return true;
        }
      }
      return false;
    }
    if (!rest.startsWith(".")) {
      throw new IllegalArgumentException("not a path: " + rest);
    }
    String tail = rest.substring(1);
    int cut = tail.length();
    for (int i = 0; i < tail.length(); i++) {
      char c = tail.charAt(i);
      if (c == '.' || c == '[') {
        cut = i;
        break;
      }
    }
    return presentAt(node.path(tail.substring(0, cut)), tail.substring(cut));
  }

  // --- reading the jars -------------------------------------------------------------------------

  private static Index index(Provider provider) {
    return INDEXES.computeIfAbsent(provider.application(), GoldenMasters::load);
  }

  private static Index load(String application) {
    ClassLoader loader = GoldenMasters.class.getClassLoader();
    try {
      Enumeration<URL> found = loader.getResources("golden-masters/index.json");
      while (found.hasMoreElements()) {
        URL url = found.nextElement();
        JsonNode tree;
        try (InputStream in = url.openStream()) {
          tree = MAPPER.readTree(in);
        }
        if (application.equals(tree.path("provider").asText())) {
          if (tree.path("formatVersion").asInt() != 1) {
            throw new IllegalStateException(
                url + " is formatVersion " + tree.path("formatVersion") + "; this reads 1");
          }
          return new Index(tree, new URL(url, "./"));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    throw new IllegalStateException(
        "no golden-masters/index.json of "
            + application
            + " on the test classpath — is"
            + " eu.wohlben.qits:"
            + application
            + "-golden-masters a test dependency?");
  }

  private static JsonNode stateNode(Provider provider, String state) {
    for (JsonNode node : index(provider).tree().path("states")) {
      if (state.equals(node.path("name").asText())) {
        return node;
      }
    }
    throw new IllegalArgumentException(
        provider.application() + "'s golden masters record no state '" + state + "'");
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new LinkedHashSet<>();
    array.forEach(e -> out.add(e.asText()));
    return Set.copyOf(out);
  }

  private static String where(Operation op) {
    return "golden master "
        + op.provider().application()
        + " "
        + op.state()
        + "/"
        + op.operationId();
  }
}
