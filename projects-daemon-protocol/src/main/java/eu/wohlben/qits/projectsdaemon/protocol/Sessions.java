package eu.wohlben.qits.projectsdaemon.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * qits → {@code projects-daemon}: the sessions this desk wants kept, one per session key (epic
 * qits-770). The daemon runs them all in its one container — the multiplex — and answers every
 * change with a {@link SessionReport}.
 *
 * <p><b>Always the full desired list, and the daemon converges on it.</b> A frame replaces whatever
 * the daemon held: a key it names {@link DaemonProtocol.DesiredState#RUNNING} is launched when
 * missing and relaunched when it exits; a key it names {@link DaemonProtocol.DesiredState#STOPPED},
 * or leaves out, has its session terminated (SIGTERM) and its entity forgotten. Sending the same
 * list again changes nothing — no restart, no rename, no reset of a backoff — so qits sends it on
 * {@code hello}, on every change and periodically, and need not diff anything itself.
 *
 * <p>The keys are {@link SessionCredentials}' keys, and a session runs on its key's credential from
 * that frame — a key with none fails closed (the daemon reports it {@link
 * DaemonProtocol.ReportedState#FAILED} and launches nothing), so qits sends the credential no later
 * than the entry:
 *
 * <ul>
 *   <li>{@link SessionCredentials#FRONT_DESK_KEY} — the front desk, surface {@code project.work}.
 *       Its entry replaces the container's {@code QITS_PROJECTS_DAEMON_LIFECYCLE} as the reason to
 *       keep the desk: {@code RUNNING} keeps it, {@code STOPPED} or absent does not — unless the
 *       container's delivery switch is on, which keeps it regardless. Its {@link Session#entity} is
 *       ignored (the desk is about the project, not an entity) and so is its {@link Session#surface}.
 *   <li>an entity's UUID — a refinement's session, surface {@code epic.chat}: launched as an
 *       interactive {@code REPOSITORY}-scoped session named from its {@link Entity}, renamed in
 *       place when a later frame changes the entity's facts.
 * </ul>
 *
 * <p>{@link SessionCredentials#DESK_AGENTS_KEY} is a credential, not a session: it never appears
 * here.
 *
 * <p>Sent only to a daemon announcing {@link DaemonProtocol#CAPABILITY_VERSION} 3 or later; an
 * older daemon would drop the frame as an unknown type. Until the first one arrives the daemon
 * behaves exactly as at capability 2: the front desk follows the lifecycle, no other session is
 * kept, and no {@link SessionReport} is sent.
 *
 * @param sessions one per session key; never null on the wire (decoded empty when absent). A key
 *     named twice is a qits bug; the daemon keeps the first
 */
public record Sessions(List<Session> sessions) implements DaemonMessage {

  public Sessions {
    sessions = sessions == null ? List.of() : List.copyOf(sessions);
  }

  /** The entry for {@code key}, if this frame names it. */
  public Optional<Session> find(String key) {
    return sessions.stream().filter(session -> session.key().equals(key)).findFirst();
  }

  /**
   * One wanted session.
   *
   * @param key the session key: {@link SessionCredentials#FRONT_DESK_KEY} or an entity's UUID
   * @param surface the agent surface it launches as, an {@code AgentSurface} key such as {@code
   *     epic.chat}; an unknown one is reported {@code FAILED}. Ignored for the front desk
   * @param state {@link DaemonProtocol.DesiredState#RUNNING} or {@link
   *     DaemonProtocol.DesiredState#STOPPED}; anything else (or absent) reads as {@code STOPPED} —
   *     a word this daemon does not know must not start a session
   * @param resumeSessionId the harness session to resume when the daemon launches this key, or null.
   *     Preferred over the daemon's own memory of the key's last session, and used only while its
   *     transcript is still on the credential volume — otherwise the launch is fresh. qits learns the
   *     id from {@link SessionReport.Session#sessionId}, so a key survives a container recreation
   * @param entity what the session is about, or null (the front desk; any key without one)
   */
  public record Session(
      String key, String surface, String state, String resumeSessionId, Entity entity) {

    public Session {
      Objects.requireNonNull(key, "key");
      state = DaemonProtocol.DesiredState.RUNNING.equals(state)
          ? DaemonProtocol.DesiredState.RUNNING
          : DaemonProtocol.DesiredState.STOPPED;
    }

    /** Whether this entry asks for the session to be running. */
    public boolean running() {
      return DaemonProtocol.DesiredState.RUNNING.equals(state);
    }
  }

  /**
   * The entity a session is about, as qits stores it — what its session name is rendered from.
   * Raw values: the daemon's harness library sanitises them for a PTY.
   *
   * @param qualifiedId the entity's qualified id, e.g. {@code qits-1234}
   * @param title its title, or null
   * @param status its status word ({@code REPORTED}, {@code REFINED}, {@code READY_FOR_DEV}, …), or
   *     null
   * @param blocked whether it is BLOCKED
   * @param blockSource why, while blocked: {@code EXPLICIT}, {@code AGENT_WAITING} or {@code BOTH};
   *     null reads as explicit
   */
  public record Entity(
      String qualifiedId, String title, String status, boolean blocked, String blockSource) {}
}
