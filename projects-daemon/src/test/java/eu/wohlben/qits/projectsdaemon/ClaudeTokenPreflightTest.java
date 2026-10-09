package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.ProcessRunner;
import eu.wohlben.qits.projectsdaemon.ClaudeTokenPreflight.Outcome;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaudeTokenPreflight} over a faked credentials file, a faked {@link ProcessRunner} and a
 * fixed clock: the stale/missing/fresh decision, that {@code claude} runs only when the token is
 * stale, that a failed preflight is reported rather than thrown, and that no token value reaches a
 * log line.
 */
class ClaudeTokenPreflightTest {

  private static final String ACCESS = "sk-ant-oat01-SECRET-ACCESS";
  private static final String REFRESH = "sk-ant-ort01-SECRET-REFRESH";

  private final long now = Instant.parse("2026-10-09T06:10:00Z").toEpochMilli();

  private static String credentials(Long expiresAt) {
    return "{\"claudeAiOauth\":{\"accessToken\":\""
        + ACCESS
        + "\",\"refreshToken\":\""
        + REFRESH
        + "\""
        + (expiresAt == null ? "" : ",\"expiresAt\":" + expiresAt)
        + ",\"scopes\":[\"user:inference\"]}}";
  }

  // --- the decision ---------------------------------------------------------------------------

  @Test
  void staleMissingAndFreshExpiry() {
    long hour = Duration.ofHours(1).toMillis();
    long minute = Duration.ofMinutes(1).toMillis();
    assertFalse(ClaudeTokenPreflight.needsRefresh(Optional.of(credentials(now + hour)), now));
    assertFalse(
        ClaudeTokenPreflight.needsRefresh(Optional.of(credentials(now + 5 * minute + 1)), now));
    assertTrue(
        ClaudeTokenPreflight.needsRefresh(Optional.of(credentials(now + 5 * minute)), now),
        "within five minutes counts as stale");
    assertTrue(ClaudeTokenPreflight.needsRefresh(Optional.of(credentials(now - hour)), now));
    assertTrue(ClaudeTokenPreflight.needsRefresh(Optional.of(credentials(null)), now));
    assertTrue(ClaudeTokenPreflight.needsRefresh(Optional.empty(), now), "no file");
    assertTrue(ClaudeTokenPreflight.needsRefresh(Optional.of("not json"), now));
    assertTrue(ClaudeTokenPreflight.needsRefresh(Optional.of("{}"), now), "no claudeAiOauth");
    assertTrue(
        ClaudeTokenPreflight.needsRefresh(
            Optional.of("{\"claudeAiOauth\":{\"expiresAt\":\"soon\"}}"), now));
  }

  @Test
  void credentialsFileFollowsClaudeConfigDirElseTheMount() {
    assertEquals(
        Path.of("/claude-home/.claude/.credentials.json"),
        ClaudeTokenPreflight.credentialsFile("/claude-home", null));
    assertEquals(
        Path.of("/elsewhere/.credentials.json"),
        ClaudeTokenPreflight.credentialsFile("/claude-home", "/elsewhere"));
  }

  // --- the preflight --------------------------------------------------------------------------

  private final List<List<String>> runs = new ArrayList<>();
  private final List<Map<String, String>> envs = new ArrayList<>();
  private final List<String> logLines = new ArrayList<>();
  private final List<Logger.Level> logLevels = new ArrayList<>();

  /** The file's content before the preflight, and what {@code claude} leaves behind. */
  private String before;
  private String after;
  private ProcessRunner.Result result =
      new ProcessRunner.Result(0, "ok " + ACCESS, "refreshed " + REFRESH, false);
  private RuntimeException runFails;
  private boolean ran;

  private ClaudeTokenPreflight preflight() {
    ProcessRunner runner =
        (command, cwd, env, timeout) -> {
          runs.add(command);
          envs.add(env);
          assertEquals(ClaudeTokenPreflight.TIMEOUT, timeout);
          if (runFails != null) {
            throw runFails;
          }
          ran = true;
          return result;
        };
    return new ClaudeTokenPreflight(
        () -> Optional.ofNullable(ran ? after : before),
        runner,
        Path.of("/tmp"),
        Map.of("HOME", "/claude-home"),
        () -> now,
        (level, line) -> {
          logLevels.add(level);
          logLines.add(line);
        });
  }

  private void assertNoSecretLogged() {
    for (String line : logLines) {
      assertFalse(line.contains(ACCESS), line);
      assertFalse(line.contains(REFRESH), line);
      assertFalse(line.contains("SECRET"), line);
    }
  }

  @Test
  void aFreshTokenRunsNothing() {
    before = credentials(now + Duration.ofHours(3).toMillis());
    assertEquals(Outcome.FRESH, preflight().ensureFresh());
    assertTrue(runs.isEmpty());
    assertTrue(logLines.isEmpty());
  }

  @Test
  void aStaleTokenIsRefreshedThroughClaudeWithTheLaunchHome() {
    before = credentials(now - Duration.ofMinutes(37).toMillis());
    long refreshed = now + Duration.ofHours(8).toMillis();
    after = credentials(refreshed);

    assertEquals(Outcome.REFRESHED, preflight().ensureFresh());

    assertEquals(List.of(ClaudeTokenPreflight.COMMAND), runs);
    assertEquals("/claude-home", envs.getFirst().get("HOME"));
    assertEquals(List.of(Logger.Level.INFO), logLevels);
    assertEquals(
        "front desk: refreshed the claude.ai token before launch (valid until "
            + Instant.ofEpochMilli(refreshed)
            + ")",
        logLines.getFirst());
    assertNoSecretLogged();
  }

  @Test
  void aMissingFileRunsThePreflight() {
    before = null;
    after = credentials(now + Duration.ofHours(8).toMillis());
    assertEquals(Outcome.REFRESHED, preflight().ensureFresh());
    assertEquals(1, runs.size());
  }

  @Test
  void aTokenStillStaleAfterThePreflightWarnsWithoutSecrets() {
    before = credentials(now - 1);
    after = before;
    result = new ProcessRunner.Result(1, ACCESS, "error: " + REFRESH, false);

    assertEquals(Outcome.FAILED, preflight().ensureFresh());

    assertEquals(List.of(Logger.Level.WARN), logLevels);
    assertTrue(logLines.getFirst().contains("exit 1"), logLines.getFirst());
    assertNoSecretLogged();
  }

  @Test
  void aTimedOutOrUnrunnablePreflightWarnsAndNeverThrows() {
    before = credentials(null);
    after = before;
    result = new ProcessRunner.Result(-1, "", "", true);
    assertEquals(Outcome.FAILED, preflight().ensureFresh());
    assertTrue(logLines.getLast().contains("timed out after 90 s"), logLines.getLast());

    runFails = new IllegalStateException("no claude on PATH " + ACCESS);
    assertEquals(Outcome.FAILED, preflight().ensureFresh());
    assertEquals(Logger.Level.WARN, logLevels.getLast());
    assertNoSecretLogged();
  }
}
