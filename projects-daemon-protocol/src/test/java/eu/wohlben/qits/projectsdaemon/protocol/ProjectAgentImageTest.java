package eu.wohlben.qits.projectsdaemon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pin resolves, and it resolves to a CalVer.
 *
 * <p>This is the guard on the one thing about {@link ProjectAgentImage} that can silently break:
 * the resource-filtering block in this module's pom. Without filtering the class reads the literal
 * {@code ${project.version}}, and the consumer that pins the agent image by this constant would
 * compose an image reference nothing can pull — a failure that surfaces a repository away, as a
 * container that never starts.
 *
 * <p>It deliberately asserts the <em>shape</em> and not a value. The version is whatever release
 * stamped this tree, so an expected literal would be a line every release has to edit, and the
 * first missed edit would make this test fail for a correct build.
 */
class ProjectAgentImageTest {

  @Test
  void theVersionIsFilteredInAndIsACalVer() {
    String version = ProjectAgentImage.VERSION;
    assertTrue(
        version.matches("[0-9][0-9.]*[0-9]"),
        () -> "not a CalVer — is resource filtering still on? got: " + version);
  }

  @Test
  void theCoordinatesAreTheOnesThePipelinePublishesUnder() {
    // Literal strings, because all three are cross-repository contracts: the two repositories are
    // what `artifacts:` declares and what the two buildctl pushes tag, and `qits-projects-daemon`
    // is the name the runnable jar is PUT under in qits-artifacts' `daemons` store. A rename that
    // moves only one side is what this trio exists to catch.
    assertEquals("qits/project-agent", ProjectAgentImage.AGENT_REPOSITORY);
    assertEquals("qits/projects-daemon", ProjectAgentImage.DAEMON_REPOSITORY);
    assertEquals("qits-projects-daemon", ProjectAgentImage.DAEMON_NAME);
  }
}
