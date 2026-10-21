package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.GuardedInput;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Desk;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Lifecycle;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Role;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Scheduler;
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Timing;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.AgentState;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * Keeps this project's delivery desk — one interactive {@code project.delivery} session, which runs
 * the project's delivery loop as the desk's own identity — running, and nudges it every {@code
 * delivery-interval} to run one pass of that loop (qits-1440, qits-1495).
 *
 * <p><b>Running</b> is {@link FrontDeskKeeper}'s ladder under a different {@link Role}: launch when
 * no live {@code project.delivery} session exists, resume while the transcript exists, back off 5 s
 * doubling to 5 min, reset after 10 min up, refresh the claude.ai token before every launch. The
 * role's surface is what keeps the two keepers apart — each one's live-session check matches its
 * own surface only, so neither ever reads the other's session as its own.
 *
 * <p><b>Its credential</b> is the delivery token ({@code QITS_DELIVERY_TOKEN}), handed to the
 * session as its {@code QITS_TOKEN} through the launch's environment overlay — the library also
 * makes it the bearer on that launch's platform MCP servers — so the session acts as {@code
 * qits:desk} where every other child of this daemon acts as the container. {@link
 * DeliveryCredentials} keeps the token away from those.
 *
 * <p><b>It runs</b> only when {@code qits.projects-daemon.delivery} is {@code on} <em>and</em> a
 * delivery token is set; otherwise it logs once at INFO and does nothing. It does not read the
 * lifecycle: a container created with delivery on keeps a delivery desk whether or not it also
 * keeps a front desk.
 *
 * <p><b>The tick</b> types {@link #tickTurn} into the session when it is RUNNING and its last
 * reported activity is {@code IDLE}. Busy, waiting on a permission prompt, not yet heard from, or
 * holding a person's unsent draft: the tick is skipped (DEBUG) and nothing is remembered — the next
 * one comes a full interval later. Ticks never queue: each schedules the next only once it is done,
 * on the keeper's one worker thread.
 */
final class DeliveryKeeper {

  private static final Logger LOG = Logger.getLogger(DeliveryKeeper.class);

  /** The surface the delivery desk runs as. */
  static final AgentSurface SURFACE = AgentSurface.PROJECT_DELIVERY;

  /** The tick interval when none (or an unreadable one) is configured. */
  static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(10);

  /** What {@link #start()} logs when there is nothing to keep, so a test can name it. */
  static final String OFF_MESSAGE =
      "Delivery is off (qits.projects-daemon.delivery): no delivery desk is kept.";

  static final String NO_TOKEN_MESSAGE =
      "Delivery is on but no delivery token is set (QITS_DELIVERY_TOKEN): no delivery desk is"
          + " kept.";

  /** The turn one tick types: one pass of the loop, stamped so the transcript tells ticks apart. */
  static String tickTurn(Instant now) {
    return "Run one pass of the delivery loop (tick " + now + ").";
  }

  /**
   * The delivery settings, as {@link ControlSocket} read them.
   *
   * @param enabled {@code qits.projects-daemon.delivery} is {@code on}
   * @param interval how often the loop is nudged
   * @param token the delivery token, or blank
   * @param tokenSubject whom it names, or blank
   */
  record Settings(boolean enabled, Duration interval, String token, String tokenSubject) {

    /**
     * Lenient, like {@link Lifecycle#parse}: {@code on} (any case) is on, unset or blank is off, and
     * anything else logs one WARN and is off — a typo must not start a desk nobody asked for. An
     * interval that is not a positive ISO-8601 duration logs one WARN and is {@link
     * #DEFAULT_INTERVAL}.
     */
    static Settings parse(
        Optional<String> delivery,
        Optional<String> interval,
        Optional<String> token,
        Optional<String> tokenSubject) {
      return new Settings(
          enabled(delivery), interval(interval), valueOf(token), valueOf(tokenSubject));
    }

    /** Whether there is a desk to keep: on, and a token to keep it with. */
    boolean runnable() {
      return enabled && !token.isBlank();
    }

    /**
     * The session's environment overlay: the delivery token as its {@code QITS_TOKEN} and the
     * subject as its {@code QITS_TOKEN_SUBJECT}. A blank subject removes the container's own rather
     * than leaving it to name a different token.
     */
    Map<String, String> environment() {
      return Map.of("QITS_TOKEN", token, "QITS_TOKEN_SUBJECT", tokenSubject);
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

    private static Duration interval(Optional<String> configured) {
      String value = valueOf(configured).trim();
      if (value.isEmpty()) {
        return DEFAULT_INTERVAL;
      }
      try {
        Duration parsed = Duration.parse(value.toUpperCase(Locale.ROOT));
        if (!parsed.isNegative() && !parsed.isZero()) {
          return parsed;
        }
      } catch (DateTimeParseException e) {
        // reported below
      }
      LOG.warnf(
          "Unreadable qits.projects-daemon.delivery-interval '%s' (expected a positive ISO-8601"
              + " duration such as PT10M); using %s.",
          value, DEFAULT_INTERVAL);
      return DEFAULT_INTERVAL;
    }

    private static String valueOf(Optional<String> configured) {
      return configured == null ? "" : configured.orElse("");
    }
  }

  /** What the tick needs of the running session, behind one seam a test can fake. */
  interface Session {

    /** The live {@code project.delivery} session's command id, whoever started it. */
    Optional<String> liveCommandId();

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
        public Optional<String> liveCommandId() {
          return store.findByStatus(CommandStatus.RUNNING).stream()
              .filter(
                  command ->
                      SURFACE.key().equals(command.agentSurface())
                          && registry.isRunning(command.id()))
              .map(command -> command.id())
              .findFirst();
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

  private final Settings settings;
  private final Session session;
  private final Scheduler scheduler;
  private final LongSupplier clock;
  private final FrontDeskKeeper ladder;

  private volatile boolean started;
  private volatile boolean stopped;

  DeliveryKeeper(
      Settings settings,
      Desk desk,
      Session session,
      Timing timing,
      Scheduler scheduler,
      LongSupplier clock,
      Consumer<DaemonMessage> send) {
    this.settings = settings;
    this.session = session;
    this.scheduler = scheduler;
    this.clock = clock;
    this.ladder =
        new FrontDeskKeeper(
            Lifecycle.ALWAYS_ON,
            new Role(SURFACE, "delivery desk", settings.environment()),
            desk,
            timing,
            scheduler,
            clock,
            send);
  }

  /** Begin keeping the delivery desk and ticking it, once; a no-op unless {@link Settings#runnable}. */
  void start() {
    if (!settings.runnable()) {
      LOG.info(settings.enabled() ? NO_TOKEN_MESSAGE : OFF_MESSAGE);
      return;
    }
    if (started || stopped) {
      return;
    }
    started = true;
    LOG.infof("Delivery is on: ticking the delivery desk every %s.", settings.interval());
    ladder.start();
    scheduler.schedule(this::tick, settings.interval().toMillis());
  }

  /** Stop supervising and ticking; the live session is left to {@code terminateAll()}. */
  void stop() {
    stopped = true;
    ladder.stop();
  }

  /** One tick: nudge the session if it is idle, then schedule the next one. */
  private void tick() {
    if (stopped) {
      return;
    }
    try {
      nudge();
    } catch (RuntimeException e) {
      LOG.warnf("The delivery tick failed: %s", e.getMessage());
    }
    scheduler.schedule(this::tick, settings.interval().toMillis());
  }

  private void nudge() {
    Optional<String> live = session.liveCommandId();
    if (live.isEmpty()) {
      LOG.debug("Delivery tick skipped: no delivery desk is running.");
      return;
    }
    String commandId = live.get();
    Optional<String> state = session.activityState(commandId);
    if (!state.map(AgentState.IDLE::equalsIgnoreCase).orElse(false)) {
      LOG.debugf(
          "Delivery tick skipped: the delivery desk is %s.",
          state.map(value -> value.toLowerCase(Locale.ROOT)).orElse("not reported yet"));
      return;
    }
    GuardedInput written =
        session.send(commandId, tickTurn(Instant.ofEpochMilli(clock.getAsLong())));
    if (written != GuardedInput.WRITTEN) {
      LOG.debugf("Delivery tick skipped: %s.", written);
    }
  }
}
