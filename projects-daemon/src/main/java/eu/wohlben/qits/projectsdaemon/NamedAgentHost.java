package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.SessionKeeper.Scheduler;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.ReportedState;
import eu.wohlben.qits.projectsdaemon.protocol.NamedAgents;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The named agents this desk's container hosts (qits-2154), as the loopback API reaches them: {@code
 * GET /named-agents}, {@code POST /named-agents/{name}/prompt} and {@code GET
 * /named-agents/{name}/last-answer}.
 *
 * <p><b>The sessions are not kept here.</b> A named agent is a key of the {@code sessions} frame,
 * {@code agent:<name>} ({@link NamedAgents}), and {@link SessionKeeper} keeps it on its generic
 * ladder like any other key — its own surface, its own credential, its own session record. This
 * class only reads where each one stands ({@link #roster}) and turns prompts into it: <b>one {@link
 * FrontDeskInbox} per agent</b>, the desk's inbox over that agent's kept command, with the desk's
 * rules — typed at once while it is idle, else a FIFO of {@link FrontDeskInbox#CAPACITY} flushed one
 * turn per {@code IDLE} — plus the one a named agent needs: while its key is wanted and its session
 * is still launching ({@code STARTING}), a prompt queues for the session's first {@code IDLE}, since
 * it is prompting that has qits start it.
 *
 * <p><b>Only what the frame names.</b> A name the current frame does not carry is no agent of this
 * container, and every route answers it 404 — an inbox exists only for a name the frame named. The
 * front desk is not one of these: it keeps {@link FrontDeskKeeper}, its own inbox and {@code
 * /front-desk/*}, untouched.
 */
final class NamedAgentHost {

  /**
   * One named agent, as the frame names it and the session report places it.
   *
   * @param name the agent's name, e.g. {@code ui-builder}
   * @param key its session key, {@code agent:<name>}
   * @param surface the agent surface its entry names, e.g. {@code project.ui-builder}; may be null
   * @param state the key's reported state: {@code STARTING}, {@code RUNNING}, {@code STOPPED} or
   *     {@code FAILED}
   * @param commandId the command running it while {@code RUNNING}, else null
   * @param sessionId its harness session id while {@code RUNNING}, once known, else null
   */
  record Agent(
      String name, String key, String surface, String state, String commandId, String sessionId) {

    /** The command a prompt is typed into: the running one, if any. */
    Optional<String> runningCommand() {
      return ReportedState.RUNNING.equals(state) ? Optional.ofNullable(commandId) : Optional.empty();
    }

    /** Wanted, and its session not up yet: a prompt waits for it. */
    boolean launching() {
      return ReportedState.STARTING.equals(state);
    }
  }

  private final Supplier<List<Agent>> roster;
  private final DeliveryKeeper.Session session;
  private final Function<String, List<String>> transcript;
  private final Scheduler scheduler;
  private final LongSupplier clock;

  private final Map<String, FrontDeskInbox> inboxes = new ConcurrentHashMap<>();

  /** The last command each agent ran, so its answer outlives the session that gave it. */
  private final Map<String, String> lastCommands = new ConcurrentHashMap<>();

  /**
   * @param roster the named agents of the current frame ({@link SessionKeeper#namedAgents})
   * @param session the seam every inbox types through ({@link DeliveryKeeper.Session})
   * @param transcript the raw TRANSCRIPT channel lines of a command, oldest first
   * @param scheduler where every inbox's flush runs, off the hook webhook's event loop
   */
  NamedAgentHost(
      Supplier<List<Agent>> roster,
      DeliveryKeeper.Session session,
      Function<String, List<String>> transcript,
      Scheduler scheduler,
      LongSupplier clock) {
    this.roster = roster;
    this.session = session;
    this.transcript = transcript;
    this.scheduler = scheduler;
    this.clock = clock;
  }

  /** Every named agent the current frame names, in its order. */
  List<Agent> agents() {
    List<Agent> agents = roster.get();
    agents = agents == null ? List.of() : agents;
    for (Agent agent : agents) {
      agent.runningCommand().ifPresent(command -> lastCommands.put(agent.name(), command));
    }
    return agents;
  }

  /** The named agent {@code name}, if the current frame names it. */
  Optional<Agent> find(String name) {
    return agents().stream().filter(agent -> agent.name().equals(name)).findFirst();
  }

  /**
   * Type {@code text} into the named agent {@code name}, or queue it ({@link
   * FrontDeskInbox#prompt}). Empty when the frame names no such agent.
   */
  Optional<FrontDeskInbox.Outcome> prompt(String name, String text) {
    if (find(name).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(inbox(name).prompt(text));
  }

  /**
   * The named agent {@code name}'s latest answer: its running session's, else the last session's it
   * ran in this daemon process, else one with every field null. Empty when the frame names no such
   * agent.
   */
  Optional<FrontDeskInbox.Answer> lastAnswer(String name) {
    if (find(name).isEmpty()) {
      return Optional.empty();
    }
    FrontDeskInbox inbox = inbox(name);
    Optional<FrontDeskInbox.Answer> running = inbox.lastAnswer();
    if (running.isPresent()) {
      return running;
    }
    String last = lastCommands.get(name);
    return Optional.of(
        last == null
            ? new FrontDeskInbox.Answer(null, null, null, null, null)
            : inbox.answerFor(last));
  }

  /**
   * One activity of a command, as {@link HookWebhook} stores it: each agent's inbox flushes on its
   * own command's {@code IDLE} and ignores everybody else's.
   */
  void onActivity(String commandId, String state) {
    for (FrontDeskInbox inbox : inboxes.values()) {
      inbox.onActivity(commandId, state);
    }
  }

  /** Stop flushing, for every agent. */
  void stop() {
    scheduler.shutdown();
  }

  /** {@code name}'s inbox, made on its first prompt or answer. */
  private FrontDeskInbox inbox(String name) {
    return inboxes.computeIfAbsent(
        name,
        agent ->
            new FrontDeskInbox(
                () -> current(agent).flatMap(Agent::runningCommand),
                () -> current(agent).map(Agent::launching).orElse(false),
                "Named agent " + agent,
                session,
                transcript,
                scheduler,
                clock));
  }

  /** {@code name} as the roster stands now, without remembering anything. */
  private Optional<Agent> current(String name) {
    List<Agent> agents = roster.get();
    return agents == null
        ? Optional.empty()
        : agents.stream().filter(agent -> agent.name().equals(name)).findFirst();
  }
}
