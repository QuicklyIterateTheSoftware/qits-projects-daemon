package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.ScopedMcp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The servers this daemon supplies across the library's {@code AgentMcpServers} seam. */
class ProjectMcpServersTest {

  private static final String PLATFORM_URL = "http://dev-qits-platform-access-mcp-service:8080/mcp";

  @Test
  void theOnlyServerIsQitsAtEveryScope() {
    ProjectMcpServers servers = new ProjectMcpServers(Optional.of(PLATFORM_URL), Optional.empty());

    for (AgentMcpScope scope : AgentMcpScope.values()) {
      List<ScopedMcp> attached = servers.serversFor(scope);
      assertEquals(1, attached.size(), scope.toString());
      assertEquals("qits", attached.get(0).key());
      assertEquals(PLATFORM_URL, attached.get(0).url(), "scoped by the bearer, not by the url");
      assertEquals(ProjectMcpServers.QITS_TOOLS, attached.get(0).allowedTools());
    }
  }

  @Test
  void theRetiredServersAreNotServed() {
    ProjectMcpServers servers = new ProjectMcpServers(Optional.of(PLATFORM_URL), Optional.empty());

    for (AgentMcpScope scope : AgentMcpScope.values()) {
      List<String> keys = servers.serversFor(scope).stream().map(ScopedMcp::key).toList();
      for (String retired : List.of("repository", "observability", "actions")) {
        assertFalse(keys.contains(retired), retired);
      }
      assertTrue(keys.contains("qits"));
    }
  }

  @Test
  void withoutAnAddressNothingIsAttached() {
    for (Optional<String> url : List.of(Optional.<String>empty(), Optional.of("  "))) {
      assertEquals(
          List.of(), new ProjectMcpServers(url, Optional.empty()).serversFor(AgentMcpScope.PROJECT));
    }
  }

  @Test
  void theTokenRidesTheQitsServer() {
    ProjectMcpServers servers = new ProjectMcpServers(Optional.of(PLATFORM_URL), Optional.of("t"));

    assertEquals(Map.of("Authorization", "Bearer t"), servers.platformHeaders());
    assertEquals(
        Map.of("Authorization", "Bearer t"),
        servers.serversFor(AgentMcpScope.PROJECT).getFirst().headers());
  }
}
