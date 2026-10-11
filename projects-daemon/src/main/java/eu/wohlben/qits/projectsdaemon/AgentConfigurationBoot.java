package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Materializes the per-surface agent configuration this container was created with, at boot, before
 * anything is started — and hands the library the path it was written to.
 *
 * <h2>Why the document arrives as two environment variables</h2>
 *
 * <p>The epic specifies a mounted file, and <b>a host bind mount is not expressible on this
 * estate's container wire</b>: qits-containers' {@code ContainerSpec} carries {@code volumeMounts}
 * and {@code sharedMounts} and no host path at all — deliberately, the shape is the security
 * boundary — and neither host that creates an agent container holds a docker socket to write one
 * with. So the bytes ride the environment and this class writes them down:
 *
 * <ul>
 *   <li>{@code QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION} — the document itself;
 *   <li>{@code QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION_PATH} — where to put it.
 * </ul>
 *
 * <p>Every decision the epic made survives that: the daemon still reads and validates one document
 * once at boot, the launch path is still a pure local render with no runtime dependency on the
 * store, and a container still keeps what it was born with. What moved is the transport. The two
 * names carry this daemon's own prefix, matching the arrangement qits-workspaces-service landed
 * first ({@code QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION} and {@code …_PATH}); the shared
 * <em>shape</em> is the contract, not a shared spelling, because each daemon reads its own
 * environment and hands the library a path.
 *
 * <p>The file is derived state, rewritten from the environment at every boot — never durable state.
 * That is why qits-projects points it under {@code /tmp} rather than at the checkout (where a stray
 * file would show up in somebody's {@code git status}) or at the shared credential volume (where
 * every other container on the estate would write over it).
 *
 * <h2>Absent, half, and broken</h2>
 *
 * <p><b>Neither variable is quiet.</b> That is a real state and a permanent one: every container
 * created before this shipped is in it, and the launch falls back to the constants the library still
 * ships.
 *
 * <p><b>One without the other is loud.</b> A path naming a file nothing wrote, or bytes with nowhere
 * to go, is a host and a daemon disagreeing about the contract — and it must not be readable as "no
 * configuration", because that state is indistinguishable from a working one until somebody notices
 * an agent behaving as it did three releases ago.
 *
 * <p><b>A malformed document is loud too</b>, with the library's own message naming the offending
 * key. Both refusals fail the boot rather than degrading: a container whose configuration cannot be
 * trusted is not a usable agent container, and this image has no {@code sleep infinity} fallback for
 * exactly that reason — it fails visibly instead of idling while looking healthy.
 */
final class AgentConfigurationBoot {

  private static final Logger LOG = Logger.getLogger(AgentConfigurationBoot.class);

  private AgentConfigurationBoot() {}

  /**
   * Write the document and read it back, or answer the shipped constants when this container was
   * created without one.
   *
   * @param document the bytes qits-projects put in the environment, if any
   * @param path where to materialize them, if any
   * @throws IllegalStateException if exactly one of the two was given, or the write fails
   * @throws eu.wohlben.qits.agents.InvalidAgentConfigurationException if the document cannot be
   *     trusted — the library's own message, naming the key
   */
  static AgentSurfaceConfigurations materialize(Optional<String> document, Optional<String> path) {
    String bytes = document.map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
    String target = path.map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
    if (bytes == null && target == null) {
      LOG.info(
          "No agent configuration was injected into this container — every surface launches on the"
              + " library's shipped constants.");
      return AgentSurfaceConfigurations.shipped();
    }
    if (bytes == null) {
      throw new IllegalStateException(
          "Agent configuration path "
              + target
              + " was set but no document came with it: qits.projects-daemon.agent-configuration is"
              + " empty. Both travel together or neither does.");
    }
    if (target == null) {
      throw new IllegalStateException(
          "An agent configuration document was injected with no path to write it to:"
              + " qits.projects-daemon.agent-configuration-path is empty. Both travel together or"
              + " neither does.");
    }
    Path file = write(target, bytes);
    // Reading back what we just wrote rather than parsing the string: the path is what the library
    // is handed and what its failures name, so the one thing that reads the document is the one
    // thing every other host uses too.
    AgentSurfaceConfigurations configurations = AgentSurfaceConfigurations.readFrom(file.toString());
    LOG.infof("Agent configuration materialized at %s", file);
    return configurations;
  }

  private static Path write(String target, String bytes) {
    Path file;
    try {
      file = Path.of(target);
    } catch (InvalidPathException e) {
      throw new IllegalStateException("Agent configuration path is not a path: " + target, e);
    }
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, bytes, StandardCharsets.UTF_8);
    } catch (IOException | UncheckedIOException e) {
      // The container runs as an arbitrary host uid, so an unwritable path is a real possibility
      // and a configuration fault rather than a transient one. Named, not swallowed.
      throw new IllegalStateException(
          "Could not write the agent configuration to " + file + ": " + e.getMessage(), e);
    }
    return file;
  }
}
