package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.protocol.CommandChunk;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.projectsdaemon.protocol.Provisioned;
import eu.wohlben.qits.projectsdaemon.protocol.Stream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The <b>autonomous self-clone</b>: on boot the daemon clones the project's <b>wrapper
 * repository</b> into {@code /workspace} and materializes its submodules — entirely from its
 * injected env, with no instruction from qits — then emits the terminal {@link Provisioned} (with
 * the checked-out {@code HEAD}) or {@link ProvisionFailed}. qits only awaits that event.
 *
 * <p>Framework-free (no Vert.x, no CDI, no JGit — native-image lean), so it forks the {@code git}
 * CLI via {@link ProcessBuilder} and unit-tests directly against a collecting {@code Consumer}.
 *
 * <p><b>The clone is project-scoped</b>: {@code <gitBase>/<projectId>/<repoName>}, qits-githost's
 * one public repository address. That is the whole point of cloning a wrapper — its committed
 * submodule urls are relative, so they resolve natively against the sibling names below the same
 * project segment. The flat {@code <gitBase>/<repoName>} this used to build addressed a repository
 * only while a storage id was its name, which collides globally the moment a second project holds a
 * repository of the same name.
 *
 * <p>Submodules are discovered from the checkout's own {@code .gitmodules} in a bounded,
 * depth-capped walk (the daemon has no database). A submodule that cannot be fetched is skipped
 * with a warning rather than failing the whole provision — a wrapper naming a repository the
 * project never imported must not cost the agent its container. An <b>absolute</b> committed url is
 * redirected to the sibling below this project's segment by basename, so a wrapper that pins full
 * urls still resolves offline.
 *
 * <p>An existing checkout (a reconnect after a restart) is never re-cloned: it may hold unpushed
 * commits. The submodule walk still re-runs, because a prior boot may have died between the root
 * clone and materialization, and {@code submodule update --init} is a no-op on submodules already
 * present.
 *
 * <p><b>The git base must be injected.</b> {@code qits.projects-daemon.git-base} names the git host
 * outright ({@code QITS_PROJECTS_DAEMON_GIT_BASE}), and qits-projects sets it on every container it
 * creates. Unset used to derive the dial-home authority plus {@code /artifacts/git}, with a {@code
 * WARN}: a guess about a <em>different</em> service, and since the byte-plane split about a host
 * that serves no git at all. That bought nothing over naming the missing key, so it is gone — unset
 * now emits the {@link DaemonLog} {@code WARN} and refuses to clone. Contrast {@link
 * DaemonMcpEndpoints}, whose derivation stays because both ends really are qits-projects.
 *
 * <p>Whatever prefix the base carries does <b>not</b> disturb relative submodule resolution. Git
 * treats the superproject's remote as a <em>directory</em> and {@code ../} replaces the
 * repository-name segment with its sibling. So {@code ../sibling} against {@code
 * …/git/<projectId>/<repoName>} yields {@code …/git/<projectId>/sibling}: the same project's
 * sibling.
 */
public final class Provisioner {

  /** The correlation id all provision output ({@link CommandChunk}) is tagged with. */
  static final String PROVISION_CORRELATION_ID = DaemonProtocol.PROVISION_CORRELATION_ID;

  /** Where the wrapper checkout lives in every agent container (image {@code WORKDIR}). */
  private static final File WORKSPACE_DIR = new File("/workspace");

  /** The cycle backstop for the bounded submodule walk. */
  private static final int MAX_SUBMODULE_DEPTH = 10;

  private static final int BUFFER_SIZE = 4096;

  /**
   * The identity and coordinates the daemon self-provisions from (its injected env).
   *
   * <p>{@code gitBaseUrl} is the git base ({@code qits.projects-daemon.git-base}, e.g. {@code
   * http://qits-githost:8080/git}). Blank means the daemon cannot clone — there is no derivation
   * left to fall back to (see the class javadoc).
   */
  public record Env(
      String projectId, String repoName, String gitBaseUrl, String gitAuthorization) {}

  private Provisioner() {}

  /**
   * Clone and submodule-materialize {@code /workspace} from {@code env}, emitting streamed output
   * and exactly one terminal {@link Provisioned}/{@link ProvisionFailed}. Never throws — any error
   * is reported as {@link ProvisionFailed}, keeping the daemon's "never exit on failure" invariant.
   * Returns {@code true} when it emitted {@link Provisioned} (a usable checkout exists), so the
   * caller knows whether the daemon's next startup steps have a checkout to run against.
   */
  public static boolean provision(Env env, Consumer<DaemonMessage> emit) {
    try {
      if (!nameAddressed(env)) {
        emit.accept(
            new ProvisionFailed(
                env.projectId(),
                "no clone url: QITS_PROJECTS_DAEMON_REPO_NAME is required, because the wrapper is"
                    + " cloned by its qits-githost repository name"));
        return false;
      }
      String gitBase = gitBase(env, emit);
      if (gitBase == null) {
        emit.accept(
            new ProvisionFailed(
                env.projectId(),
                "no git host: qits.projects-daemon.git-base (QITS_PROJECTS_DAEMON_GIT_BASE) is"
                    + " unset"));
        return false;
      }
      if (new File(WORKSPACE_DIR, ".git").exists()) {
        emit.accept(
            new DaemonLog(
                "INFO",
                "/workspace already checked out — skipping root clone, re-checking submodules."));
        materializeSubmodules(gitBase, env, ".", 0, emit);
        emit.accept(new Provisioned(env.projectId(), head()));
        return true;
      }
      String rootUrl = rootUrl(gitBase, env);
      emit.accept(new DaemonLog("INFO", "self-cloning " + rootUrl + " into /workspace"));
      int cloneExit =
          runStreaming(
              List.of("git", "clone", rootUrl, WORKSPACE_DIR.getPath()),
              gitEnvironment(env),
              emit);
      if (cloneExit != 0) {
        emit.accept(
            new ProvisionFailed(
                env.projectId(), "git clone exited " + cloneExit + " (" + rootUrl + ")"));
        return false;
      }
        materializeSubmodules(gitBase, env, ".", 0, emit);
      emit.accept(new Provisioned(env.projectId(), head()));
      return true;
    } catch (RuntimeException e) {
      emit.accept(new ProvisionFailed(env.projectId(), "self-provision error: " + e.getMessage()));
      return false;
    }
  }

  /**
   * The git base every clone url is built on: the injected {@code qits.projects-daemon.git-base}, or
   * {@code null} when none was supplied — announced as a {@code WARN} and reported by the caller as
   * {@link ProvisionFailed}.
   *
   * <p>The fallback that used to sit here derived the dial-home authority plus {@code
   * /artifacts/git}. qits-projects states the base on every container it creates, so it was already
   * dead in a deployed daemon; and the host it guessed at stopped serving git at the byte-plane
   * split, so where it did fire it only turned a missing setting into a connection error against
   * the wrong service.
   */
  static String gitBase(Env env, Consumer<DaemonMessage> emit) {
    String configured = env.gitBaseUrl();
    if (configured != null && !configured.isBlank()) {
      return trimTrailingSlash(configured.trim());
    }
    emit.accept(
        new DaemonLog(
            "WARN",
            "No qits.projects-daemon.git-base injected — refusing to self-clone. The git host is"
                + " qits-githost and its address is not derivable from the control socket's"
                + " (qits-projects'); whoever creates this container has to state it."));
    return null;
  }

  private static String trimTrailingSlash(String base) {
    String out = base;
    while (out.length() > 1 && out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }

  /**
   * The project-scoped clone url, {@code <gitBase>/<projectId>/<repoName>}.
   *
   * <p>A container always knows its project — qits-projects injects {@code
   * QITS_PROJECTS_DAEMON_PROJECT_ID} on every one it creates, and the id is the control socket's own
   * path segment. A blank one therefore means a hand-run daemon, not a deployment, and it keeps the
   * flat form the git host served while storage ids were names. Cloning {@code
   * <gitBase>//<repoName>} instead would fail somewhere far from the empty variable that caused it.
   */
  static String rootUrl(String gitBase, Env env) {
    if (!projectScoped(env)) {
      return gitBase + "/" + env.repoName();
    }
    return gitBase + "/" + env.projectId() + "/" + env.repoName();
  }

  /**
   * Where a submodule's committed url is pointed instead: the sibling repository of the same name,
   * below this checkout's own scheme. A project's repositories are siblings <em>under the project
   * segment</em>.
   */
  static String siblingUrl(String gitBase, Env env, String submoduleUrl) {
    String sibling = basename(submoduleUrl);
    return projectScoped(env)
        ? gitBase + "/" + env.projectId() + "/" + sibling
        : gitBase + "/" + sibling;
  }

  /** Whether the project id — the first segment of the public repository address — was injected. */
  static boolean projectScoped(Env env) {
    return env.projectId() != null && !env.projectId().isBlank();
  }

  /** Whether the qits-githost repository name was injected. */
  static boolean nameAddressed(Env env) {
    return env.repoName() != null && !env.repoName().isBlank();
  }

  /**
   * Materialize one level of the checkout's submodules (from its committed {@code .gitmodules})
   * then descend. Relative urls resolve natively; an absolute url is redirected by basename to the
   * sibling under this project's segment. A submodule whose gitlink is not on this branch is
   * skipped; a submodule whose update fails (never imported, so no served sibling) is skipped with a
   * warning rather than failing the provision.
   *
   * <p><b>Known limitation.</b> The daemon has no database, so it materializes every submodule the
   * wrapper's {@code .gitmodules} names rather than a curated closure. Usually harmless — an
   * unserved submodule fails to fetch and is skipped. The sharp edge is a name collision: an
   * unimported submodule whose basename coincides with a <em>different</em> served repository in
   * the same project resolves to that sibling and the update <em>succeeds</em>, pulling in
   * unrelated content. Severity is bounded by the project model — a project is one maintainer's
   * curated repository set, so this is a naming mistake in their own project, not an outside
   * threat.
   */
  private static void materializeSubmodules(
      String gitBase, Env env, String rel, int depth, Consumer<DaemonMessage> emit) {
    if (depth >= MAX_SUBMODULE_DEPTH) {
      return;
    }
    String gitmodules = ".".equals(rel) ? ".gitmodules" : rel + "/.gitmodules";
    Captured listed =
        capture(
            List.of(
                "git", "config", "--file", gitmodules, "--get-regexp", "^submodule\\..*\\.path$"));
    if (listed.exitCode() != 0 || listed.stdout().isBlank()) {
      return;
    }
    List<Submodule> present = new ArrayList<>();
    for (Submodule sub : parseSubmodules(listed.stdout())) {
      // The gitlink may be absent on this branch (parsed from another branch's .gitmodules).
      if (capture(List.of("git", "-C", rel, "ls-files", "--error-unmatch", "--", sub.path()))
              .exitCode()
          != 0) {
        continue;
      }
      Captured committedUrl =
          capture(
              List.of(
                  "git", "config", "--file", gitmodules, "--get", "submodule." + sub.name() + ".url"));
      String url = committedUrl.exitCode() == 0 ? committedUrl.stdout().trim() : "";
      boolean relative = url.isEmpty() || url.startsWith("./") || url.startsWith("../");
      // A relative url resolves natively against the project-scoped origin; only an absolute url
      // needs redirecting to the sibling below this project's segment to stay offline.
      if (!relative) {
        runStreaming(
            List.of(
                "git",
                "-C",
                rel,
                "config",
                "submodule." + sub.name() + ".url",
                siblingUrl(gitBase, env, url)),
            Map.of(),
            emit);
      }
      int update =
          runStreaming(
              List.of("git", "-C", rel, "submodule", "update", "--init", "--", sub.path()),
              gitEnvironment(env),
              emit);
      if (update != 0) {
        emit.accept(
            new DaemonLog(
                "WARN",
                "skipping submodule '"
                    + sub.name()
                    + "' at "
                    + childRel(rel, sub.path())
                    + " (update exited "
                    + update
                    + ")"));
        continue;
      }
      present.add(sub);
    }
    for (Submodule sub : present) {
      materializeSubmodules(gitBase, env, childRel(rel, sub.path()), depth + 1, emit);
    }
  }

  record Submodule(String name, String path) {}

  /** Parse {@code git config --get-regexp} output lines ({@code submodule.<name>.path <path>}). */
  static List<Submodule> parseSubmodules(String getRegexpOutput) {
    List<Submodule> out = new ArrayList<>();
    for (String raw : getRegexpOutput.split("\n")) {
      String line = raw.trim();
      if (line.isEmpty()) {
        continue;
      }
      int space = line.indexOf(' ');
      if (space < 0) {
        continue;
      }
      String key = line.substring(0, space);
      String path = line.substring(space + 1).trim();
      if (!key.startsWith("submodule.") || !key.endsWith(".path") || path.isEmpty()) {
        continue;
      }
      String name = key.substring("submodule.".length(), key.length() - ".path".length());
      if (!name.isEmpty()) {
        out.add(new Submodule(name, path));
      }
    }
    return out;
  }

  private static String childRel(String rel, String path) {
    return ".".equals(rel) ? path : rel + "/" + path;
  }

  /**
   * The addressable basename of a submodule url ({@code https://h/o/foo.git} → {@code foo}), so an
   * absolute url redirects to the same served sibling name the git host publishes.
   */
  static String basename(String url) {
    String u = url == null ? "" : url.trim();
    while (u.length() > 1 && u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    int slash = u.lastIndexOf('/');
    String last = slash >= 0 ? u.substring(slash + 1) : u;
    int colon = last.lastIndexOf(':'); // scp-style user@host:path
    if (colon >= 0) {
      last = last.substring(colon + 1);
    }
    if (last.endsWith(".git")) {
      last = last.substring(0, last.length() - 4);
    }
    return last;
  }

  /** The current {@code HEAD} of the checkout, or {@code ""} if unreadable. */
  private static String head() {
    Captured rev = capture(List.of("git", "rev-parse", "HEAD"));
    return rev.exitCode() == 0 ? rev.stdout().trim() : "";
  }

  /**
   * Run a git command in {@code /workspace} (when it exists), streaming stdout+stderr as {@link
   * CommandChunk}s tagged {@link #PROVISION_CORRELATION_ID} so qits can feed the clone segment, and
   * return its exit code. Mirrors {@link CommandExecutor}'s pump, minus the terminal {@code
   * CommandExit} — a provision is not a command round-trip.
   */
  static int runStreaming(List<String> argv, Consumer<DaemonMessage> emit) {
    return runStreaming(argv, Map.of(), emit);
  }

  static int runStreaming(
      List<String> argv, Map<String, String> environment, Consumer<DaemonMessage> emit) {
    ProcessBuilder builder = new ProcessBuilder(argv);
    builder.environment().putAll(environment);
    if (WORKSPACE_DIR.isDirectory()) {
      builder.directory(WORKSPACE_DIR);
    }
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      emit.accept(
          new CommandChunk(PROVISION_CORRELATION_ID, Stream.STDERR, String.valueOf(e.getMessage())));
      return 127;
    }
    Thread stderrPump =
        new Thread(
            () -> pump(process.getErrorStream(), Stream.STDERR, emit),
            "projects-daemon-provision-stderr");
    stderrPump.setDaemon(true);
    stderrPump.start();
    pump(process.getInputStream(), Stream.STDOUT, emit);
    try {
      int exit = process.waitFor();
      stderrPump.join();
      return exit;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return 130;
    }
  }

  /**
   * Supplies Git's Authorization header through its process environment, never the command line,
   * remote URL, or checkout config. Git propagates this config to the fetches it forks for
   * submodules. An anonymous/developer deployment gets an unchanged empty environment.
   */
  static Map<String, String> gitEnvironment(Env env) {
    if (env.gitAuthorization() == null || env.gitAuthorization().isBlank()) {
      return Map.of();
    }
    return Map.of(
        "GIT_CONFIG_COUNT", "1",
        "GIT_CONFIG_KEY_0", "http.extraHeader",
        "GIT_CONFIG_VALUE_0", "Authorization: " + env.gitAuthorization());
  }

  private static void pump(InputStream stream, Stream channel, Consumer<DaemonMessage> emit) {
    byte[] buffer = new byte[BUFFER_SIZE];
    try (stream) {
      int read;
      while ((read = stream.read(buffer)) != -1) {
        if (read > 0) {
          emit.accept(
              new CommandChunk(
                  PROVISION_CORRELATION_ID,
                  channel,
                  new String(buffer, 0, read, StandardCharsets.UTF_8)));
        }
      }
    } catch (IOException e) {
      // Stream closed under us (the process died) — the exit code carries the outcome.
    }
  }

  private record Captured(int exitCode, String stdout) {}

  /** Run a short git read in {@code /workspace}, returning its exit and stdout ("" on failure). */
  private static Captured capture(List<String> argv) {
    try {
      ProcessBuilder builder =
          new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
      if (WORKSPACE_DIR.isDirectory()) {
        builder.directory(WORKSPACE_DIR);
      }
      Process process = builder.start();
      byte[] out = process.getInputStream().readAllBytes();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new Captured(-1, "");
      }
      return new Captured(process.exitValue(), new String(out, StandardCharsets.UTF_8));
    } catch (Exception e) {
      return new Captured(-1, "");
    }
  }
}
