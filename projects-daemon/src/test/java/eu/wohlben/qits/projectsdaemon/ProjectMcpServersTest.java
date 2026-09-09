package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentMcpNarrowing;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.agents.ScopedMcp;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The mapping this daemon supplies across the library's {@code AgentMcpServers} seam.
 *
 * <p>The urls are asserted as literals because that is what they are downstream: each one is
 * embedded in a single-quoted shell argument of a rendered launch command, which the library's own
 * suite asserts byte for byte against this same mapping (its {@code ProjectHostMcpServers}
 * fixture). A parameter order changed here moves every one of those assertions.
 */
class ProjectMcpServersTest {

  private static final String BASE = "http://qits-projects:8080/projects/mcp";

  private final ProjectMcpServers servers =
      new ProjectMcpServers(new FixedEndpoints("proj-7"), "qits-qits");

  @Test
  void aProjectScopedLaunchSeesEveryRepositoryInTheProject() {
    List<ScopedMcp> attached = servers.serversFor(AgentMcpScope.PROJECT);

    assertEquals(1, attached.size(), "exactly one server, and that is the decision");
    assertEquals("repository", attached.get(0).key());
    assertEquals(BASE + "?projectId=proj-7", attached.get(0).url());
    assertEquals(
        ProjectMcpServers.READ_ONLY_REPOSITORY_TOOLS,
        attached.get(0).allowedTools(),
        "the pre-approval travels with the mapping, in order");
  }

  @Test
  void aRepositoryScopedLaunchIsNarrowedToTheOneCheckout() {
    assertEquals(
        BASE + "?projectId=proj-7&repositoryId=qits-qits",
        servers.serversFor(AgentMcpScope.REPOSITORY).get(0).url());
  }

  @Test
  void theActionsScopeIsRefusedRatherThanServedTheRepositoryServer() {
    InvalidCommandRequestException refused =
        assertThrows(
            InvalidCommandRequestException.class,
            () -> servers.serversFor(AgentMcpScope.ACTIONS));

    assertTrue(refused.getMessage().contains("ACTIONS"));
  }

  @Test
  void aConfiguredNarrowingIsRenderedExactlyAsAsked() {
    assertEquals(
        BASE + "?projectId=proj-7",
        narrowed(true, false, false).url(),
        "the project alone");
    assertEquals(
        BASE + "?projectId=proj-7&repositoryId=qits-qits",
        narrowed(true, true, false).url(),
        "and in the canonical projectId, repositoryId order");
    assertEquals(
        BASE + "?repositoryId=qits-qits",
        narrowed(false, true, false).url(),
        "the repository without the project, if that is what the document says");
    assertEquals(BASE, narrowed(false, false, false).url(), "and unnarrowed is a value too");
  }

  @Test
  void aNarrowingThisContainerCannotSatisfyIsRefusedRatherThanDropped() {
    InvalidCommandRequestException refused =
        assertThrows(
            InvalidCommandRequestException.class,
            () ->
                servers.serverFor(
                    "repository", AgentMcpScope.PROJECT, new AgentMcpNarrowing(true, false, true)));

    assertTrue(
        refused.getMessage().contains("workspace"),
        "there is no workspace here, and answering for the whole project instead would be a"
            + " session quietly wider than it was configured to be");
  }

  @Test
  void aServerThisHostDoesNotServeIsEmpty() {
    assertTrue(
        servers
            .serverFor("observability", AgentMcpScope.PROJECT, new AgentMcpNarrowing(true, false, false))
            .isEmpty(),
        "the launch turns this into its own refusal naming the surface");
  }

  @Test
  void theSeamIsHonoured() {
    assertTrue(
        servers.honoursNarrowing(),
        "so no launch records that its addressing was the host's rather than the document's");
  }

  @Test
  void anIdOutsideThePlatformGrammarNeverReachesAUrl() {
    ProjectMcpServers hostile =
        new ProjectMcpServers(new FixedEndpoints("proj-7"), "qits-qits' --dangerously");

    assertThrows(
        InvalidCommandRequestException.class,
        () -> hostile.serversFor(AgentMcpScope.REPOSITORY),
        "the url is embedded in a single-quoted shell argument and nothing escapes it");
  }

  private ScopedMcp narrowed(boolean project, boolean repository, boolean workspace) {
    Optional<ScopedMcp> server =
        servers.serverFor(
            "repository",
            AgentMcpScope.PROJECT,
            new AgentMcpNarrowing(project, repository, workspace));
    return server.orElseThrow();
  }

  private record FixedEndpoints(String projectId) implements McpEndpoints {
    @Override
    public String mcpUrl(String server) {
      if (!"repository".equals(server)) {
        throw new InvalidCommandRequestException("Unknown MCP server '" + server + "'");
      }
      return BASE;
    }
  }
}
