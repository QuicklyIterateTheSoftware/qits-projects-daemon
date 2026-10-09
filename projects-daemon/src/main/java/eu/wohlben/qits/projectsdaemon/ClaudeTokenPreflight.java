package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.ProcessRunner;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Makes sure the claude.ai OAuth access token on the shared credential volume is fresh before the
 * {@link FrontDeskKeeper} launches the desk (qits-1102).
 *
 * <p>Why: {@code claude --remote-control} connects Remote Control at startup with whatever access
 * token {@code .credentials.json} holds, and does not retry. The model path refreshes an expired
 * token on its first API call, Remote Control does not — so a desk launched on a stale token comes
 * up as "Remote Control failed · /login" and stays that way. The library's own sign-in probe,
 * {@code claude auth status}, runs before every launch and evidently does not refresh: both launches
 * in the incident passed it and still started on the expired token.
 *
 * <p>The refresh itself is left to the official client — the refresh token rotates and the file is
 * shared by every container on the estate, so a hand-rolled refresh racing a real {@code claude}
 * would strand one of them. The preflight is the cheapest call that makes {@code claude} talk to the
 * API and so refresh and persist the token: {@link #COMMAND}, run with the launch's {@code HOME}
 * (and the inherited {@code CLAUDE_CONFIG_DIR}) and its output discarded. {@code --bare} would be
 * cheaper still but never reads OAuth credentials, so it would refresh nothing.
 *
 * <p>No token value is ever logged: only the expiry instant and the preflight's exit code.
 */
final class ClaudeTokenPreflight {

  private static final Logger LOG = Logger.getLogger(ClaudeTokenPreflight.class);

  /** A token within this margin of expiry counts as stale. */
  static final long MARGIN_MS = Duration.ofMinutes(5).toMillis();

  static final Duration TIMEOUT = Duration.ofSeconds(90);

  /**
   * One haiku turn, no tools, no MCP servers, nothing written to the transcript store: the least
   * that still sends a model request, which is what makes the client refresh its token.
   */
  static final List<String> COMMAND =
      List.of(
          "claude",
          "-p",
          "--model",
          "haiku",
          "--max-turns",
          "1",
          "--tools",
          "",
          "--strict-mcp-config",
          "--no-session-persistence",
          "ok");

  /** What one {@link #ensureFresh()} did, for the caller and the tests. */
  enum Outcome {
    /** The token was fresh; nothing ran. */
    FRESH,
    /** The preflight ran and the token is fresh now. */
    REFRESHED,
    /** The preflight ran (or could not) and the token is still not fresh. */
    FAILED
  }

  private final Supplier<Optional<String>> credentials;
  private final ProcessRunner processes;
  private final Path cwd;
  private final Map<String, String> env;
  private final LongSupplier clock;
  private final BiConsumer<Logger.Level, String> log;

  ClaudeTokenPreflight(
      Supplier<Optional<String>> credentials,
      ProcessRunner processes,
      Path cwd,
      Map<String, String> env,
      LongSupplier clock,
      BiConsumer<Logger.Level, String> log) {
    this.credentials = credentials;
    this.processes = processes;
    this.cwd = cwd;
    this.env = env;
    this.clock = clock;
    this.log = log;
  }

  /**
   * The production preflight: the credentials file under {@code $CLAUDE_CONFIG_DIR} when the daemon
   * (and so every launch, which inherits its environment) carries one, else {@code
   * <claudeMount>/.claude}; the command runs with {@code HOME=<claudeMount>}, exactly the overlay
   * {@code AgentLaunchService} puts on a Claude launch.
   */
  static ClaudeTokenPreflight of(
      ProcessRunner processes, String claudeMount, String claudeConfigDir, Path cwd) {
    Path file = credentialsFile(claudeMount, claudeConfigDir);
    Map<String, String> env = new HashMap<>();
    if (claudeMount != null && !claudeMount.isBlank()) {
      env.put("HOME", claudeMount);
    }
    return new ClaudeTokenPreflight(
        () -> read(file), processes, cwd, Map.copyOf(env), System::currentTimeMillis, LOG::log);
  }

  /** {@code $CLAUDE_CONFIG_DIR/.credentials.json}, else {@code <claudeMount>/.claude/…}. */
  static Path credentialsFile(String claudeMount, String claudeConfigDir) {
    if (claudeConfigDir != null && !claudeConfigDir.isBlank()) {
      return Path.of(claudeConfigDir, ".credentials.json");
    }
    String home =
        claudeMount != null && !claudeMount.isBlank()
            ? claudeMount
            : System.getProperty("user.home");
    return Path.of(home, ".claude", ".credentials.json");
  }

  private static Optional<String> read(Path file) {
    try {
      return Optional.of(Files.readString(file));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** {@code claudeAiOauth.expiresAt} (epoch ms), or empty when absent or unreadable. */
  static OptionalLong expiresAt(Optional<String> content) {
    if (content == null || content.isEmpty()) {
      return OptionalLong.empty();
    }
    try {
      JsonObject oauth = new JsonObject(content.get()).getJsonObject("claudeAiOauth");
      if (oauth == null || !(oauth.getValue("expiresAt") instanceof Number expiry)) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(expiry.longValue());
    } catch (RuntimeException e) {
      return OptionalLong.empty();
    }
  }

  /** Whether a launch must refresh first: expiry missing, unreadable, past, or within 5 min. */
  static boolean needsRefresh(Optional<String> content, long nowMs) {
    OptionalLong expiry = expiresAt(content);
    return expiry.isEmpty() || expiry.getAsLong() - nowMs <= MARGIN_MS;
  }

  /** Refresh the token through {@code claude} when it is stale. Never throws. */
  Outcome ensureFresh() {
    try {
      if (!needsRefresh(credentials.get(), clock.getAsLong())) {
        return Outcome.FRESH;
      }
      String detail;
      try {
        ProcessRunner.Result result = processes.exec(COMMAND, cwd, env, TIMEOUT);
        detail =
            result.timedOut()
                ? "timed out after " + TIMEOUT.toSeconds() + " s"
                : "exit " + result.exitCode();
      } catch (RuntimeException e) {
        detail = "did not run: " + e.getClass().getSimpleName();
      }
      Optional<String> after = credentials.get();
      if (!needsRefresh(after, clock.getAsLong())) {
        log.accept(
            Logger.Level.INFO,
            "front desk: refreshed the claude.ai token before launch (valid until "
                + Instant.ofEpochMilli(expiresAt(after).getAsLong())
                + ")");
        return Outcome.REFRESHED;
      }
      log.accept(
          Logger.Level.WARN,
          "front desk: the claude.ai token is still stale after the refresh preflight ("
              + detail
              + "); launching anyway — Remote Control may need /remote-control once the model"
              + " has refreshed it");
      return Outcome.FAILED;
    } catch (RuntimeException e) {
      log.accept(
          Logger.Level.WARN,
          "front desk: the claude.ai token preflight failed ("
              + e.getClass().getSimpleName()
              + "); launching anyway");
      return Outcome.FAILED;
    }
  }
}
