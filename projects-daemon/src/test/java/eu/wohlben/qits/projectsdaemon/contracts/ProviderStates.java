package eu.wohlben.qits.projectsdaemon.contracts;

import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.projectsdaemon.ContractSeams;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * <b>qits-projects-daemon's provider states</b> (ticket qits-1149): each starts the daemon's API in
 * the situation one consumer needs and hands back its parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} starts a state before it records each operation
 * that names it, and {@link ConsumerPactVerificationTest} starts the state each interaction names.
 *
 * <p><b>A state is a fresh daemon.</b> Nothing is shared between two states: each one binds its own
 * API on an ephemeral loopback port, mounted at {@code /projects/container/<projectId>} as
 * qits-projects mounts it, and the caller closes it.
 *
 * <p><b>Every value is fixed</b>, so a recording holds nothing to freeze. The API demands {@code
 * Authorization: Bearer <apiToken>}; the token is a state param, so a consumer's pact names it.
 */
public final class ProviderStates {

  /** The daemon took its harness report at boot: one harness probed, one not installed. */
  public static final String A_DAEMON_WHOSE_HARNESSES_WERE_PROBED =
      "a daemon whose harnesses were probed";

  /**
   * The daemon's API is bound and its agent surface is not wired yet — the boot window before the
   * harness probe finishes, in which {@code GET /agents/available} answers 503. qits-projects'
   * relay reads that as "not ready" and asks again.
   */
  public static final String A_DAEMON_WHOSE_AGENTS_ARE_NOT_WIRED_YET =
      "a daemon whose agents are not wired yet";

  /**
   * The daemon keeps a front desk (qits-1572): its kept command is RUNNING and idle, so {@code POST
   * /front-desk/prompt} is typed at once ({@code delivered: true}), and its transcript holds one
   * finished turn, so {@code GET /front-desk/last-answer} answers that turn's text.
   */
  public static final String A_DAEMON_WHOSE_FRONT_DESK_IS_RUNNING =
      "a daemon whose front desk is running";

  /** The front desk's kept command. */
  static final String FRONT_DESK_COMMAND_ID = "00000000-0000-4000-8000-00000000fd01";

  /** The Claude session the front desk's transcript names. */
  static final String FRONT_DESK_SESSION_ID = "00000000-0000-4000-8000-00000000fd02";

  /** The front desk's raw TRANSCRIPT channel: one prompt and one assistant text answer. */
  static final List<String> FRONT_DESK_TRANSCRIPT =
      List.of(
          "{\"type\":\"user\",\"sessionId\":\""
              + FRONT_DESK_SESSION_ID
              + "\",\"timestamp\":\"2026-10-01T12:00:00.000Z\","
              + "\"message\":{\"role\":\"user\",\"content\":\"What is ready for dev?\"}}",
          "{\"type\":\"assistant\",\"sessionId\":\""
              + FRONT_DESK_SESSION_ID
              + "\",\"timestamp\":\"2026-10-01T12:00:05.000Z\","
              + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\","
              + "\"text\":\"Two tickets are ready for dev: qits-1 and qits-2.\"}]}}");

  static final String PROJECT_ID = "00000000-0000-4000-8000-000000000001";

  /** The shared secret qits-projects injects as {@code QITS_PROJECTS_DAEMON_API_TOKEN}. */
  static final String API_TOKEN = "daemon-api-token";

  static final String IMAGE_VERSION = "2026.1001.120000";

  /** A started state: its params and its daemon. */
  public record Setup(Map<String, String> params, ContractSeams.RunningApi api)
      implements AutoCloseable {

    @Override
    public void close() {
      api.close();
    }
  }

  private final Map<String, Function<Path, Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_DAEMON_WHOSE_HARNESSES_WERE_PROBED, this::aDaemonWhoseHarnessesWereProbed);
    states.put(A_DAEMON_WHOSE_AGENTS_ARE_NOT_WIRED_YET, this::aDaemonWhoseAgentsAreNotWiredYet);
    states.put(A_DAEMON_WHOSE_FRONT_DESK_IS_RUNNING, this::aDaemonWhoseFrontDeskIsRunning);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Starts the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state, Path root) {
    Function<Path, Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.apply(root);
  }

  /** The state's directory name in {@code golden-masters/}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }

  private Setup aDaemonWhoseHarnessesWereProbed(Path root) {
    return new Setup(
        params(),
        ContractSeams.startApi(
            root, PROJECT_ID, API_TOKEN, IMAGE_VERSION, reportedBy(), probedHarnesses()));
  }

  private Setup aDaemonWhoseFrontDeskIsRunning(Path root) {
    return new Setup(
        params(),
        ContractSeams.startApiWithFrontDesk(
            root,
            PROJECT_ID,
            API_TOKEN,
            IMAGE_VERSION,
            reportedBy(),
            probedHarnesses(),
            FRONT_DESK_COMMAND_ID,
            FRONT_DESK_TRANSCRIPT));
  }

  private static String reportedBy() {
    return "project-agent/" + PROJECT_ID;
  }

  /** The boot-time harness report: one harness probed, one not installed. */
  private static List<HarnessCapabilities> probedHarnesses() {
    return
        List.of(
            new HarnessCapabilities(
                AgentType.CLAUDE,
                "2.1.0 (Claude Code)",
                HarnessCapabilities.CLAUDE_MODELS,
                false,
                true,
                HarnessCapabilities.CLAUDE_EFFORT_LEVELS,
                true,
                "Signed in.",
                false,
                ""),
            HarnessCapabilities.shipped(AgentType.KIMI, "kimi is not installed in this image"));
  }

  private Setup aDaemonWhoseAgentsAreNotWiredYet(Path root) {
    return new Setup(params(), ContractSeams.startApiBeforeAgents(root, PROJECT_ID, API_TOKEN));
  }

  /** Every state's params: the one project and the bearer its API demands. */
  private static Map<String, String> params() {
    Map<String, String> params = new TreeMap<>();
    params.put("projectId", PROJECT_ID);
    params.put("apiToken", API_TOKEN);
    return Collections.unmodifiableMap(params);
  }
}
