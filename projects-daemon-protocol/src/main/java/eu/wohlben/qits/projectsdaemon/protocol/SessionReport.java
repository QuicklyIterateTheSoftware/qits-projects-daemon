package eu.wohlben.qits.projectsdaemon.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code projects-daemon} → qits: where every session key of the multiplex stands (epic qits-770),
 * the answer to {@link Sessions}.
 *
 * <p><b>Always the full list, and qits replaces what it held.</b> It names {@link
 * SessionCredentials#FRONT_DESK_KEY} and every key of the last {@link Sessions} frame, in that
 * frame's order after the front desk. A key qits left out of its frame is left out here too — its
 * session is being (or has been) stopped — so a key absent from a report is not running.
 *
 * <p>Sent when a key's state, command, session id or detail changes; once right after each {@link
 * Sessions} frame is applied (also when nothing changed, so a periodic frame is answered with the
 * current state); and once when the control socket reconnects. Never sent before the first {@link
 * Sessions} frame: a daemon under a capability-2 qits reports nothing.
 *
 * @param sessions one per key; never null on the wire (decoded empty when absent)
 */
public record SessionReport(List<Session> sessions) implements DaemonMessage {

  public SessionReport {
    sessions = sessions == null ? List.of() : List.copyOf(sessions);
  }

  /** The entry for {@code key}, if this report names it. */
  public Optional<Session> find(String key) {
    return sessions.stream().filter(session -> session.key().equals(key)).findFirst();
  }

  /**
   * One key's state.
   *
   * @param key the session key, as {@link Sessions} named it
   * @param state one of {@link DaemonProtocol.ReportedState}: {@code STARTING} — wanted, not
   *     running yet (waiting for the checkout, for its credential's grace window, for a person's own
   *     desk session to end, or between an exit and its relaunch); {@code RUNNING} — its session is
   *     up; {@code STOPPED} — not wanted, and nothing of it is running; {@code FAILED} — wanted, and
   *     the last launch was refused (no credential for the key, harness not signed in, the harness
   *     refused it, the boot provision failed). The daemon keeps retrying a {@code FAILED} key on its
   *     ladder; it is a state, not a verdict
   * @param commandId the daemon command running it — the id {@code /commands} and the terminal and
   *     chat sockets know it by — while {@code RUNNING}; null otherwise
   * @param sessionId the harness session id while {@code RUNNING}, once known — what qits sends back
   *     as {@link Sessions.Session#resumeSessionId}; null otherwise
   * @param detail a sentence for a person — why it is {@code STARTING} or {@code FAILED}, or {@code
   *     stopping} while a stopped key's session is still exiting — or null. Never parsed: the state
   *     is the contract, this is prose, and it never carries a token
   */
  public record Session(
      String key, String state, String commandId, String sessionId, String detail) {

    public Session {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(state, "state");
    }
  }
}
