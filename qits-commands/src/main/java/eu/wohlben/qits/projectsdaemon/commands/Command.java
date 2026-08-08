package eu.wohlben.qits.projectsdaemon.commands;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One process launched into this container: what it was, what it ran, and how it ended.
 *
 * <p>There is no owner relation and no owner id. The daemon serves exactly one project, it was
 * told which at container creation, and a command that is not this project's cannot exist — so the
 * scoping is structural rather than a column, and the queries collapse to filters on {@link
 * #status} and {@link #kind}. The identity is put back into the response bodies by {@code
 * CommandJson}, from the ambient {@code ProjectContext}.
 *
 * <p>Immutable, with {@code with*} copies for the two transitions a command actually undergoes
 * (finishing, and gaining a session). {@link CommandStore} holds them; nothing else may mutate one.
 *
 * @param id the durable command id, and the key everything else is attached by
 * @param kind how the process is driven and rendered
 * @param branch the branch checked out at launch
 * @param commitHash the full commit SHA checked out at launch
 * @param actionId the resolved action's id; null for launches not backed by a declared action
 * @param actionName the display name shown on the Commands list
 * @param executeScript the rendered script the shell ran
 * @param status the lifecycle state
 * @param exitCode the process exit code once finished; null while running
 * @param interactive whether a human attaches a terminal to it
 * @param agentType the coding-agent harness this launch drove; null for non-agent commands
 * @param launchedAt when the process was spawned
 * @param finishedAt when it ended; null while running
 * @param agentSessions the ordered agent-session lineage; empty for non-agent commands
 */
public record Command(
    String id,
    CommandKind kind,
    String branch,
    String commitHash,
    String actionId,
    String actionName,
    String executeScript,
    CommandStatus status,
    Integer exitCode,
    boolean interactive,
    String agentType,
    Instant launchedAt,
    Instant finishedAt,
    List<AgentSessionRef> agentSessions) {

  public Command {
    agentSessions =
        agentSessions == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(agentSessions));
  }

  /** A freshly spawned command: RUNNING, no exit code, no end time, no sessions yet. */
  public static Command running(
      String id,
      CommandKind kind,
      String branch,
      String commitHash,
      String actionId,
      String actionName,
      String executeScript,
      boolean interactive,
      String agentType,
      Instant launchedAt) {
    return new Command(
        id,
        kind,
        branch,
        commitHash,
        actionId,
        actionName,
        executeScript,
        CommandStatus.RUNNING,
        null,
        interactive,
        agentType,
        launchedAt,
        null,
        List.of());
  }

  /** The same command, ended. */
  public Command finished(CommandStatus endState, Integer code, Instant at) {
    return new Command(
        id,
        kind,
        branch,
        commitHash,
        actionId,
        actionName,
        executeScript,
        endState,
        code,
        interactive,
        agentType,
        launchedAt,
        at,
        agentSessions);
  }

  /**
   * The same command with {@code session} appended. Appending the session that is already last is a
   * no-op, so a hook that re-reports the current session cannot grow the list without bound — the
   * SessionStart hook fires on every resume, including ones that change nothing.
   */
  public Command withSession(AgentSessionRef session) {
    if (!agentSessions.isEmpty()
        && agentSessions.getLast().sessionId().equals(session.sessionId())) {
      return this;
    }
    List<AgentSessionRef> grown = new ArrayList<>(agentSessions);
    grown.add(session);
    return new Command(
        id,
        kind,
        branch,
        commitHash,
        actionId,
        actionName,
        executeScript,
        status,
        exitCode,
        interactive,
        agentType,
        launchedAt,
        finishedAt,
        grown);
  }

  /** The command's current session — the last entry — or null for a non-agent command. */
  public AgentSessionRef currentSession() {
    return agentSessions.isEmpty() ? null : agentSessions.getLast();
  }

  public boolean isRunning() {
    return status == CommandStatus.RUNNING;
  }
}
