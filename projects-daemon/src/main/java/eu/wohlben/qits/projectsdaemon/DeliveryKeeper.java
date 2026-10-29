package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.GuardedInput;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Lifecycle;
import java.util.Optional;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * The delivery desk's share of the daemon: the delivery settings and the seam into the front desk
 * session.
 *
 * <p><b>Nothing types turns on a schedule</b> (qits-1597). The timer that used to type a delivery
 * pass into the front desk (qits-1440) is gone, its setting with it, and nothing replaces it: the
 * desk runs a pass only when a person prompts it — in the session itself, or through {@link
 * FrontDeskInbox} ({@code POST /front-desk/prompt}).
 *
 * <p><b>One desk, one session</b> (owner ruling, 2026-10-22). There is no separate {@code
 * project.delivery} session: the front desk holds the desk's delivery rights — the delivery token,
 * {@code qits:desk}, handed to it at launch by {@link FrontDeskCredential} — while {@link Settings}
 * say delivery is {@code on}. This class launches nothing.
 *
 * <p><b>The daemon writes no brief file any more</b> (qits-1734). The desk's brief is the
 * platform-served guide ({@code qits help guide}), not a file this daemon composes and writes to
 * {@code /tmp}; every prompt that points the desk at it composes {@code DeskBrief} from
 * projects-daemon-protocol instead.
 */
final class DeliveryKeeper {

  private static final Logger LOG = Logger.getLogger(DeliveryKeeper.class);

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
