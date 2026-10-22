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
import eu.wohlben.qits.projectsdaemon.FrontDeskKeeper.Scheduler;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.AgentState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Runs the project's delivery loop as turns in the front desk session (qits-1440): every {@code
 * delivery-interval} it types one {@link #tickTurn} into the {@code project.work} session {@link
 * FrontDeskKeeper} launched and keeps.
 *
 * <p><b>One desk, one session</b> (owner ruling, 2026-10-22). There is no separate {@code
 * project.delivery} session: the front desk holds the desk's delivery rights — the delivery token,
 * {@code qits:desk}, handed to it at launch by {@link FrontDeskCredential} — and the loop is a turn
 * like any other in it. This class launches nothing. The {@code project.delivery} surface survives
 * only as the store of the loop's brief ({@link #writeBrief}), which the tick turn points at.
 *
 * <p><b>It ticks</b> only when {@code qits.projects-daemon.delivery} is {@code on} <em>and</em> a
 * delivery token is set; otherwise it logs once at INFO and does nothing.
 *
 * <p><b>The tick</b> goes to the keeper's own command ({@link FrontDeskKeeper#keptCommandId()}),
 * never to a {@code project.work} session a person started from the desk panel — that one is
 * scrubbed and holds no delivery rights. It is typed when that command is RUNNING and its last
 * reported activity is {@code IDLE}. Busy, waiting on a permission prompt, not yet heard from, not
 * running, or holding a person's unsent draft: the tick is skipped. The first two consecutive skips
 * log at DEBUG; the third, and every one after it, logs at INFO, naming the reason plus how many
 * ticks in a row were skipped and since when. Only that count — and when the run of skips started —
 * is remembered, and only for the log; a tick that writes resets it to zero. The next skipped (or
 * written) tick comes a full interval later either way. Ticks never queue: each schedules the next
 * only once it is done, on this class's one worker thread.
 */
final class DeliveryKeeper {

  private static final Logger LOG = Logger.getLogger(DeliveryKeeper.class);

  /** The surface whose {@code systemPrompt} is the loop's brief. Nothing launches it. */
  static final AgentSurface BRIEF_SURFACE = AgentSurface.PROJECT_DELIVERY;

  /** Where the brief is written, and what the tick turn tells the session to read. */
  static final Path BRIEF_PATH = Path.of("/tmp/qits-delivery-brief.md");

  /** The tick interval when none (or an unreadable one) is configured. */
  static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(10);

  /** What {@link #start()} logs when there is nothing to tick, so a test can name it. */
  static final String OFF_MESSAGE =
      "Delivery is off (qits.projects-daemon.delivery): the front desk runs no delivery loop.";

  static final String NO_TOKEN_MESSAGE =
      "Delivery is on but no delivery token is set (QITS_DELIVERY_TOKEN): the front desk runs no"
          + " delivery loop.";

  /**
   * The turn one tick types: one pass of the loop by the brief, stamped so the transcript tells
   * ticks apart.
   */
  static String tickTurn(Instant now) {
    return "Run one pass of the delivery loop: read "
        + BRIEF_PATH
        + " and follow it. (tick "
        + now
        + ")";
  }

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
   * @param interval how often the loop is nudged
   * @param token the delivery token, or blank
   * @param tokenSubject whom it names, or blank
   */
  record Settings(boolean enabled, Duration interval, String token, String tokenSubject) {

    /** Delivery off, as an unset configuration reads. */
    static final Settings OFF = new Settings(false, DEFAULT_INTERVAL, "", "");

    /**
     * Lenient, like {@link Lifecycle#parse}: {@code on} (any case) is on, unset or blank is off, and
     * anything else logs one WARN and is off — a typo must not hand the desk rights nobody asked
     * for. An interval that is not a positive ISO-8601 duration logs one WARN and is {@link
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

    /** Whether the front desk runs the delivery loop: on, and a token to run it with. */
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

  /** What the tick needs of the front desk's session, behind one seam a test can fake. */
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

  private final Settings settings;
  private final Supplier<Optional<String>> frontDesk;
  private final Session session;
  private final Scheduler scheduler;
  private final LongSupplier clock;

  private volatile boolean started;
  private volatile boolean stopped;

  // Touched only on this keeper's one worker thread (the scheduler that runs tick()/nudge()).
  private int consecutiveSkips;
  private Instant skippedSince;

  /**
   * @param frontDesk the command the front desk keeper launched and keeps ({@link
   *     FrontDeskKeeper#keptCommandId()}) — the only session a tick is typed into
   */
  DeliveryKeeper(
      Settings settings,
      Supplier<Optional<String>> frontDesk,
      Session session,
      Scheduler scheduler,
      LongSupplier clock) {
    this.settings = settings;
    this.frontDesk = frontDesk;
    this.session = session;
    this.scheduler = scheduler;
    this.clock = clock;
  }

  /** Begin ticking the front desk, once; a no-op unless {@link Settings#runnable}. */
  void start() {
    if (!settings.runnable()) {
      LOG.info(settings.enabled() ? NO_TOKEN_MESSAGE : OFF_MESSAGE);
      return;
    }
    if (started || stopped) {
      return;
    }
    started = true;
    LOG.infof("Delivery is on: ticking the front desk every %s.", settings.interval());
    scheduler.schedule(this::tick, settings.interval().toMillis());
  }

  /** Stop ticking; the session itself is the front desk keeper's. */
  void stop() {
    stopped = true;
    scheduler.shutdown();
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
    Optional<String> kept = frontDesk.get();
    if (kept.isEmpty() || !session.running(kept.get())) {
      skip("the front desk the keeper launched is not running");
      return;
    }
    String commandId = kept.get();
    Optional<String> state = session.activityState(commandId);
    if (!state.map(AgentState.IDLE::equalsIgnoreCase).orElse(false)) {
      skip(
          "the front desk is "
              + state.map(value -> value.toLowerCase(Locale.ROOT)).orElse("not reported yet"));
      return;
    }
    GuardedInput written =
        session.send(commandId, tickTurn(Instant.ofEpochMilli(clock.getAsLong())));
    if (written != GuardedInput.WRITTEN) {
      skip(String.valueOf(written));
      return;
    }
    consecutiveSkips = 0;
  }

  /**
   * Record one skipped tick and log it: DEBUG for the first two in a row, INFO from the third on —
   * named with {@code reason}, the running count and when the run started.
   */
  private void skip(String reason) {
    consecutiveSkips++;
    if (consecutiveSkips == 1) {
      skippedSince = Instant.ofEpochMilli(clock.getAsLong());
    }
    if (consecutiveSkips <= 2) {
      LOG.debugf("Delivery tick skipped: %s.", reason);
    } else {
      LOG.infof(
          "Delivery tick skipped (%d in a row since %s): %s.",
          consecutiveSkips, skippedSince, reason);
    }
  }

  /** How many ticks in a row have just been skipped; a written tick resets it. For a test. */
  int consecutiveSkips() {
    return consecutiveSkips;
  }
}
