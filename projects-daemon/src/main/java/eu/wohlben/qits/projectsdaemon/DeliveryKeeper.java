package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentSurfaceConfiguration;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.GuardedInput;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Lifecycle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * The delivery desk's share of the daemon: the delivery settings, the loop's brief, and the seam
 * into the front desk session.
 *
 * <p><b>Nothing types turns on a schedule</b> (qits-1597). The timer that used to type a delivery
 * pass into the front desk (qits-1440) is gone, its setting with it, and nothing replaces it: the
 * desk runs a pass only when a person prompts it — in the session itself, or through {@link
 * FrontDeskInbox} ({@code POST /front-desk/prompt}).
 *
 * <p><b>One desk, one session</b> (owner ruling, 2026-10-22). There is no separate {@code
 * project.delivery} session: the front desk holds the desk's delivery rights — the delivery token,
 * {@code qits:desk}, handed to it at launch by {@link FrontDeskCredential} — while {@link Settings}
 * say delivery is {@code on}. This class launches nothing. The {@code project.delivery} surface
 * survives only as the store of the loop's brief ({@link #writeBrief}), written at boot to {@link
 * #BRIEF_PATH} for a prompted pass to read.
 */
final class DeliveryKeeper {

  private static final Logger LOG = Logger.getLogger(DeliveryKeeper.class);

  /** The surface whose {@code systemPrompt} is the loop's brief. Nothing launches it. */
  static final AgentSurface BRIEF_SURFACE = AgentSurface.PROJECT_DELIVERY;

  /** Where the brief is written at boot, for a prompted delivery pass to read. */
  static final Path BRIEF_PATH = Path.of("/tmp/qits-delivery-brief.md");

  /**
   * The brief: the {@code project.delivery} surface's {@code systemPrompt} from the injected agent
   * configuration document, or — when the document carries none, or a blank one — a short fallback
   * that points the session at the project's delivery rules.
   *
   * @param project what {@code qits projects delivery --project} is given: an id, slug or name
   */
  static String brief(AgentSurfaceConfigurations configurations, String project) {
    String configured =
        configurations == null
            ? ""
            : configurations
                .document()
                .map(document -> document.surfaces().get(BRIEF_SURFACE.key()))
                .map(AgentSurfaceConfiguration::systemPrompt)
                .orElse("");
    if (!configured.isBlank()) {
      return configured.endsWith("\n") ? configured : configured + "\n";
    }
    return "You are this project's delivery desk, running inside its front desk session.\n"
        + "Read the project's delivery rules first, every pass:\n"
        + "  qits projects delivery --project "
        + (project == null || project.isBlank() ? "<this project>" : project)
        + "\n"
        + "Then do one pass of the delivery loop within those rules, and end your turn."
        + " Never loop or sleep inside a turn.\n";
  }

  /**
   * Write {@link #brief} to {@code path}, world-readable ({@code 0644}). Never throws: a brief that
   * could not be written costs a WARN, and the session reads a missing file as nothing to follow.
   */
  static void writeBrief(Path path, AgentSurfaceConfigurations configurations, String project) {
    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(path, brief(configurations, project), StandardCharsets.UTF_8);
      try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
      } catch (UnsupportedOperationException e) {
        // Not a POSIX file system; the default mode is what it gets.
      }
      LOG.infof("The delivery brief is at %s.", path);
    } catch (IOException | RuntimeException e) {
      LOG.warnf("Could not write the delivery brief to %s: %s", path, e.getMessage());
    }
  }

  /**
   * The delivery settings, as {@link ControlSocket} read them.
   *
   * @param enabled {@code qits.projects-daemon.delivery} is {@code on}
   * @param token the delivery token, or blank
   * @param tokenSubject whom it names, or blank
   */
  record Settings(boolean enabled, String token, String tokenSubject) {

    /** Delivery off, as an unset configuration reads. */
    static final Settings OFF = new Settings(false, "", "");

    /**
     * Lenient, like {@link Lifecycle#parse}: {@code on} (any case) is on, unset or blank is off, and
     * anything else logs one WARN and is off — a typo must not hand the desk rights nobody asked
     * for.
     */
    static Settings parse(
        Optional<String> delivery, Optional<String> token, Optional<String> tokenSubject) {
      return new Settings(enabled(delivery), valueOf(token), valueOf(tokenSubject));
    }

    /** Whether the front desk holds the delivery rights by these settings: on, and a token. */
    boolean runnable() {
      return enabled && !token.isBlank();
    }

    private static boolean enabled(Optional<String> configured) {
      String value = valueOf(configured).trim();
      if (value.isEmpty() || value.equalsIgnoreCase("off")) {
        return false;
      }
      if (value.equalsIgnoreCase("on")) {
        return true;
      }
      LOG.warnf(
          "Unknown qits.projects-daemon.delivery '%s' (expected on or off); treating it as off.",
          value);
      return false;
    }

    private static String valueOf(Optional<String> configured) {
      return configured == null ? "" : configured.orElse("");
    }
  }

  /**
   * What {@link FrontDeskInbox} needs of the front desk's session, behind one seam a test can fake.
   */
  interface Session {

    /** Whether {@code commandId} is RUNNING. */
    boolean running(String commandId);

    /** The last activity state the hooks reported for {@code commandId}, if any. */
    Optional<String> activityState(String commandId);

    /** Types {@code text} into the session, unless a person has an unsent draft there. */
    GuardedInput send(String commandId, String text);

    /** The production session over this daemon's store, registry, agent commands and hooks. */
    static Session of(
        CommandStore store,
        CommandRegistry registry,
        AgentCommands commands,
        Function<String, Optional<String>> activity) {
      return new Session() {
        @Override
        public boolean running(String commandId) {
          return store
                  .find(commandId)
                  .map(command -> command.status() == CommandStatus.RUNNING)
                  .orElse(false)
              && registry.isRunning(commandId);
        }

        @Override
        public Optional<String> activityState(String commandId) {
          return activity.apply(commandId);
        }

        @Override
        public GuardedInput send(String commandId, String text) {
          return commands.sendKeystrokesUnlessDraft(commandId, text);
        }
      };
    }
  }
}
