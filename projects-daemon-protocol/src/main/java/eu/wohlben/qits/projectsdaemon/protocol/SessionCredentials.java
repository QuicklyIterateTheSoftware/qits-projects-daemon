package eu.wohlben.qits.projectsdaemon.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * qits → {@code projects-daemon}: the tokens this desk's sessions run on, one per session key
 * (qits-1482). Sent on {@code hello} and again on every change, and always the <b>full</b> list: a
 * frame replaces whatever the daemon held, so a key absent from it has no credential any more.
 *
 * <p>Tokens travel over the control socket and never touch a container environment, which is the
 * point — anything in the container's environment is readable by every process under its uid.
 *
 * <p>Sent only to a daemon announcing {@link DaemonProtocol#CAPABILITY_VERSION} 2 or later; an
 * older daemon would drop the frame as an unknown type.
 *
 * @param credentials one per session key; never null on the wire (decoded empty when absent)
 */
public record SessionCredentials(List<Credential> credentials) implements DaemonMessage {

  /**
   * The key of the front desk's own session — the one {@code project.work} session the keeper holds
   * open, which also runs the project's delivery loop as turns while the loop is on. qits puts the
   * desk's delivery token ({@code qits:agent} + {@code qits:desk}) under it while the loop is on,
   * and an ordinary desk-session token otherwise.
   */
  public static final String FRONT_DESK_KEY = "front-desk";

  /**
   * The key every launch a person starts from the desk panel ({@code POST /agents}) runs on: a
   * desk-session token ({@code qits:agent} only), so a person-started session never holds {@code
   * qits:desk}. A refinement session's key is its entity id (qits-770).
   */
  public static final String DESK_AGENTS_KEY = "desk-agents";

  public SessionCredentials {
    credentials = credentials == null ? List.of() : List.copyOf(credentials);
  }

  /** The credential for {@code key}, if this frame carries one. */
  public Optional<Credential> find(String key) {
    return credentials.stream().filter(credential -> credential.key().equals(key)).findFirst();
  }

  /**
   * One session's credential.
   *
   * @param key which session runs on it: {@link #FRONT_DESK_KEY} or an entity id
   * @param token the bearer the session gets as its {@code QITS_TOKEN}
   * @param subject whom it names, the session's {@code QITS_TOKEN_SUBJECT}
   */
  public record Credential(String key, String token, String subject) {

    public Credential {
      Objects.requireNonNull(key, "key");
      token = token == null ? "" : token;
      subject = subject == null ? "" : subject;
    }

    /** Names the key and the subject and never the token, so a logged frame leaks nothing. */
    @Override
    public String toString() {
      return "Credential[key=" + key + ", subject=" + subject + ", token=<redacted>]";
    }
  }
}
