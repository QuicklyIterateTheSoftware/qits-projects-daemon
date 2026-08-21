package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdaemon.Provisioner.Env;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Container-free coverage of the {@link Provisioner}'s pure decision helpers — the git base,
 * project-scoped addressing, {@code .gitmodules} parsing, and basename normalization. The
 * end-to-end clone
 * and submodule walk touches {@code /workspace} and real git, so it is not exercised here; what is
 * pinned is the logic deciding <em>what</em> the daemon clones and how it addresses submodule
 * redirects.
 */
class ProvisionerTest {

  private static final String GIT_BASE = "http://qits-githost:8080/git";

  private static Env env(String projectId, String repoName) {
    return env(projectId, repoName, "");
  }

  private static Env env(String projectId, String repoName, String gitBaseUrl) {
    return new Env(projectId, repoName, gitBaseUrl, "");
  }

  /** Collects the messages a decision emits, so a silent refusal fails the test. */
  private static List<DaemonMessage> emitted(Env env) {
    List<DaemonMessage> out = new ArrayList<>();
    Provisioner.gitBase(env, out::add);
    return out;
  }

  @Test
  void anInjectedGitBaseIsTakenSilently() {
    Env env = env("proj-1", "qits-qits", GIT_BASE + "/");

    assertEquals(
        GIT_BASE,
        Provisioner.gitBase(env, m -> {}),
        "trailing slash trimmed, so rootUrl never doubles it");
    assertTrue(
        emitted(env).isEmpty(), "a configured host is not an assumption worth warning about");
  }

  /**
   * The derivation this replaced built {@code <control-socket authority>/artifacts/git}, a host that
   * serves no git since the byte-plane split. A missing setting has to read as a missing setting.
   */
  @Test
  void noInjectedGitBaseRefusesToCloneRatherThanGuessingAHost() {
    Env env = env("proj-1", "qits-qits");

    assertNull(Provisioner.gitBase(env, m -> {}));
    assertTrue(
        emitted(env).stream()
            .anyMatch(
                m ->
                    m instanceof DaemonLog log
                        && "WARN".equals(log.level())
                        && log.message().contains("qits.projects-daemon.git-base")),
        "the refusal names the key nobody set");

    List<DaemonMessage> out = new ArrayList<>();
    assertFalse(Provisioner.provision(env, out::add));
    assertTrue(
        out.getLast() instanceof ProvisionFailed failed
            && failed.message().contains("qits.projects-daemon.git-base"),
        "no checkout is better than a checkout from a host nobody named");
  }

  @Test
  void theCloneIsProjectScoped() {
    assertEquals(
        GIT_BASE + "/proj-1/qits-qits", Provisioner.rootUrl(GIT_BASE, env("proj-1", "qits-qits")));
    assertTrue(Provisioner.nameAddressed(env("proj-1", "qits-qits")));
    assertTrue(Provisioner.projectScoped(env("proj-1", "qits-qits")));
  }

  /**
   * qits-projects injects the project id on every container, so a blank one is a hand-run daemon.
   * It keeps the flat form the git host served while storage ids were names, rather than cloning
   * {@code <gitBase>//<repoName>} and failing a long way from the empty variable.
   */
  @Test
  void aBlankProjectIdKeepsTheFlatFormRatherThanAnEmptySegment() {
    assertEquals(GIT_BASE + "/qits-qits", Provisioner.rootUrl(GIT_BASE, env("", "qits-qits")));
    assertFalse(Provisioner.projectScoped(env("", "qits-qits")));
  }

  @Test
  void halfAnIdentityIsNotACloneUrl() {
    // The workspace daemon falls back to an id-addressed route here. A wrapper has no such
    // fallback: its submodule urls are relative, and an id-addressed root would break every one of
    // them. So a missing name fails the provision loudly rather than cloning something that cannot
    // resolve.
    assertFalse(Provisioner.nameAddressed(env("proj-1", "")));

    List<DaemonMessage> out = new ArrayList<>();
    assertFalse(Provisioner.provision(env("proj-1", ""), out::add));
    assertTrue(out.getLast() instanceof ProvisionFailed);
  }

  /** A wrapper's siblings are the repositories of its own project, never of the whole git host. */
  @Test
  void anAbsoluteSubmoduleUrlIsRedirectedUnderTheSameProjectSegment() {
    assertEquals(
        GIT_BASE + "/proj-1/qits-idp",
        Provisioner.siblingUrl(
            GIT_BASE, env("proj-1", "qits-qits"), "https://github.com/o/qits-idp.git"));
    assertEquals(
        GIT_BASE + "/qits-idp",
        Provisioner.siblingUrl(GIT_BASE, env("", "qits-qits"), "https://github.com/o/qits-idp.git"),
        "no project id, no project segment — the same fallback the clone url takes");
  }

  /**
   * Git's own rule for a relative submodule url, which is <b>not</b> {@link URI#resolve} — it
   * treats the superproject remote as a directory and {@code ../} drops one whole segment, where
   * RFC 3986 would first discard the repository name as a filename and land a level too high.
   */
  private static String gitRelative(String remote, String relative) {
    return remote.substring(0, remote.lastIndexOf('/')) + "/" + relative.substring("../".length());
  }

  /**
   * The repository name is the last segment of the clone URL, so a relative submodule URL lands on
   * another repository name inside the same project.
   */
  @Test
  void aRelativeSubmoduleUrlStaysInsideTheProjectSegment() {
    assertEquals(
        "/proj-1/qits-qits",
        Provisioner.rootUrl(GIT_BASE, env("proj-1", "qits-qits")).substring(GIT_BASE.length()),
        "the project segment, then the repository name");
    assertEquals(
        GIT_BASE + "/proj-1/sibling",
        gitRelative(Provisioner.rootUrl(GIT_BASE, env("proj-1", "qits-qits")), "../sibling"),
        "a relative submodule url lands on this project's sibling, not another project's");
  }

  @Test
  void parseSubmodulesReadsNameAndPathIgnoringJunk() {
    String getRegexp =
        "submodule.qits-idp.path services/qits-idp\n"
            + "submodule.shared.path libs/shared\n"
            + "\n"
            + "not-a-submodule-line\n";
    List<?> subs = Provisioner.parseSubmodules(getRegexp);
    assertEquals(2, subs.size());
    assertTrue(subs.toString().contains("services/qits-idp"));
    assertTrue(subs.toString().contains("libs/shared"));
  }

  @Test
  void basenameStripsGitSuffixAndPath() {
    assertEquals("foo", Provisioner.basename("https://h/o/foo.git"));
    assertEquals("foo", Provisioner.basename("/abs/foo.git"));
    assertEquals("foo", Provisioner.basename("git@host:o/foo.git"));
    assertEquals("foo", Provisioner.basename("../foo.git"));
    assertEquals("foo", Provisioner.basename("foo"));
  }

  @Test
  void gitAuthorizationIsEphemeralProcessConfiguration() {
    Env authenticated = new Env("proj-1", "qits-qits", "http://git", "Bearer token");

    assertEquals(
        Map.of(
            "GIT_CONFIG_COUNT", "1",
            "GIT_CONFIG_KEY_0", "http.extraHeader",
            "GIT_CONFIG_VALUE_0", "Authorization: Bearer token"),
        Provisioner.gitEnvironment(authenticated));
    assertTrue(Provisioner.gitEnvironment(env("proj-1", "qits-qits")).isEmpty());
  }
}
