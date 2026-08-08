package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.projectsdaemon.commands.InvalidCommandRequestException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The MCP address derivation. A string built from another string is exactly the kind of thing that
 * rots invisibly: a wrong url fails as a 404 an agent reports as a missing tool, not as an error
 * anybody sees.
 */
class DaemonMcpEndpointsTest {

  private static final String PROJECT = "11111111-1111-1111-1111-111111111111";
  private static final String DIAL_HOME = "ws://qits-projects:8080/projects/daemon/" + PROJECT;

  private static DaemonMcpEndpoints endpoints(Optional<String> repository) {
    return new DaemonMcpEndpoints(DIAL_HOME, PROJECT, repository);
  }

  private static DaemonMcpEndpoints derived() {
    return endpoints(Optional.empty());
  }

  @Test
  void theRepositoryServerIsAddressedUnderTheProjectsSegment() {
    assertEquals(
        "http://qits-projects:8080/projects/mcp",
        derived().mcpUrl("repository"),
        "the control socket and this server are the same service, so the authority is not a guess");
  }

  @Test
  void anUnknownServerNameIsRefusedRatherThanTurnedIntoAPath() {
    // The workspace daemon addressed three servers; this one addresses one. Anything else must
    // refuse rather than fabricate a base, or the launch reads as having worked.
    assertThrows(InvalidCommandRequestException.class, () -> derived().mcpUrl("observability"));
    assertThrows(InvalidCommandRequestException.class, () -> derived().mcpUrl("actions"));
    assertThrows(InvalidCommandRequestException.class, () -> derived().mcpUrl("repositories"));
  }

  @Test
  void anExplicitUrlWins() {
    assertEquals(
        "http://elsewhere:9000/projects/mcp",
        endpoints(Optional.of("http://elsewhere:9000/projects/mcp")).mcpUrl("repository"));
  }

  @Test
  void aBlankOverrideIsNoOverride() {
    assertEquals(
        "http://qits-projects:8080/projects/mcp",
        endpoints(Optional.of("   ")).mcpUrl("repository"));
  }

  @Test
  void httpBaseKeepsOnlyTheAuthority() {
    assertEquals(
        "http://qits-projects:8080",
        DaemonMcpEndpoints.httpBaseOf(DIAL_HOME),
        "the dial-home path addresses the control socket, not the MCP server on the same service");
    assertEquals("https://host", DaemonMcpEndpoints.httpBaseOf("wss://host/projects/daemon/x"));
    assertEquals(
        "https://host:443", DaemonMcpEndpoints.httpBaseOf("wss://host:443/projects/daemon/x"));
  }

  @Test
  void aMissingOrMalformedDialHomeUrlIsRefusedAtConstruction() {
    assertThrows(IllegalStateException.class, () -> DaemonMcpEndpoints.httpBaseOf(null));
    assertThrows(IllegalStateException.class, () -> DaemonMcpEndpoints.httpBaseOf(""));
    assertThrows(IllegalStateException.class, () -> DaemonMcpEndpoints.httpBaseOf("/no-authority"));
  }

  @Test
  void theProjectIdIsPassedThroughUntouched() {
    assertEquals(PROJECT, derived().projectId());
  }
}
