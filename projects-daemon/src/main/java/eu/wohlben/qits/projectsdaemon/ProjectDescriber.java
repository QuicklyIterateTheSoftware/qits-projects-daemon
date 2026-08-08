package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.protocol.ProjectInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Answers a {@code Describe} from in-container git: the current {@code HEAD} of the wrapper
 * checkout and whether the tree is dirty, alongside the identity qits-projects injected.
 * Framework-free; {@link ControlSocket} runs it on a worker thread. If git cannot be read (an
 * unprovisioned tree), it returns blanks rather than failing.
 *
 * <p>Also the source of {@link #head()}, which {@link DaemonProjectContext} records on every
 * command launch. The workspace daemon read that off an inotify-debounced working-tree watcher; a
 * project agent has no dirty badge to drive, so a fork per launch is cheaper than a watcher.
 */
public final class ProjectDescriber {

  /** Where the wrapper checkout lives in every agent container (image {@code WORKDIR}). */
  private static final File WORKSPACE_DIR = new File("/workspace");

  private ProjectDescriber() {}

  public static ProjectInfo describe(String projectId, String repoName) {
    // One git fork, not two: `status --porcelain=v2 --branch` reports the HEAD oid (in a
    // `# branch.oid` header) AND the dirty state (any non-header line) together.
    return parse(projectId, repoName, capture("git", "status", "--porcelain=v2", "--branch"));
  }

  /** The current {@code HEAD} of the checkout, or {@code ""} if unreadable. */
  public static String head() {
    return capture("git", "rev-parse", "HEAD").trim();
  }

  /**
   * Derive {@code head}/{@code dirty} from {@code git status --porcelain=v2 --branch} output. HEAD
   * is the {@code # branch.oid} value ({@code (initial)} — an unborn branch — maps to blank); the
   * tree is dirty if any non-{@code #} entry line is present. Blank input (git unreadable) yields
   * blank head and not-dirty. Package-private for unit testing without a real git tree.
   */
  static ProjectInfo parse(String projectId, String repoName, String statusV2) {
    String head = "";
    boolean dirty = false;
    for (String line : statusV2.split("\n", -1)) {
      if (line.startsWith("# branch.oid ")) {
        String oid = line.substring("# branch.oid ".length()).trim();
        head = oid.equals("(initial)") ? "" : oid;
      } else if (!line.isBlank() && !line.startsWith("#")) {
        dirty = true;
      }
    }
    return new ProjectInfo(projectId, repoName, head, dirty);
  }

  /** Run a git command in {@code /workspace} and return its stdout, or "" on any failure. */
  private static String capture(String... argv) {
    try {
      // Discard stderr to the OS null rather than a pipe: reading stdout to completion before
      // draining a stderr pipe deadlocks if git fills the ~64KB stderr buffer (many warnings), and
      // the 10s timeout below is only reached after the stdout read returns. DISCARD removes the
      // pipe entirely, so only stdout is read and there is nothing to deadlock on.
      ProcessBuilder builder =
          new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
      if (WORKSPACE_DIR.isDirectory()) {
        builder.directory(WORKSPACE_DIR);
      }
      Process process = builder.start();
      byte[] out = process.getInputStream().readAllBytes();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return "";
      }
      return process.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : "";
    } catch (Exception e) {
      return "";
    }
  }
}
