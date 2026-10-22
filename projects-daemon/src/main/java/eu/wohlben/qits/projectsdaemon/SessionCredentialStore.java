package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials.Credential;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * The tokens this desk's sessions run on, by session key, as qits last sent them over the control
 * socket ({@link SessionCredentials}, qits-1482). In memory only: nothing here is written to disk or
 * to an environment, so a session cannot read another's credential off the container.
 *
 * <p><b>Two modes, and the switch is one-way</b> — with a wait in front of the first.
 *
 * <ul>
 *   <li><b>Waiting</b> — no frame yet, but the grace window ({@code
 *       qits.projects-daemon.session-credentials-grace}, PT20S) is still open. A new qits sends the
 *       frame right after {@code hello}, so a launch asked for in this window would otherwise race
 *       it and start on the container's token for the rest of its life. Instead it is deferred
 *       ({@link AwaitingSessionCredentialsException}): the keeper retries shortly without spending a
 *       backoff step, {@code POST /agents} answers 503 with a {@code Retry-After}. The window opens
 *       at daemon start ({@link #openWindow}) and again when the control socket first connects
 *       ({@link #connectionOpened}), so a daemon that never connects still comes up on the
 *       container's token one window after it started. No window opened (a test, a hand-wired
 *       daemon) means no wait.
 *   <li><b>Legacy</b> — no frame received and the window has passed, which is a server that
 *       predates capability 2. Every
 *       launch runs on the daemon's <em>own</em> token, overlaid explicitly because {@link
 *       DaemonSecrets} keeps it from being inherited. Logged once at INFO. This is exactly what every
 *       session ran on before qits-1482, so a new daemon under an old server changes nothing.
 *   <li><b>Per-session</b> — from the first frame on, for the life of the process. A launch runs on
 *       its key's credential, and a key with none <b>fails closed</b> ({@link
 *       MissingSessionCredentialException}): it never falls back to the daemon's token, because a
 *       fallback is exactly how a refinement session would come to act as the container.
 * </ul>
 *
 * <p>A frame replaces the whole set; a key it leaves out has no credential any more. Thread-safe:
 * the control socket's event loop writes, keeper workers and the API read.
 *
 * <p>Token values are never logged; keys and subjects are.
 */
final class SessionCredentialStore {

  private static final Logger LOG = Logger.getLogger(SessionCredentialStore.class);

  /** What {@link #overlayFor} logs, once, while no frame has arrived. */
  static final String LEGACY_MESSAGE =
      "No sessionCredentials frame from qits: sessions run as before capability 2 (the container's"
          + " own token; the front desk on the delivery token while delivering).";

  /** A launch whose key has no credential in per-session mode. Never falls back. */
  static final class MissingSessionCredentialException extends RuntimeException {
    private final String key;

    MissingSessionCredentialException(String key) {
      super(
          "No session credential for '"
              + key
              + "': qits has not issued one to this desk, so the session is not launched.");
      this.key = key;
    }

    String key() {
      return key;
    }
  }

  /**
   * A launch asked for while the grace window is open and no frame has arrived: not refused, just
   * not yet. Never logged as a failure.
   */
  static final class AwaitingSessionCredentialsException extends RuntimeException {
    private final String key;

    AwaitingSessionCredentialsException(String key) {
      super("Waiting for session credentials from qits; retry in a few seconds.");
      this.key = key;
    }

    String key() {
      return key;
    }
  }

  /** The grace window when none (or an unreadable one) is configured. */
  static final Duration DEFAULT_GRACE = Duration.ofSeconds(20);

  private final Supplier<String> ownToken;
  private final Supplier<String> ownSubject;
  private final LongSupplier clock;

  private volatile Duration grace = Duration.ZERO;

  /** When the open grace window closes, in {@link #clock} millis; MIN_VALUE while none is open. */
  private volatile long windowEndsAt = Long.MIN_VALUE;

  private final AtomicBoolean connectedOnce = new AtomicBoolean();

  private volatile Map<String, Credential> byKey = Map.of();
  private final AtomicBoolean perSession = new AtomicBoolean();
  private final AtomicBoolean legacyLogged = new AtomicBoolean();

  /**
   * @param ownToken the container's own {@code QITS_TOKEN}, for legacy mode; blank when it has none
   * @param ownSubject whom it names
   */
  SessionCredentialStore(Supplier<String> ownToken, Supplier<String> ownSubject) {
    this(ownToken, ownSubject, System::currentTimeMillis);
  }

  /** As above, on {@code clock} — so a test drives the grace window without waiting. */
  SessionCredentialStore(Supplier<String> ownToken, Supplier<String> ownSubject, LongSupplier clock) {
    this.ownToken = ownToken;
    this.ownSubject = ownSubject;
    this.clock = clock;
  }

  /**
   * Open the grace window at daemon start: until {@code grace} has passed (or a frame arrived), a
   * launch is deferred rather than put on the container's token. Also remembers {@code grace} for
   * {@link #connectionOpened}.
   */
  void openWindow(Duration grace) {
    this.grace = grace == null || grace.isNegative() ? Duration.ZERO : grace;
    windowEndsAt = clock.getAsLong() + this.grace.toMillis();
  }

  /**
   * The control socket's first connection is open and its {@code hello} is on the wire: a qits that
   * speaks capability 2 sends the frame now, so the window restarts from here. Only the first
   * connection counts — a reconnect to an old server must not hold every launch up again.
   */
  void connectionOpened() {
    if (connectedOnce.compareAndSet(false, true) && !perSession.get()) {
      windowEndsAt = clock.getAsLong() + grace.toMillis();
    }
  }

  /** Whether a launch would be deferred now: no frame yet and the grace window still open. */
  boolean awaiting() {
    return !perSession.get() && clock.getAsLong() < windowEndsAt;
  }

  /**
   * {@code configured} as a grace window: a non-negative ISO-8601 duration, else one WARN and
   * {@link #DEFAULT_GRACE}. Unset is the default.
   */
  static Duration parseGrace(Optional<String> configured) {
    String value = configured == null ? "" : configured.orElse("").trim();
    if (value.isEmpty()) {
      return DEFAULT_GRACE;
    }
    try {
      Duration parsed = Duration.parse(value.toUpperCase(java.util.Locale.ROOT));
      if (!parsed.isNegative()) {
        return parsed;
      }
    } catch (java.time.format.DateTimeParseException e) {
      // reported below
    }
    LOG.warnf(
        "Unreadable qits.projects-daemon.session-credentials-grace '%s' (expected a non-negative"
            + " ISO-8601 duration such as PT20S); using %s.",
        value, DEFAULT_GRACE);
    return DEFAULT_GRACE;
  }

  /** A store with no own token: legacy launches run with {@code QITS_TOKEN} removed. */
  static SessionCredentialStore withoutOwnToken() {
    return new SessionCredentialStore(() -> "", () -> "");
  }

  /** Replace the whole set with {@code frame}'s, and switch to per-session mode for good. */
  void replace(SessionCredentials frame) {
    Map<String, Credential> next = new LinkedHashMap<>();
    if (frame != null) {
      for (Credential credential : frame.credentials()) {
        if (credential.token().isBlank()) {
          // A key with an empty token is a key without a credential: it fails closed like one.
          LOG.warnf("sessionCredentials named '%s' with no token; it is ignored.", credential.key());
          continue;
        }
        next.put(credential.key(), credential);
      }
    }
    byKey = Map.copyOf(next);
    boolean first = perSession.compareAndSet(false, true);
    LOG.infof(
        "%s session credentials: %s",
        first ? "Per-session mode on;" : "Replaced",
        next.isEmpty()
            ? "none"
            : next.values().stream()
                .map(credential -> credential.key() + " (" + credential.subject() + ")")
                .collect(Collectors.joining(", ")));
  }

  /** Whether a {@link SessionCredentials} frame has ever arrived. Never reverts. */
  boolean perSessionMode() {
    return perSession.get();
  }

  /** The credential qits issued for {@code key}, if any — the lookup qits-770's sessions use. */
  Optional<Credential> find(String key) {
    return Optional.ofNullable(byKey.get(key));
  }

  /**
   * The {@code QITS_TOKEN}/{@code QITS_TOKEN_SUBJECT} overlay a launch for {@code key} runs on: its
   * credential in per-session mode, the daemon's own in legacy mode.
   *
   * @throws AwaitingSessionCredentialsException while no frame has arrived and the grace window is
   *     open; the caller tries again shortly, without counting it as a failure
   * @throws MissingSessionCredentialException in per-session mode when {@code key} has none; the
   *     caller WARNs and does not launch
   */
  Map<String, String> overlayFor(String key) {
    return overlayFor(key, null);
  }

  /**
   * As {@link #overlayFor(String)}, with {@code legacy} answering legacy mode in place of the
   * daemon's own token when it yields a credential. The front desk uses it to run the delivery loop
   * on {@code QITS_DELIVERY_TOKEN} under an older qits, which still puts that token in the
   * container's environment and sends no frame. Null, or an empty answer, is the daemon's own.
   */
  Map<String, String> overlayFor(String key, Supplier<Optional<Map<String, String>>> legacy) {
    if (awaiting()) {
      throw new AwaitingSessionCredentialsException(key);
    }
    if (!perSession.get()) {
      if (legacyLogged.compareAndSet(false, true)) {
        LOG.info(LEGACY_MESSAGE);
      }
      Optional<Map<String, String>> substitute =
          legacy == null ? Optional.empty() : legacy.get();
      return substitute.orElseGet(
          () -> DaemonSecrets.credential(ownToken.get(), ownSubject.get()));
    }
    Credential credential = byKey.get(key);
    if (credential == null) {
      throw new MissingSessionCredentialException(key);
    }
    return DaemonSecrets.credential(credential.token(), credential.subject());
  }
}
