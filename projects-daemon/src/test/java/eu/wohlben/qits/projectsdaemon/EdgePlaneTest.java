package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentMcpScope;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The edge plane, branch by branch: with {@code QITS_TOKEN} set it is the one credential on every
 * hop and nothing is minted; without it every hop — the control socket, the dial-back, the boot
 * clone's git header — dials anonymously, the clone-alone/developer topology's deliberate shape.
 * The end-to-end proof over real TLS is {@link EdgePlaneTlsTest}.
 */
class EdgePlaneTest {

  private static final String TOKEN = "qits_tok_test";
  private static final String BEARER = "Bearer " + TOKEN;
  private static final String PROJECT = "11111111-1111-1111-1111-111111111111";

  // ---- the control socket ---------------------------------------------------

  @Test
  void theTokenIsTheControlSocketBearer() {
    ControlSocket socket = anonymous();
    socket.token = Optional.of(TOKEN);

    assertEquals(Optional.of(BEARER), socket.bearer());
  }

  @Test
  void aBlankTokenIsNoToken() {
    ControlSocket socket = anonymous();
    socket.token = Optional.of("  ");

    assertEquals(Optional.empty(), socket.bearer());
    assertEquals(Optional.empty(), socket.dialBackAuthorization());
  }

  // ---- the dial-back --------------------------------------------------------

  @Test
  void theDialBackCarriesTheTokenWhenSet() {
    ControlSocket socket = anonymous();
    socket.token = Optional.of(TOKEN);

    assertEquals(Optional.of(BEARER), socket.dialBackAuthorization());
  }

  /**
   * Deliberate, not a gap: the clone-alone/developer topology dials every hop with no credential at
   * all. Who may open a control socket is the host's decision, not this daemon's.
   */
  @Test
  void theAnonymousDeveloperDialBackStaysAnonymous() {
    ControlSocket socket = anonymous();

    assertEquals(Optional.empty(), socket.dialBackAuthorization());
  }

  // ---- TLS ------------------------------------------------------------------

  @Test
  void aWssUrlDialsTlsOnTheDefaultTlsPort() {
    WebSocketConnectOptions options =
        ControlSocket.dialOptions(
            URI.create("wss://projects.qits.example.org/projects/daemon/7"), Optional.of(BEARER));

    assertTrue(options.isSsl());
    assertEquals(443, options.getPort());
    assertEquals("projects.qits.example.org", options.getHost());
    assertEquals("/projects/daemon/7", options.getURI());
    assertEquals(BEARER, options.getHeaders().get("Authorization"));
  }

  // ---- git --------------------------------------------------------------------

  @Test
  void theTokenIsTheGitHeader() {
    ControlSocket socket = anonymous();
    socket.token = Optional.of(TOKEN);

    String gitAuthorization = socket.bearer().orElse("");
    Map<String, String> env =
        Provisioner.gitEnvironment(
            new Provisioner.Env(PROJECT, "qits-qits", "https://git.example/git", gitAuthorization));

    assertEquals("1", env.get("GIT_CONFIG_COUNT"));
    assertEquals("http.extraHeader", env.get("GIT_CONFIG_KEY_0"));
    assertEquals("Authorization: " + BEARER, env.get("GIT_CONFIG_VALUE_0"));
  }

  @Test
  void noTokenIsNoGitHeader() {
    ControlSocket socket = anonymous();

    String gitAuthorization = socket.bearer().orElse("");
    Map<String, String> env =
        Provisioner.gitEnvironment(
            new Provisioner.Env(PROJECT, "qits-qits", "https://git.example/git", gitAuthorization));

    assertEquals(Map.of(), env, "an anonymous/developer clone gets an unchanged empty environment");
  }

  // ---- MCP --------------------------------------------------------------------

  @Test
  void theTokenRidesTheQitsServer() {
    ProjectMcpServers servers =
        new ProjectMcpServers(Optional.of("https://mcp.qits.example.org/mcp"), Optional.of(TOKEN));
    Map<String, String> expected = Map.of("Authorization", BEARER);

    assertEquals(expected, servers.platformHeaders());
    assertEquals(expected, servers.serversFor(AgentMcpScope.PROJECT).getFirst().headers());
  }

  @Test
  void withoutTheTokenNoMcpServerCarriesHeaders() {
    for (Optional<String> token :
        List.of(Optional.<String>empty(), Optional.of(" "))) {
      ProjectMcpServers servers =
          new ProjectMcpServers(Optional.of("https://mcp.qits.example.org/mcp"), token);
      assertEquals(Map.of(), servers.platformHeaders());
      assertEquals(Map.of(), servers.serversFor(AgentMcpScope.PROJECT).getFirst().headers());
    }
  }

  // ---- the checkout follower ----------------------------------------------------

  @Test
  void theFollowerNeverInjectsThePairsNames() {
    Map<String, String> derived =
        CheckoutFollower.additionalEnvironment(
            Map.of("QITS_TOKEN", TOKEN), "https://git.qits.example.org/git");

    assertFalse(derived.containsKey("QITS_GIT_AUTH_TOKEN_URL"), derived.toString());
    assertFalse(derived.containsKey("QITS_GIT_AUTH_AUDIENCE"), derived.toString());
    assertEquals("git.qits.example.org", derived.get("QITS_GIT_AUTH_HOST"));
  }

  // ---- fixtures -----------------------------------------------------------------

  private static ControlSocket anonymous() {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.empty();
    return socket;
  }
}
