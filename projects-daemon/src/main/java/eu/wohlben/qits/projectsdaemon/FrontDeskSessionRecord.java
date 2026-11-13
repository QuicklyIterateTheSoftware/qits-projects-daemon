package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.protocol.NamedAgents;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * The front desk's current session id, kept on the persistent claude volume at {@code
 * <claudeMount>/.qits/front-desk/<projectId>.session} so a new daemon process can resume the desk
 * without qits having stored the id. qits' {@code front_desk.session_id} is filled from the {@code
 * sessionReport}, and a report sent while qits' database is down is lost: the 2026-10-27 outage
 * restarted the desk's container with nothing in qits and nothing in memory, and the desk launched
 * fresh beside a transcript it could have resumed. The volume outlives the container; this file is
 * the daemon's own memory of what ran.
 *
 * <p>The volume is shared by every project container on a node, hence the project id in the path.
 * The file holds the id and nothing else, written atomically (a temp file moved over it), {@code
 * 0644}, and only when the id changed. Nothing here throws: a record that cannot be read or written
 * costs one WARN, and the desk falls back to what qits names, as before.
 *
 * <p><b>A named agent keeps one too</b> (qits-2154), at {@code
 * <claudeMount>/.qits/named-agents/<projectId>/<name>.session} ({@link #ofNamedAgent}): it is a kept
 * session qits resumes the same way, and it loses its id to the same outage. Same file format, same
 * rules; only the path and the log's noun differ. A refinement key keeps none ({@link
 * #forSessionKey}).
 *
 * <p>Read and written on the owning ladder's thread only ({@link
 * SessionLadder.Spec#recordedSession}, {@link SessionLadder.Spec#recordSession}).
 */
final class FrontDeskSessionRecord {

  private static final Logger LOG = Logger.getLogger(FrontDeskSessionRecord.class);

  /** A project id that is one plain path segment; anything else records nothing. */
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");

  /** Records nothing and remembers nothing: tests, and a daemon without a project id or mount. */
  static final FrontDeskSessionRecord NONE = new FrontDeskSessionRecord(null, "front desk");

  private final Path path;

  /** Whose record it is, for the log: {@code front desk} or {@code named agent <name>}. */
  private final String owner;

  /** The id the file holds, as last read or written; null when unknown or none. */
  private String current;

  private boolean warned;

  private FrontDeskSessionRecord(Path path, String owner) {
    this.path = path;
    this.owner = owner;
  }

  /** {@code projectId}'s desk record under {@code claudeMount}; {@link #NONE} without both. */
  static FrontDeskSessionRecord of(String claudeMount, String projectId) {
    if (claudeMount == null
        || claudeMount.isBlank()
        || projectId == null
        || !SEGMENT.matcher(projectId).matches()) {
      return NONE;
    }
    return at(Path.of(claudeMount, ".qits", "front-desk", projectId + ".session"));
  }

  /**
   * The named agent {@code name}'s record under {@code claudeMount}, at {@code
   * .qits/named-agents/<projectId>/<name>.session}; {@link #NONE} without a mount, a plain project
   * id or a {@linkplain NamedAgents#validName valid name}.
   */
  static FrontDeskSessionRecord ofNamedAgent(String claudeMount, String projectId, String name) {
    if (claudeMount == null
        || claudeMount.isBlank()
        || projectId == null
        || !SEGMENT.matcher(projectId).matches()
        || !NamedAgents.validName(name)) {
      return NONE;
    }
    return new FrontDeskSessionRecord(
        Path.of(claudeMount, ".qits", "named-agents", projectId, name + ".session"),
        "named agent " + name);
  }

  /**
   * The record the multiplex keeps for session key {@code key}: a named agent's own ({@link
   * #ofNamedAgent}), {@link #NONE} for every other key — a refinement resumes only what qits names.
   */
  static FrontDeskSessionRecord forSessionKey(String claudeMount, String projectId, String key) {
    return NamedAgents.namedAgentName(key)
        .map(name -> ofNamedAgent(claudeMount, projectId, name))
        .orElse(NONE);
  }

  /** The record at {@code path}. */
  static FrontDeskSessionRecord at(Path path) {
    return new FrontDeskSessionRecord(path, "front desk");
  }

  /** The file this record lives in, or null for {@link #NONE}. */
  Path path() {
    return path;
  }

  /** The recorded session id, or null: none recorded, or the file could not be read. */
  String read() {
    if (path == null) {
      return null;
    }
    try {
      if (!Files.isRegularFile(path)) {
        return null;
      }
      String id = Files.readString(path, StandardCharsets.UTF_8).strip();
      if (id.isEmpty() || id.chars().anyMatch(Character::isWhitespace)) {
        return null;
      }
      current = id;
      return id;
    } catch (IOException | RuntimeException e) {
      warnOnce("read", e);
      return null;
    }
  }

  /** Record {@code sessionId}, unless it is what the file already holds. Never throws. */
  void write(String sessionId) {
    if (path == null || sessionId == null || sessionId.isBlank() || sessionId.equals(current)) {
      return;
    }
    Path temp = null;
    try {
      Path parent = path.getParent();
      Files.createDirectories(parent);
      temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
      Files.writeString(temp, sessionId + "\n", StandardCharsets.UTF_8);
      try {
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
      } catch (UnsupportedOperationException e) {
        // Not a POSIX file system; the default mode is what it gets.
      }
      try {
        Files.move(
            temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
      temp = null;
      current = sessionId;
      LOG.debugf("Recorded the %s's session %s at %s", owner, sessionId, path);
    } catch (IOException | RuntimeException e) {
      warnOnce("write", e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException | RuntimeException e) {
          // Left behind; the next write's temp file has a name of its own.
        }
      }
    }
  }

  private void warnOnce(String what, Exception e) {
    if (warned) {
      LOG.debugf("Could not %s the %s's session record %s: %s", what, owner, path, e.getMessage());
      return;
    }
    warned = true;
    LOG.warnf(
        "Could not %s the %s's session record %s (%s); a new daemon process resumes only"
            + " what qits names",
        what, owner, path, e.getMessage());
  }
}
