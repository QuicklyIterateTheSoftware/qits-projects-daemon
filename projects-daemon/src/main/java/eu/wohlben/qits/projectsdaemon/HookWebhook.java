package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.projectsdaemon.protocol.AgentActivity;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.AgentState;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * The daemon's one <em>inbound</em> listener: a loopback HTTP server the in-container coding
 * agent's lifecycle hooks POST to. A qits-launched {@code claude}/{@code kimi} carries a hook that
 * {@code curl}s {@code http://127.0.0.1:<port>/hooks/claude-code?commandId=<id>} with the hook's
 * stdin JSON ({@code hook_event_name}, {@code session_id}, {@code transcript_path}, {@code
 * source}); this maps the event to an {@link AgentState} and relays an unsolicited {@link
 * AgentActivity} home over the control socket. The daemon's only inbound listener besides the
 * loopback API, and the reason the agent's turn boundaries are visible in qits at all.
 *
 * <p>Bound to {@code 127.0.0.1} only (the hook and the daemon share the container's network
 * namespace, so loopback reaches it and nothing outside the container can). The response is a bare
 * {@code 200} returned as soon as the body is read — a hook must never add latency to a turn.
 *
 * <p>The last state per {@code commandId} is retained so {@link #reportCurrent()} can replay it on
 * a socket reconnect, so a qits restart that lost its in-memory projection rebuilds it. {@code Stop}
 * maps to {@code IDLE} (turn finished) but
 * also fires when Claude pauses to ask the user — so a {@code Stop} arriving while the stored state
 * is {@code WAITING} (a preceding {@code Notification}) is dropped, keeping the permission-prompt
 * signal.
 *
 * <p>Not every {@code Notification} is a prompt, though (qits-1440). Claude Code also sends one
 * about a minute after a turn ends while the session sits untouched — {@code notification_type}
 * {@code idle_prompt}, {@code message} "Claude is waiting for your input" — and reading that as
 * {@code WAITING} parked every finished session there until its next prompt, which is exactly the
 * state {@link FrontDeskInbox} refuses to type into: a turn for the front desk would queue behind
 * a prompt nobody has to answer. So an idle prompt maps to {@code IDLE} (see {@link #isIdlePrompt}); a {@code
 * permission_prompt}, and any notification this daemon does not recognise, stays {@code WAITING} —
 * an unknown one might still be a prompt somebody has to answer. An idle prompt never downgrades a
 * stored {@code WAITING}, by the same rule as {@code Stop}: since idle prompts no longer store
 * {@code WAITING}, every stored {@code WAITING} came from a notification that was not one, so the
 * stored state alone is enough to tell a pending prompt apart and no cause needs remembering.
 */
final class HookWebhook {

  private static final Logger LOG = Logger.getLogger(HookWebhook.class);

  static final String PATH = "/hooks/claude-code";

  /** The {@code notification_type} Claude Code gives the after-a-turn idle reminder. */
  static final String IDLE_PROMPT = "idle_prompt";

  /**
   * The text of that reminder's {@code message}, matched when an older Claude Code sends no {@code
   * notification_type} at all.
   */
  static final String IDLE_PROMPT_MESSAGE = "waiting for your input";

  private final Vertx vertx;
  private final int port;
  private final Consumer<DaemonMessage> send;

  /** Last activity per qits command id; replayed by {@link #reportCurrent()}, evicted on end. */
  private final Map<String, AgentActivity> lastByCommand = new ConcurrentHashMap<>();

  /**
   * Set once {@link eu.wohlben.qits.projectsdaemon.ControlSocket#wireAgents} has built the {@link
   * AgentLaunchService} — this webhook is created and started independent of, and before,
   * provisioning finishes (see {@link ControlSocket#start()}), so a hook can fire before there is
   * one to tell. Held as a {@link BiConsumer} (commandId, state) rather than the service type
   * itself so a test can drive {@link #handle}'s forwarding without standing up the whole launch
   * machinery; {@link #setAgentLaunch(AgentLaunchService)} is what production wires. Null until
   * then, in which case {@link #handle} simply has nobody to forward to.
   */
  private volatile BiConsumer<String, String> agentLaunch;

  /**
   * Hears every stored state per command, beside {@link #agentLaunch}: the front desk's inbox
   * ({@link FrontDeskInbox#onActivity}), which flushes a queued turn on {@code IDLE}. Null until
   * {@link ControlSocket#wireAgents} sets it.
   */
  private volatile BiConsumer<String, String> activityListener;

  private volatile HttpServer server;

  HookWebhook(Vertx vertx, int port, Consumer<DaemonMessage> send) {
    this.vertx = vertx;
    this.port = port;
    this.send = send;
  }

  /** Wires the service that turns a stored activity into an interactive-rename signal. */
  void setAgentLaunch(AgentLaunchService agentLaunch) {
    this.agentLaunch = agentLaunch == null ? null : agentLaunch::onActivity;
  }

  /** Test seam: wire a bare listener instead of a real {@link AgentLaunchService}. */
  void setAgentLaunch(BiConsumer<String, String> listener) {
    this.agentLaunch = listener;
  }

  /** Wires the listener that hears every stored state ({@link FrontDeskInbox#onActivity}). */
  void setActivityListener(BiConsumer<String, String> listener) {
    this.activityListener = listener;
  }

  void start() {
    server = vertx.createHttpServer();
    server
        .requestHandler(this::onRequest)
        .listen(port, "127.0.0.1")
        .onSuccess(s -> LOG.infof("projects-daemon hook webhook listening on 127.0.0.1:%d", port))
        .onFailure(t -> LOG.errorf(t, "projects-daemon hook webhook failed to bind :%d", port));
  }

  private void onRequest(HttpServerRequest request) {
    if (request.method() != HttpMethod.POST || !PATH.equals(request.path())) {
      request.response().setStatusCode(404).end();
      return;
    }
    String commandId = request.getParam("commandId");
    request.bodyHandler(
        body -> {
          try {
            JsonObject json = body.length() == 0 ? new JsonObject() : new JsonObject(body);
            handle(json.getString("hook_event_name"), json, commandId);
          } catch (RuntimeException e) {
            LOG.debugf("projects-daemon dropped an undecodable hook payload: %s", e.getMessage());
          }
          request.response().setStatusCode(200).end();
        });
  }

  /**
   * Maps one hook payload to an {@link AgentActivity} and relays it. Package-private so a test can
   * drive the event→state mapping, the Notification override, and the reconnect replay without a
   * real HTTP fork. Uninteresting events
   * ({@code SubagentStop}, {@code PreToolUse}, …) and payloads with no command correlation are
   * dropped.
   */
  void handle(String hookEvent, JsonObject body, String commandId) {
    boolean idlePrompt = "Notification".equals(hookEvent) && isIdlePrompt(body);
    String state = idlePrompt ? AgentState.IDLE : mapState(hookEvent);
    if (state == null || commandId == null || commandId.isBlank()) {
      return;
    }
    // A turn-finished Stop, or an idle-prompt Notification, must not downgrade a pending
    // permission prompt (WAITING wins).
    AgentActivity current = lastByCommand.get(commandId);
    if (("Stop".equals(hookEvent) || idlePrompt)
        && current != null
        && AgentState.WAITING.equals(current.state())) {
      // Nothing changes in lastByCommand and no frame goes out, but the launch service still hears
      // the stored state — WAITING, never the raw Stop's IDLE — so a queued rename keeps waiting on
      // the permission prompt rather than firing into it. The same holds for an idle prompt.
      notifyAgentLaunch(commandId, current.state());
      return;
    }
    AgentActivity activity =
        new AgentActivity(
            commandId,
            body.getString("session_id"),
            state,
            hookEvent,
            body.getString("source"),
            body.getString("transcript_path"),
            System.currentTimeMillis());
    if (AgentState.ENDED.equals(state)) {
      lastByCommand.remove(commandId);
    } else {
      lastByCommand.put(commandId, activity);
    }
    send.accept(activity);
    notifyAgentLaunch(commandId, state);
  }

  /**
   * Tells the wired {@link AgentLaunchService} the stored state, if one is wired yet. This is the
   * hook-response path: it must never throw into {@link #onRequest}'s {@code 200}, so any failure
   * the listener raises is logged and swallowed here rather than left to propagate.
   */
  private void notifyAgentLaunch(String commandId, String state) {
    BiConsumer<String, String> listener = agentLaunch;
    if (listener != null) {
      try {
        listener.accept(commandId, state);
      } catch (RuntimeException e) {
        LOG.debugf(e, "AgentLaunchService.onActivity failed for command %s", commandId);
      }
    }
    BiConsumer<String, String> activity = activityListener;
    if (activity != null) {
      try {
        activity.accept(commandId, state);
      } catch (RuntimeException e) {
        LOG.debugf(e, "The activity listener failed for command %s", commandId);
      }
    }
  }

  /**
   * The last state stored for {@code commandId} — what {@link FrontDeskInbox} reads to tell an
   * idle front desk from a busy one. Empty when nothing was heard yet or the session ended.
   */
  Optional<String> lastState(String commandId) {
    AgentActivity activity = commandId == null ? null : lastByCommand.get(commandId);
    return activity == null ? Optional.empty() : Optional.ofNullable(activity.state());
  }

  /** Re-send the last known activity for every still-tracked command (reconnect adoption). */
  void reportCurrent() {
    for (AgentActivity activity : lastByCommand.values()) {
      send.accept(activity);
    }
  }

  void close() {
    HttpServer s = server;
    if (s != null) {
      s.close();
    }
  }

  /**
   * Whether a {@code Notification} body is Claude Code's idle reminder rather than a prompt: its
   * {@code notification_type} is {@code idle_prompt}, or — only when the type is absent, as from an
   * older Claude Code — its {@code message} says it is waiting for input. A present type that is
   * anything else ({@code permission_prompt}, {@code elicitation_dialog}, one never seen) is not.
   */
  static boolean isIdlePrompt(JsonObject body) {
    if (body == null) {
      return false;
    }
    Object type = body.getValue("notification_type");
    if (type != null) {
      return IDLE_PROMPT.equals(type);
    }
    Object message = body.getValue("message");
    return message instanceof String text
        && text.toLowerCase(Locale.ROOT).contains(IDLE_PROMPT_MESSAGE);
  }

  private static String mapState(String hookEvent) {
    if (hookEvent == null) {
      return null;
    }
    return switch (hookEvent) {
      case "SessionStart" -> AgentState.IDLE;
      case "UserPromptSubmit" -> AgentState.BUSY;
      case "Stop" -> AgentState.IDLE;
      case "Notification" -> AgentState.WAITING;
      case "SessionEnd" -> AgentState.ENDED;
      default -> null; // SubagentStop / PreToolUse / … — not a main-agent state transition
    };
  }
}
