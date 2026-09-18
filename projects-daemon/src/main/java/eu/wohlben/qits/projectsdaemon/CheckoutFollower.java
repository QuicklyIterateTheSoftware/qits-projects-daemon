package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Supervises the <b>one</b> child process this container runs beside the agent: {@code qits
 * checkout-daemon --path /workspace}, the platform-access CLI's mode that holds {@code /workspace}
 * at what each repository has <em>released</em>.
 *
 * <p>This daemon only starts and watches it. Every decision about what the checkout should look
 * like — which tag a submodule sits at, what a dirty tree means, how often to re-read — belongs to
 * the CLI and is deliberately not duplicated here: a second opinion about the checkout is how the
 * two ends end up disagreeing with nothing to arbitrate them.
 *
 * <p>Framework-free (no CDI, no Vert.x, no Jackson — native-image lean) like the rest of this
 * daemon's capability code, so it reads no configuration: {@link ControlSocket} is the single
 * reader and hands everything down as a constructor argument.
 *
 * <h2>Why it is started after provisioning and after the API is wired</h2>
 *
 * It is started only when {@link Provisioner#provision} returned {@code true} — without a checkout
 * there is nothing to hold — and only <em>after</em> {@code wireCapabilities()}, so the loopback
 * API never waits on a subprocess. Starting it can never throw out of the boot worker: this
 * process is PID 1's child and nothing may take the container down.
 *
 * <h2>The child's environment, and why four names are not optional decoration</h2>
 *
 * The child inherits the daemon's environment, which already carries {@code
 * QITS_COMMISSIONED_CLIENT_ID}/{@code _SECRET} — that is how the CLI signs itself in. Four more
 * names are added, and each one <b>only when the inherited environment does not already carry a
 * non-blank value</b>: a container-wide injection is a deployment's decision and must win over an
 * opinion derived in here.
 *
 * <ul>
 *   <li>{@code GIT_CONFIG_GLOBAL=/etc/qits-gitconfig} — a base-image constant. Without it git never
 *       runs the credential helper that file names, and the child's first fetch dies with {@code
 *       could not read Username}.
 *   <li>{@code QITS_GIT_AUTH_HOST} — the <em>authority</em> of the git base ({@code host[:port]},
 *       no scheme and no path). The CLI's {@code Checkout.requireCredentialHelper} refuses
 *       in-platform with exit code 2 unless this names the origin's host, so handing it the whole
 *       base url would be the same as handing it nothing.
 *   <li>{@code QITS_GIT_AUTH_TOKEN_URL} and {@code QITS_GIT_AUTH_AUDIENCE} — verbatim, so the
 *       helper can mint a qits-githost token with the same client pair the daemon dials home with.
 * </ul>
 *
 * None of these is dead weight; removing one turns a working checkout into a fetch that cannot
 * authenticate, and the symptom surfaces a long way from the deletion.
 *
 * <h2>Supervision policy</h2>
 *
 * The command is built to stay up — a dirty tree, a lost stream, a failing fetch are all things it
 * logs and keeps watching through. <b>An exit is therefore an event, not routine</b>, and the
 * policy is written around which exits can be fixed by trying again:
 *
 * <ul>
 *   <li><b>exit 2</b> — the environment is wrong, and no retry can make it right. One {@link
 *       DaemonLog} {@code ERROR} carrying the child's last stderr line, then supervision stops. A
 *       loop here would bury the one sentence that says what to fix.
 *   <li><b>spawn failure</b> ({@code IOException} from {@link ProcessBuilder#start()}, e.g. no
 *       {@code qits} on {@code PATH}) — the same shape, and for the same reason: an image whose
 *       base is too old must state the fact once, not repeat it every second for the container's
 *       life.
 *   <li><b>exit 1</b> — the platform refused. Restart on a doubling backoff, and give up after
 *       {@code maxRuns} consecutive runs none of which stayed up longer than the reset window.
 *   <li><b>exit 0</b> — it should not have ended by itself, so it is treated exactly like exit 1.
 * </ul>
 *
 * A run that lasted longer than the reset window resets both the consecutive-failure counter and
 * the backoff: a child that ran for an hour and then died is a fresh incident, not the fifth in a
 * crash loop.
 *
 * <p>The four durations are constructor arguments rather than configuration keys. They are policy
 * this daemon owns, and the only caller that needs other values is a test driving the ladder in
 * milliseconds; an operator knob would be a way to make the give-up rule invisible.
 *
 * <h2>Output</h2>
 *
 * Both streams are pumped on their own daemon threads and every line becomes a {@link DaemonLog}
 * {@code INFO} prefixed {@code checkout-daemon: }. That is not bookkeeping — an undrained pipe
 * blocks the child as soon as the kernel buffer fills. The output deliberately does <b>not</b> ride
 * {@code CommandChunk}/{@link Provisioner#PROVISION_CORRELATION_ID}: that correlation is the
 * provisioning view's clone segment and ends with the provision, so a long-lived child appended to
 * it would keep re-opening a finished segment.
 */
public final class CheckoutFollower {

  /** The platform-access CLI, resolved on {@code PATH}. Overridden only by tests. */
  static final String DEFAULT_BINARY = "qits";

  /** Where the checkout the CLI holds lives in every agent container (image {@code WORKDIR}). */
  static final String CHECKOUT_PATH = "/workspace";

  /**
   * The base image's git configuration, naming the credential helper every fetch needs. A constant
   * rather than a key: it is a property of the image this daemon ships inside, and a container that
   * wants another one injects {@code GIT_CONFIG_GLOBAL} and wins outright.
   */
  static final String GIT_CONFIG_GLOBAL = "/etc/qits-gitconfig";

  /** The exit code the CLI uses for "this environment cannot work" — never retried. */
  static final int EXIT_ENVIRONMENT = 2;

  private static final String LINE_PREFIX = "checkout-daemon: ";

  private final boolean enabled;
  private final String binary;
  private final String gitBase;
  private final String authTokenUrl;
  private final String gitAuthAudience;
  private final long backoffInitialMs;
  private final long backoffMaxMs;
  private final long runResetMs;
  private final int maxRuns;
  private final long termGraceMs;
  private final Consumer<DaemonMessage> emit;

  /** Guards the spawn/stop transition, so a stop can never race a restart into a live child. */
  private final Object lock = new Object();

  private volatile boolean stopped;
  private volatile Process current;
  private volatile Thread supervisor;
  private volatile String lastStderrLine = "";

  /**
   * @param enabled the kill switch ({@code qits.projects-daemon.checkout-follow}); {@code false}
   *     spawns nothing and says so once
   * @param binary the CLI to run, {@link #DEFAULT_BINARY} in production
   * @param gitBase {@code qits.projects-daemon.git-base}; its <em>authority</em> becomes {@code
   *     QITS_GIT_AUTH_HOST}
   * @param authTokenUrl {@code qits.projects-daemon.auth-token-url}, passed verbatim
   * @param gitAuthAudience {@code qits.projects-daemon.git-auth-audience}, passed verbatim
   * @param backoffInitialMs the first restart delay, doubling per consecutive failure
   * @param backoffMaxMs the cap that doubling stops at
   * @param runResetMs how long a run has to last to count as "it was up", clearing the counter
   * @param maxRuns consecutive short runs after which supervision gives up
   * @param termGraceMs what {@link #stop()} gives the child between destroy and force-kill
   * @param emit where every {@link DaemonLog} goes — {@code ControlSocket::send} in production
   */
  public CheckoutFollower(
      boolean enabled,
      String binary,
      String gitBase,
      String authTokenUrl,
      String gitAuthAudience,
      long backoffInitialMs,
      long backoffMaxMs,
      long runResetMs,
      int maxRuns,
      long termGraceMs,
      Consumer<DaemonMessage> emit) {
    this.enabled = enabled;
    this.binary = binary == null || binary.isBlank() ? DEFAULT_BINARY : binary.trim();
    this.gitBase = gitBase == null ? "" : gitBase.trim();
    this.authTokenUrl = authTokenUrl == null ? "" : authTokenUrl.trim();
    this.gitAuthAudience = gitAuthAudience == null ? "" : gitAuthAudience.trim();
    this.backoffInitialMs = Math.max(1, backoffInitialMs);
    this.backoffMaxMs = Math.max(this.backoffInitialMs, backoffMaxMs);
    this.runResetMs = Math.max(0, runResetMs);
    this.maxRuns = Math.max(1, maxRuns);
    this.termGraceMs = Math.max(0, termGraceMs);
    this.emit = emit;
  }

  /**
   * Begin supervising, on a daemon thread of this class's own. Returns immediately, and never
   * throws: the caller is the boot worker, and a container must survive anything that happens here.
   */
  public void start() {
    if (!enabled) {
      emit.accept(
          new DaemonLog(
              "INFO",
              "checkout following is switched off (qits.projects-daemon.checkout-follow=false) —"
                  + " /workspace stays at whatever the boot clone left and will not follow what the"
                  + " repositories release."));
      return;
    }
    Thread thread = new Thread(this::supervise, "projects-daemon-checkout-follower");
    thread.setDaemon(true);
    supervisor = thread;
    thread.start();
  }

  /**
   * Stop supervising and end the child: no further restart, {@link Process#destroy()}, then {@link
   * Process#destroyForcibly()} once the term grace is up.
   *
   * <p>Safe when nothing was ever started, and bounded by the grace — it is called from {@code
   * ControlSocket}'s {@code @PreDestroy}, where a supervisor that blocked would hold the whole
   * shutdown.
   */
  public void stop() {
    Process process;
    synchronized (lock) {
      stopped = true;
      process = current;
    }
    Thread thread = supervisor;
    if (thread != null) {
      // Wakes a supervisor parked in waitFor() or in the backoff sleep; `stopped` is what makes it
      // return rather than restart.
      thread.interrupt();
    }
    if (process == null) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(termGraceMs, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  /** The exact command line, so a test can pin it rather than re-deriving it. */
  List<String> argv() {
    return List.of(binary, "checkout-daemon", "--path", CHECKOUT_PATH);
  }

  /** The live child, or {@code null} — package-private so a test can watch it die. */
  Process currentProcess() {
    return current;
  }

  /**
   * The names this daemon adds to the inherited environment, given what the container already
   * carries. Pure, and package-private, because "derived when absent, untouched when injected" is
   * the whole contract and a test must be able to drive both sides of it without a real process.
   *
   * <p>A name whose value is absent, blank, or underivable is simply not returned: setting {@code
   * QITS_GIT_AUTH_HOST} to something wrong is worse than leaving it unset, because the CLI's
   * refusal then names a host nobody configured.
   */
  static Map<String, String> additionalEnvironment(
      Map<String, String> inherited, String gitBase, String authTokenUrl, String gitAuthAudience) {
    Map<String, String> out = new LinkedHashMap<>();
    deriveIfAbsent(out, inherited, "GIT_CONFIG_GLOBAL", GIT_CONFIG_GLOBAL);
    deriveIfAbsent(out, inherited, "QITS_GIT_AUTH_HOST", authorityOf(gitBase));
    deriveIfAbsent(out, inherited, "QITS_GIT_AUTH_TOKEN_URL", authTokenUrl);
    deriveIfAbsent(out, inherited, "QITS_GIT_AUTH_AUDIENCE", gitAuthAudience);
    return out;
  }

  private static void deriveIfAbsent(
      Map<String, String> out, Map<String, String> inherited, String name, String derived) {
    if (derived == null || derived.isBlank()) {
      return;
    }
    String injected = inherited == null ? null : inherited.get(name);
    if (injected != null && !injected.isBlank()) {
      return;
    }
    out.put(name, derived);
  }

  /**
   * The authority of a git base — {@code http://dev-qits-githost:8080/git} → {@code
   * dev-qits-githost:8080}. Scheme, path and any userinfo are dropped; a default port is left
   * implicit, because that is the form the origin url's host component takes and the CLI compares
   * against it.
   *
   * <p>A base that does not parse yields {@code ""} and therefore no variable at all. The
   * alternative — some best-effort substring — would hand the CLI a host it can only refuse, and
   * the refusal would name a value nobody wrote down.
   */
  static String authorityOf(String gitBase) {
    if (gitBase == null || gitBase.isBlank()) {
      return "";
    }
    try {
      URI uri = new URI(gitBase.trim());
      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        return "";
      }
      return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
    } catch (java.net.URISyntaxException | IllegalArgumentException e) {
      return "";
    }
  }

  // ---- internals -----------------------------------------------------------

  private ProcessBuilder processBuilder() {
    ProcessBuilder builder = new ProcessBuilder(argv());
    // The default is to inherit, which is the point: QITS_COMMISSIONED_CLIENT_ID/_SECRET are how
    // the CLI signs in, and nothing here needs to know their values to pass them on.
    Map<String, String> environment = builder.environment();
    environment.putAll(
        additionalEnvironment(environment, gitBase, authTokenUrl, gitAuthAudience));
    return builder;
  }

  private void supervise() {
    long backoffMs = backoffInitialMs;
    int consecutiveFailures = 0;
    while (true) {
      Process process;
      synchronized (lock) {
        if (stopped) {
          return;
        }
        try {
          process = processBuilder().start();
        } catch (IOException e) {
          emit.accept(
              new DaemonLog(
                  "ERROR",
                  "checkout-daemon could not be started ("
                      + binary
                      + ": "
                      + e.getMessage()
                      + ") — /workspace will not follow what the repositories release. Not"
                      + " retried: a binary that is not in this image will not appear, and a retry"
                      + " loop would only repeat the fact."));
          return;
        }
        current = process;
      }
      lastStderrLine = "";
      long startedAtNanos = System.nanoTime();
      Thread out = pumpThread(process.getInputStream(), "stdout", false);
      Thread err = pumpThread(process.getErrorStream(), "stderr", true);
      out.start();
      err.start();
      int exit;
      try {
        exit = process.waitFor();
        // Joined so the exit-2 message below carries the stderr line the child actually died on,
        // rather than whatever the pump had got round to.
        out.join();
        err.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (stopped) {
        return;
      }
      if (exit == EXIT_ENVIRONMENT) {
        String reason = lastStderrLine.isBlank() ? "(no output)" : lastStderrLine;
        emit.accept(
            new DaemonLog(
                "ERROR",
                "checkout-daemon exited 2 — its environment is wrong and a retry cannot fix it, so"
                    + " supervision stops. Last output: "
                    + reason));
        return;
      }
      long ranMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
      if (ranMs > runResetMs) {
        // It was genuinely up. Whatever killed it now is a fresh incident, not the next rung of a
        // crash loop, so both the counter and the ladder start over.
        consecutiveFailures = 0;
        backoffMs = backoffInitialMs;
      }
      consecutiveFailures++;
      if (consecutiveFailures >= maxRuns) {
        emit.accept(
            new DaemonLog(
                "ERROR",
                "checkout-daemon exited "
                    + exit
                    + " on "
                    + consecutiveFailures
                    + " consecutive runs, none of which stayed up for "
                    + runResetMs
                    + "ms — giving up. /workspace no longer follows what the repositories"
                    + " release."));
        return;
      }
      try {
        Thread.sleep(backoffMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      backoffMs = Math.min(backoffMs * 2, backoffMaxMs);
    }
  }

  private Thread pumpThread(InputStream stream, String channel, boolean recordLast) {
    Thread thread =
        new Thread(
            () -> pump(stream, recordLast), "projects-daemon-checkout-follower-" + channel);
    thread.setDaemon(true);
    return thread;
  }

  /**
   * Relay one channel line by line. Line-oriented rather than chunked because each line becomes one
   * {@link DaemonLog}, and because the exit-2 message quotes the last stderr <em>line</em>.
   */
  private void pump(InputStream stream, boolean recordLast) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (recordLast && !line.isBlank()) {
          lastStderrLine = line;
        }
        emit.accept(new DaemonLog("INFO", LINE_PREFIX + line));
      }
    } catch (IOException e) {
      // The stream closed under us because the child died; the exit code carries the outcome.
    }
  }
}
