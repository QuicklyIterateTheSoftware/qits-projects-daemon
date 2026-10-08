package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.agents.AgentMcpNarrowing;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.ScopedMcp;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The edge plane, branch by branch: with {@code QITS_TOKEN} set it is the one credential on every
 * hop and nothing is minted; without it the commissioned pair is minted by {@code
 * client_secret_post} and the dial-back carries the same bearer the control socket dialled with.
 * The end-to-end proof over real TLS is {@link EdgePlaneTlsTest}.
 */
class EdgePlaneTest {

  private static final String TOKEN = "qits_tok_test";
  private static final String BEARER = "Bearer " + TOKEN;
  private static final String PROJECT = "11111111-1111-1111-1111-111111111111";

  // ---- the control socket ---------------------------------------------------

  @Test
  void theTokenIsTheControlSocketBearerAndNothingIsMinted() throws Exception {
    AtomicInteger mints = new AtomicInteger();
    HttpServer idp = tokenEndpoint(new AtomicReference<>(), new AtomicReference<>(), mints);
    try {
      // The pair is configured too: the token must win outright, not merely when it is absent.
      ControlSocket socket = commissioned(idp);
      socket.token = Optional.of(TOKEN);

      assertEquals(Optional.of(BEARER), socket.authorization().get());
      assertEquals(0, mints.get(), "the token endpoint must not be called");
    } finally {
      idp.stop(0);
    }
  }

  @Test
  void aBlankTokenIsNoToken() throws Exception {
    ControlSocket socket = anonymous();
    socket.token = Optional.of("  ");

    assertEquals(Optional.empty(), socket.authorization().get());
    assertEquals(Optional.empty(), socket.dialBackAuthorization());
  }

  @Test
  void withoutTheTokenThePairIsMintedByClientSecretPost() throws Exception {
    AtomicReference<String> header = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer idp = tokenEndpoint(header, body, new AtomicInteger());
    try {
      ControlSocket socket = commissioned(idp);
      socket.token = Optional.empty();
      socket.commissionedClientSecret = Optional.of("s&cret=+/");

      assertEquals(Optional.of("Bearer machine-token"), socket.authorization().get());
      assertNull(header.get(), "no Basic header on the mint");
      assertTrue(body.get().contains("client_id=dyn-agent"), body.get());
      assertTrue(body.get().contains("client_secret=s%26cret%3D%2B%2F"), body.get());
      assertTrue(body.get().contains("audience=dev-qits-projects"), body.get());
    } finally {
      idp.stop(0);
    }
  }

  // ---- the dial-back --------------------------------------------------------

  @Test
  void theDialBackCarriesTheTokenWhenSet() {
    ControlSocket socket = anonymous();
    socket.token = Optional.of(TOKEN);

    assertEquals(Optional.of(BEARER), socket.dialBackAuthorization());
  }

  @Test
  void withoutTheTokenTheDialBackCarriesTheBearerTheControlSocketMinted() throws Exception {
    HttpServer idp =
        tokenEndpoint(new AtomicReference<>(), new AtomicReference<>(), new AtomicInteger());
    try {
      ControlSocket socket = commissioned(idp);

      assertEquals(Optional.empty(), socket.dialBackAuthorization(), "nothing minted yet");
      socket.authorization().get();

      assertEquals(Optional.of("Bearer machine-token"), socket.dialBackAuthorization());
    } finally {
      idp.stop(0);
    }
  }

  @Test
  void theGitMintDoesNotBecomeTheDialBackBearer() throws Exception {
    HttpServer idp =
        tokenEndpoint(new AtomicReference<>(), new AtomicReference<>(), new AtomicInteger());
    try {
      ControlSocket socket = commissioned(idp);
      socket.gitAuthAudience = Optional.of("dev-qits-githost");

      socket.authorization(socket.gitAuthAudience).get();

      assertEquals(
          Optional.empty(),
          socket.dialBackAuthorization(),
          "a bearer for qits-githost's audience is not qits-projects' credential");
    } finally {
      idp.stop(0);
    }
  }

  @Test
  void theAnonymousDeveloperDialBackStaysAnonymous() throws Exception {
    ControlSocket socket = anonymous();

    socket.authorization().get();

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
  void theTokenIsTheGitHeaderAndNothingIsMinted() throws Exception {
    AtomicInteger mints = new AtomicInteger();
    HttpServer idp = tokenEndpoint(new AtomicReference<>(), new AtomicReference<>(), mints);
    try {
      ControlSocket socket = commissioned(idp);
      socket.gitAuthAudience = Optional.of("dev-qits-githost");
      socket.token = Optional.of(TOKEN);

      String gitAuthorization = socket.authorization(socket.gitAuthAudience).get().orElse("");
      Map<String, String> env =
          Provisioner.gitEnvironment(
              new Provisioner.Env(PROJECT, "qits-qits", "https://git.example/git", gitAuthorization));

      assertEquals(0, mints.get(), "the token endpoint must not be called");
      assertEquals("1", env.get("GIT_CONFIG_COUNT"));
      assertEquals("http.extraHeader", env.get("GIT_CONFIG_KEY_0"));
      assertEquals("Authorization: " + BEARER, env.get("GIT_CONFIG_VALUE_0"));
    } finally {
      idp.stop(0);
    }
  }

  // ---- MCP --------------------------------------------------------------------

  @Test
  void theTokenRidesBothPlatformMcpServers() {
    ProjectMcpServers servers =
        new ProjectMcpServers(endpoints(), "qits-qits", Optional.of(TOKEN));
    Map<String, String> expected = Map.of("Authorization", BEARER);

    assertEquals(expected, servers.platformHeaders());
    assertEquals(expected, servers.serversFor(AgentMcpScope.PROJECT).getFirst().headers());
    assertEquals(expected, servers.serversFor(AgentMcpScope.REPOSITORY).getFirst().headers());
    AgentMcpNarrowing narrowing = new AgentMcpNarrowing(true, true, false);
    ScopedMcp repository =
        servers.serverFor("repository", AgentMcpScope.REPOSITORY, narrowing).orElseThrow();
    ScopedMcp qits = servers.serverFor("qits", AgentMcpScope.REPOSITORY, narrowing).orElseThrow();
    assertEquals(expected, repository.headers());
    assertEquals(expected, qits.headers());
  }

  @Test
  void withoutTheTokenNoMcpServerCarriesHeaders() {
    for (ProjectMcpServers servers :
        new ProjectMcpServers[] {
          new ProjectMcpServers(endpoints(), "qits-qits"),
          new ProjectMcpServers(endpoints(), "qits-qits", Optional.empty()),
          new ProjectMcpServers(endpoints(), "qits-qits", Optional.of(" ")),
        }) {
      assertEquals(Map.of(), servers.platformHeaders());
      assertEquals(Map.of(), servers.serversFor(AgentMcpScope.PROJECT).getFirst().headers());
      assertEquals(
          Map.of(),
          servers
              .serverFor("qits", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, false, false))
              .orElseThrow()
              .headers());
    }
  }

  // ---- the checkout follower and the derived MCP base ---------------------------

  @Test
  void theFollowerInjectsNoTokenUrlWhenNoneIsSet() {
    Map<String, String> derived =
        CheckoutFollower.additionalEnvironment(
            Map.of("QITS_TOKEN", TOKEN), "https://git.qits.example.org/git", "", "");

    assertFalse(derived.containsKey("QITS_GIT_AUTH_TOKEN_URL"), derived.toString());
    assertFalse(derived.containsKey("QITS_GIT_AUTH_AUDIENCE"), derived.toString());
    assertEquals("git.qits.example.org", derived.get("QITS_GIT_AUTH_HOST"));
  }

  @Test
  void anEdgeDialHomeUrlDerivesAnHttpsMcpBase() {
    assertEquals(
        "https://projects.qits.example.org",
        DaemonMcpEndpoints.httpBaseOf("wss://projects.qits.example.org/projects/daemon/x"));
  }

  // ---- fixtures -----------------------------------------------------------------

  private static DaemonMcpEndpoints endpoints() {
    return new DaemonMcpEndpoints(
        "wss://projects.qits.example.org/projects/daemon/" + PROJECT,
        PROJECT,
        Optional.empty(),
        Optional.of("https://mcp.qits.example.org/mcp"));
  }

  private static ControlSocket anonymous() {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.empty();
    socket.commissionedClientId = Optional.empty();
    socket.commissionedClientSecret = Optional.empty();
    socket.authTokenUrl = Optional.empty();
    socket.authAudience = Optional.empty();
    socket.gitAuthAudience = Optional.empty();
    return socket;
  }

  private static ControlSocket commissioned(HttpServer idp) {
    ControlSocket socket = anonymous();
    socket.commissionedClientId = Optional.of("dyn-agent");
    socket.commissionedClientSecret = Optional.of("one-time-secret");
    socket.authTokenUrl =
        Optional.of("http://127.0.0.1:" + idp.getAddress().getPort() + "/idp/token");
    socket.authAudience = Optional.of("dev-qits-projects");
    return socket;
  }

  private static HttpServer tokenEndpoint(
      AtomicReference<String> header, AtomicReference<String> body, AtomicInteger calls)
      throws Exception {
    HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    idp.createContext(
        "/idp/token",
        exchange -> {
          calls.incrementAndGet();
          header.set(exchange.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] answer = "{\"access_token\":\"machine-token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    idp.start();
    return idp;
  }
}
