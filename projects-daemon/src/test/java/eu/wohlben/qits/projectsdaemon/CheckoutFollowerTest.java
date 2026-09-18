package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Container-free coverage of the {@code qits checkout-daemon} supervisor. Real processes, because
 * the thing under test <em>is</em> the process handling: a fake executable in a {@link TempDir}
 * stands in for the CLI, so the argv, the injected environment, every exit code and the stop path
 * are exercised end-to-end without a {@code qits} binary, docker, or the platform.
 *
 * <p>The ladder is driven in milliseconds. Nothing here sleeps for a fixed second where a poll on
 * the fake's own output will do.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CheckoutFollowerTest {

  private static final String GIT_BASE = "http://dev-qits-githost:8080/git";
  private static final String TOKEN_URL = "http://qits-idp:8080/realms/qits/token";
  private static final String GIT_AUDIENCE = "qits-githost-env";

  @TempDir Path tempDir;

  private final CopyOnWriteArrayList<DaemonMessage> events = new CopyOnWriteArrayList<>();
  private CheckoutFollower follower;
  private int scripts;

  @AfterEach
  void cleanup() {
    if (follower != null) {
      follower.stop();
    }
  }

  // ---- the command line ----------------------------------------------------

  @Test
  void theArgvIsTheCheckoutDaemonAndNothingElse() {
    assertEquals(
        List.of("qits", "checkout-daemon", "--path", "/workspace"),
        follower(CheckoutFollower.DEFAULT_BINARY).argv(),
        "the CLI owns every decision about the checkout; this daemon only names the mode and the"
            + " path");
  }

  // ---- the four environment names -----------------------------------------

  @Test
  void theFourNamesAreDerivedWhenTheContainerCarriesNone() {
    Map<String, String> derived =
        CheckoutFollower.additionalEnvironment(Map.of(), GIT_BASE, TOKEN_URL, GIT_AUDIENCE);

    assertEquals("/etc/qits-gitconfig", derived.get("GIT_CONFIG_GLOBAL"));
    assertEquals("dev-qits-githost:8080", derived.get("QITS_GIT_AUTH_HOST"));
    assertEquals(TOKEN_URL, derived.get("QITS_GIT_AUTH_TOKEN_URL"));
    assertEquals(GIT_AUDIENCE, derived.get("QITS_GIT_AUTH_AUDIENCE"));
    assertEquals(4, derived.size(), "and nothing else — the rest of the environment is inherited");
  }

  /** A container-wide injection is a deployment's decision; a locally derived opinion is not. */
  @Test
  void anInjectedValueWinsOverTheDerivedOne() {
    Map<String, String> injected =
        Map.of(
            "GIT_CONFIG_GLOBAL", "/etc/other-gitconfig",
            "QITS_GIT_AUTH_HOST", "other-githost:9090",
            "QITS_GIT_AUTH_TOKEN_URL", "http://other/token",
            "QITS_GIT_AUTH_AUDIENCE", "other-audience");

    assertTrue(
        CheckoutFollower.additionalEnvironment(injected, GIT_BASE, TOKEN_URL, GIT_AUDIENCE)
            .isEmpty(),
        "nothing is overwritten, so the container's own answer is the one the child sees");
  }

  @Test
  void aBlankInjectedValueIsNotAnAnswerAndIsDerivedOver() {
    Map<String, String> derived =
        CheckoutFollower.additionalEnvironment(
            Map.of("QITS_GIT_AUTH_HOST", "  "), GIT_BASE, TOKEN_URL, GIT_AUDIENCE);

    assertEquals("dev-qits-githost:8080", derived.get("QITS_GIT_AUTH_HOST"));
  }

  @Test
  void theTwoVerbatimNamesAreOmittedWhenTheConfigIsAbsent() {
    Map<String, String> derived =
        CheckoutFollower.additionalEnvironment(Map.of(), GIT_BASE, "", "   ");

    assertFalse(derived.containsKey("QITS_GIT_AUTH_TOKEN_URL"));
    assertFalse(derived.containsKey("QITS_GIT_AUTH_AUDIENCE"));
    assertEquals("dev-qits-githost:8080", derived.get("QITS_GIT_AUTH_HOST"));
  }

  /**
   * The CLI's {@code Checkout.requireCredentialHelper} compares this against the <em>origin's
   * host</em>, so the whole base url would be as useless as an unset variable — and it would fail
   * with exit 2, a code this supervisor never retries.
   */
  @Test
  void theGitAuthHostIsTheAuthorityOfTheGitBaseNotTheBase() {
    assertEquals("dev-qits-githost:8080", CheckoutFollower.authorityOf(GIT_BASE));
    assertEquals("qits-githost", CheckoutFollower.authorityOf("http://qits-githost/git"));
    assertEquals(
        "qits-githost:8080",
        CheckoutFollower.authorityOf("https://user:secret@qits-githost:8080/git"),
        "userinfo is dropped — it would never match an origin's host and could leak a secret");
    assertEquals(
        "", CheckoutFollower.authorityOf("not a url"), "unparseable leaves the name unset");
    assertEquals(
        "",
        CheckoutFollower.authorityOf("dev-qits-githost:8080/git"),
        "no scheme, no authority — better unset than a host nobody configured");
    assertEquals("", CheckoutFollower.authorityOf(""));
    assertEquals("", CheckoutFollower.authorityOf(null));
  }

  /**
   * The same contract, through a real spawn: all four names reach the child, each carrying the
   * value the container injected if there was one and the derived value otherwise.
   *
   * <p>The expectation is computed from {@link System#getenv()} rather than written down, because
   * the JVM running this suite may itself be inside a qits container that injects some of these —
   * and that case is precisely the one the "injected wins" rule is about, not an obstacle to
   * asserting it.
   */
  @Test
  void theDerivedEnvironmentReachesTheChild() throws Exception {
    Path dump = tempDir.resolve("child-env.out");
    // exit 2 so the child runs exactly once and supervision settles immediately.
    start(
        script(
            "for n in GIT_CONFIG_GLOBAL QITS_GIT_AUTH_HOST QITS_GIT_AUTH_TOKEN_URL"
                + " QITS_GIT_AUTH_AUDIENCE; do",
            "  eval \"printf '%s=%s\\n' \\\"$n\\\" \\\"\\${$n-}\\\"\" >> '" + dump + "'",
            "done",
            "exit 2"));

    awaitCondition(() -> errors().size() == 1, 20_000, () -> "the exit-2 refusal");
    assertEquals(
        List.of(
            "GIT_CONFIG_GLOBAL=" + expected("GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig"),
            "QITS_GIT_AUTH_HOST=" + expected("QITS_GIT_AUTH_HOST", "dev-qits-githost:8080"),
            "QITS_GIT_AUTH_TOKEN_URL=" + expected("QITS_GIT_AUTH_TOKEN_URL", TOKEN_URL),
            "QITS_GIT_AUTH_AUDIENCE=" + expected("QITS_GIT_AUTH_AUDIENCE", GIT_AUDIENCE)),
        Files.readAllLines(dump));
  }

  /** What the child must see for {@code name}: the injected value, else the derived one. */
  private static String expected(String name, String derived) {
    String injected = System.getenv(name);
    return injected != null && !injected.isBlank() ? injected : derived;
  }

  // ---- supervision policy --------------------------------------------------

  @Test
  void exitTwoGivesUpAfterOneAttemptAndCarriesTheLastStderrLine() throws Exception {
    Path runs = tempDir.resolve("runs.out");
    start(
        script(
            "echo run >> '" + runs + "'",
            "echo 'ignore this earlier line' 1>&2",
            "echo 'QITS_GIT_AUTH_HOST does not name the origin host' 1>&2",
            "exit 2"));

    awaitCondition(() -> errors().size() == 1, 20_000, () -> "one ERROR; saw " + errors());
    assertEquals(
        1, Files.readAllLines(runs).size(), "an environment a retry cannot fix is not retried");
    String error = errors().get(0).message();
    assertTrue(error.contains("exited 2"), error);
    assertTrue(
        error.contains("QITS_GIT_AUTH_HOST does not name the origin host"),
        "the child's last stderr line is what says which name is wrong: " + error);
    assertSettled(runs, 1);
  }

  @Test
  void aBinaryThatIsNotThereIsStatedOnceAndNotRetried() {
    start(tempDir.resolve("no-such-qits").toString());

    awaitCondition(() -> errors().size() == 1, 20_000, () -> "one ERROR; saw " + errors());
    assertTrue(
        errors().get(0).message().contains("could not be started"), errors().get(0).message());
    sleep(400);
    assertEquals(1, errors().size(), "an image whose base is too old states the fact once");
  }

  @Test
  void exitOneRestartsOnBackoffAndGivesUpAtTheConfiguredMax() throws Exception {
    Path runs = tempDir.resolve("runs.out");
    start(script("echo run >> '" + runs + "'", "echo refused 1>&2", "exit 1"));

    awaitCondition(() -> errors().size() == 1, 30_000, () -> "the give-up ERROR; saw " + errors());
    assertEquals(3, Files.readAllLines(runs).size(), "maxRuns consecutive short runs, then stop");
    assertTrue(errors().get(0).message().contains("giving up"), errors().get(0).message());
    assertSettled(runs, 3);
  }

  /**
   * A run that stayed up is a fresh incident, not the next rung of a crash loop. With the reset the
   * ladder is short-run, long-run, short, short — four runs before the third consecutive failure;
   * without it the third run would already be the last.
   */
  @Test
  void aRunLongerThanTheResetWindowClearsTheCounter() throws Exception {
    Path runs = tempDir.resolve("runs.out");
    start(
        script(
            "echo run >> '" + runs + "'",
            "if [ \"$(wc -l < '" + runs + "')\" -eq 2 ]; then sleep 1; fi",
            "exit 1"));

    awaitCondition(() -> errors().size() == 1, 30_000, () -> "the give-up ERROR; saw " + errors());
    assertEquals(
        4,
        Files.readAllLines(runs).size(),
        "the second run outlived the reset window, so the two runs before it stopped counting");
    assertSettled(runs, 4);
  }

  @Test
  void exitZeroIsAlsoARestartBecauseItShouldNotHaveEnded() throws Exception {
    Path runs = tempDir.resolve("runs.out");
    start(script("echo run >> '" + runs + "'", "exit 0"));

    awaitCondition(() -> errors().size() == 1, 30_000, () -> "the give-up ERROR; saw " + errors());
    assertEquals(3, Files.readAllLines(runs).size());
  }

  // ---- output --------------------------------------------------------------

  @Test
  void everyChildLineBecomesADaemonLog() throws Exception {
    start(
        script(
            "echo 'held /workspace at 2026.918.1'",
            "echo 'submodule drift detected' 1>&2",
            "exit 2"));

    awaitCondition(() -> errors().size() == 1, 20_000, () -> "the exit-2 refusal");
    List<String> lines =
        events.stream()
            .filter(m -> m instanceof DaemonLog log && "INFO".equals(log.level()))
            .map(m -> ((DaemonLog) m).message())
            .collect(Collectors.toList());
    assertTrue(
        lines.contains("checkout-daemon: held /workspace at 2026.918.1"),
        "stdout is relayed, prefixed so it is identifiable in the service log: " + lines);
    assertTrue(
        lines.contains("checkout-daemon: submodule drift detected"),
        "and so is stderr — an undrained pipe blocks the child: " + lines);
  }

  // ---- stopping ------------------------------------------------------------

  @Test
  void stoppingTerminatesTheChildAndNothingRestarts() throws Exception {
    Path runs = tempDir.resolve("runs.out");
    start(script("echo run >> '" + runs + "'", "exec sleep 300"));

    awaitCondition(() -> lineCount(runs) == 1, 20_000, () -> "the child to announce itself");
    Process child = follower.currentProcess();
    assertNotNull(child);

    follower.stop();

    assertFalse(child.isAlive(), "the term grace expired into destroyForcibly at the latest");
    sleep(400);
    assertEquals(1, lineCount(runs), "stop means stop supervising, not stop this one child");
    assertTrue(errors().isEmpty(), "a requested stop is not a failure");
  }

  @Test
  void stoppingIsSafeWhenNothingWasEverStarted() {
    follower = follower(CheckoutFollower.DEFAULT_BINARY);

    follower.stop();
    follower.stop();

    assertTrue(events.isEmpty());
  }

  // ---- the kill switch -----------------------------------------------------

  @Test
  void theKillSwitchSpawnsNothingAndSaysSo() throws Exception {
    Path runs = tempDir.resolve("runs.out");
    String fake = script("echo run >> '" + runs + "'", "exec sleep 300");
    follower =
        new CheckoutFollower(
            false, fake, GIT_BASE, TOKEN_URL, GIT_AUDIENCE, 10, 20, 300, 3, 500, events::add);

    follower.start();

    sleep(400);
    assertFalse(Files.exists(runs), "nothing was spawned");
    assertNull(follower.currentProcess());
    assertEquals(1, events.size(), "exactly one line, so 'off' is legible rather than inferred");
    assertTrue(
        events.get(0) instanceof DaemonLog log
            && "INFO".equals(log.level())
            && log.message().contains("qits.projects-daemon.checkout-follow=false"),
        "the line names the key that switched it off: " + events);
  }

  // ---- where the decision to follow at all is made -------------------------

  /**
   * A container whose clone failed has no checkout to hold, and qits leaves such a container
   * running. A follower there would fail its first fetch against a directory that is not a checkout
   * once a second for the life of the container.
   */
  @Test
  void noFollowerStartsWhenTheProvisionFailed() {
    ControlSocket daemon = controlSocket();

    daemon.provisioned = false;
    daemon.followCheckout();

    assertNull(daemon.checkoutFollower, "nothing to hold, so nothing is supervised");

    daemon.provisioned = true;
    daemon.followCheckout();

    assertNotNull(
        daemon.checkoutFollower, "and with a checkout it is wired — the guard is the provision");
    daemon.stop();
  }

  /** The kill switch is off here, so this wires a follower without spawning anything. */
  private ControlSocket controlSocket() {
    ControlSocket daemon = new ControlSocket();
    daemon.checkoutFollow = false;
    daemon.gitBaseConfig = Optional.of(GIT_BASE);
    daemon.authTokenUrl = Optional.of(TOKEN_URL);
    daemon.gitAuthAudience = Optional.of(GIT_AUDIENCE);
    daemon.termGraceMs = 1_000;
    return daemon;
  }

  // ---- fixtures ------------------------------------------------------------

  /** The ladder in milliseconds: three runs, a 300 ms reset window, a 10→20 ms backoff. */
  private CheckoutFollower follower(String binary) {
    return new CheckoutFollower(
        true, binary, GIT_BASE, TOKEN_URL, GIT_AUDIENCE, 10, 20, 300, 3, 500, events::add);
  }

  private void start(String binary) {
    follower = follower(binary);
    follower.start();
  }

  /** A fake {@code qits} in the temp dir: a real executable, driven by real exit codes. */
  private String script(String... body) throws IOException {
    Path path = tempDir.resolve("qits-fake-" + (scripts++));
    Files.writeString(path, "#!/bin/sh\n" + String.join("\n", body) + "\n");
    assertTrue(path.toFile().setExecutable(true), "the fake has to be runnable");
    return path.toString();
  }

  private List<DaemonLog> errors() {
    return events.stream()
        .filter(m -> m instanceof DaemonLog log && "ERROR".equals(log.level()))
        .map(m -> (DaemonLog) m)
        .collect(Collectors.toList());
  }

  private static int lineCount(Path path) {
    try {
      return Files.exists(path) ? Files.readAllLines(path).size() : 0;
    } catch (IOException e) {
      return -1;
    }
  }

  /** Once it has given up, it stays given up: no further run and no second ERROR. */
  private void assertSettled(Path runs, int expectedRuns) {
    sleep(400);
    assertEquals(expectedRuns, lineCount(runs), "supervision stopped for good");
    assertEquals(1, errors().size(), "and said so exactly once");
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted");
    }
  }

  private static void awaitCondition(
      BooleanSupplier condition, long timeoutMs, java.util.function.Supplier<String> what) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep(20);
    }
    fail("timed out waiting for " + what.get());
  }
}
