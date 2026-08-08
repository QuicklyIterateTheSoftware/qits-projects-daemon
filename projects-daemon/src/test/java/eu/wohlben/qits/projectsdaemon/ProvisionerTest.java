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
import org.junit.jupiter.api.Test;

/**
 * Container-free coverage of the {@link Provisioner}'s pure decision helpers — url derivation,
 * name addressing, {@code .gitmodules} parsing, and basename normalization. The end-to-end clone
 * and submodule walk touches {@code /workspace} and real git, so it is not exercised here; what is
 * pinned is the logic deciding <em>what</em> the daemon clones and how it addresses submodule
 * redirects.
 */
class ProvisionerTest {

  private static final String DIAL_HOME = "ws://qits-projects:8080/projects/daemon/proj-1";

  private static Env env(String projectId, String repoName) {
    return env(projectId, repoName, "");
  }

  private static Env env(String projectId, String repoName, String gitBaseUrl) {
    return new Env(projectId, repoName, DIAL_HOME, gitBaseUrl);
  }

  /** Collects the messages a derivation emits, so a silent fallback fails the test. */
  private static List<DaemonMessage> emitted(Env env) {
    List<DaemonMessage> out = new ArrayList<>();
    Provisioner.gitBase(env, out::add);
    return out;
  }

  @Test
  void derivedGitBaseCarriesTheArtifactsSegment() {
    assertEquals(
        "http://qits-projects:8080/artifacts/git",
        Provisioner.derivedGitBase(DIAL_HOME),
        "the git host is qits-artifacts, and it serves its own segment on the network too");
  }

  @Test
  void derivedGitBaseDerivesHttpsFromWss() {
    assertEquals(
        "https://host:443/artifacts/git",
        Provisioner.derivedGitBase("wss://host:443/projects/daemon/x"));
  }

  @Test
  void derivedGitBaseOmitsPortWhenAbsent() {
    assertEquals(
        "http://host/artifacts/git", Provisioner.derivedGitBase("ws://host/projects/daemon/x"));
  }

  @Test
  void derivedGitBaseNullOnBlankOrUnparseable() {
    assertNull(Provisioner.derivedGitBase(null));
    assertNull(Provisioner.derivedGitBase(""));
    assertNull(Provisioner.derivedGitBase("::::not a url"));
  }

  @Test
  void anInjectedGitBaseWinsOverTheDerivedOneAndIsTakenSilently() {
    Env env = env("proj-1", "qits-qits", "http://qits-artifacts:8080/artifacts/git/");

    assertEquals(
        "http://qits-artifacts:8080/artifacts/git",
        Provisioner.gitBase(env, m -> {}),
        "trailing slash trimmed, so rootUrl never doubles it");
    assertTrue(
        emitted(env).isEmpty(), "a configured host is not an assumption worth warning about");
  }

  @Test
  void aDerivedGitBaseSaysSoRatherThanCloningQuietlyFromTheControlSocketsHost() {
    Env env = env("proj-1", "qits-qits");

    assertEquals("http://qits-projects:8080/artifacts/git", Provisioner.gitBase(env, m -> {}));
    assertTrue(
        emitted(env).stream()
            .anyMatch(m -> m instanceof DaemonLog log && log.message().contains("qits-artifacts")),
        "the single-authority assumption has to be visible where a wrong host is a bare connection"
            + " error");
  }

  @Test
  void theCloneIsAlwaysNameAddressed() {
    assertEquals(
        "http://qits-projects:8080/artifacts/git/proj-1/qits-qits",
        Provisioner.rootUrl("http://qits-projects:8080/artifacts/git", env("proj-1", "qits-qits")));
    assertTrue(Provisioner.nameAddressed(env("proj-1", "qits-qits")));
  }

  @Test
  void halfAnIdentityIsNotACloneUrl() {
    // The workspace daemon fell back to an id-addressed route here. A wrapper has no such fallback:
    // its submodule urls are relative, and an id-addressed root would break every one of them. So a
    // missing half fails the provision loudly rather than cloning something that cannot resolve.
    assertFalse(Provisioner.nameAddressed(env("proj-1", "")));
    assertFalse(Provisioner.nameAddressed(env("", "qits-qits")));

    List<DaemonMessage> out = new ArrayList<>();
    assertFalse(Provisioner.provision(env("proj-1", ""), out::add));
    assertTrue(out.getLast() instanceof ProvisionFailed);
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
   * The clone url must stay exactly two segments below the base: that length is what decides where
   * a relative submodule url lands. The {@code /artifacts} prefix is added above the base, so it
   * shifts the whole thing down and changes nothing below it.
   */
  @Test
  void theArtifactsPrefixDoesNotChangeHowManySegmentsSitBelowTheBase() {
    String base = "http://qits-artifacts:8080/artifacts/git";

    assertEquals(
        "/proj-1/qits-qits",
        Provisioner.rootUrl(base, env("proj-1", "qits-qits")).substring(base.length()),
        "two segments below the base");
    assertEquals(
        "http://qits-artifacts:8080/artifacts/git/proj-1/sibling",
        gitRelative(Provisioner.rootUrl(base, env("proj-1", "qits-qits")), "../sibling"),
        "a relative submodule url still lands on the project sibling, one segment deeper");
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
}
