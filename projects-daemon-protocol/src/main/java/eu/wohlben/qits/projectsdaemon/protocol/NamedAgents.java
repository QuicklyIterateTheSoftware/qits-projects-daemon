package eu.wohlben.qits.projectsdaemon.protocol;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The session key of a <b>named agent</b> (qits-2154): a kept Claude session in the desk's
 * container beside the front desk, tied to no entity, one per project and name — {@code
 * ui-builder} is the first. Its key is {@code agent:<name>}, and that one key names it everywhere
 * the multiplex names a session: the {@link Sessions} entry that asks for it (its own surface,
 * {@code entity} null), the {@link SessionCredentials} credential it runs on, and its {@link
 * SessionReport} entry.
 *
 * <p><b>The name is a path segment and a file name</b> — the daemon serves {@code
 * /named-agents/{name}/…} and records the agent's session at {@code
 * .qits/named-agents/<projectId>/<name>.session} — so it is held to {@link #NAME}: lower-case, a
 * letter first, at most 32 characters. A key whose name does not match is not a named agent's,
 * which is what keeps an arbitrary string in a frame from becoming a path.
 *
 * <p>Understood from {@link DaemonProtocol#CAPABILITY_VERSION} 4 on: an older daemon would run an
 * {@code agent:} key on its generic ladder like any other but serve no route for it.
 */
public final class NamedAgents {

  /** What every named agent's session key starts with. */
  public static final String KEY_PREFIX = "agent:";

  /** A named agent's name: a lower-case letter, then up to 31 lower-case letters, digits or dashes. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

  private NamedAgents() {}

  /** Whether {@code name} is a valid named agent's name. */
  public static boolean validName(String name) {
    return name != null && NAME.matcher(name).matches();
  }

  /**
   * The session key of the named agent {@code name}: {@code agent:<name>}.
   *
   * @throws IllegalArgumentException when {@code name} is not a {@linkplain #validName valid name}
   */
  public static String namedAgentKey(String name) {
    if (!validName(name)) {
      throw new IllegalArgumentException("Not a named agent's name: '" + name + "'");
    }
    return KEY_PREFIX + name;
  }

  /** The named agent {@code key} belongs to, or empty when it is not a named agent's key. */
  public static Optional<String> namedAgentName(String key) {
    if (key == null || !key.startsWith(KEY_PREFIX)) {
      return Optional.empty();
    }
    String name = key.substring(KEY_PREFIX.length());
    return validName(name) ? Optional.of(name) : Optional.empty();
  }

  /** Whether {@code key} is a named agent's session key. */
  public static boolean isNamedAgentKey(String key) {
    return namedAgentName(key).isPresent();
  }
}
