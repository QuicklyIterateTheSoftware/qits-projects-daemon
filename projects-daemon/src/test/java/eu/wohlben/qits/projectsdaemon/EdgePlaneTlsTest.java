package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.net.PfxOptions;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The edge in miniature, end to end: a TLS WebSocket server with a self-signed certificate for a
 * dotted host plays {@code projects.qits.<domain>}, and the daemon's two dials — the control socket
 * (its own options, on a client built as {@link ControlSocket} builds it, with the authorization
 * {@link ControlSocket#authorization()} answers) and a dial-back through the real {@link
 * DaemonStreamTunnel} wired to {@link ControlSocket#dialBackAuthorization()} — must both complete a
 * verified TLS handshake and both carry the same bearer.
 *
 * <p>The certificate is trusted only through a test trust store installed as the JVM default, which
 * is the same default trust store the daemon relies on against the edge's public certificate, so
 * nothing about the dial is configured for the test — no trust-all. Host verification stays on.
 * Copied in shape from qits-workspace-daemon's {@code DaemonStreamTunnelTest} (qits-625).
 */
class EdgePlaneTlsTest {

  /** Dotted on purpose: the JDK sends no SNI for a dotless host such as {@code localhost}. */
  private static final String EDGE_HOST = "projects.qits.test";

  private static final String CONTROL_PATH = "/projects/daemon/7";
  private static final String STREAM_PATH = "/projects/daemon/stream/test-nonce";

  @TempDir Path dir;

  private Vertx vertx;
  private Context ctx;
  private io.vertx.core.http.HttpServer edge;
  private io.vertx.core.http.HttpServer api;
  private DaemonStreamTunnel tunnel;
  private WebSocketClient control;
  private String previousStore;
  private String previousPassword;
  private String previousType;

  /** {@code path auth=<Authorization>} for every upgrade the edge saw, in arrival order. */
  private final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();

  private final CompletableFuture<ServerWebSocket> controlDial = new CompletableFuture<>();
  private final CompletableFuture<ServerWebSocket> tunnelDial = new CompletableFuture<>();

  @BeforeEach
  void setUp() throws Exception {
    vertx =
        Vertx.vertx(
            new VertxOptions()
                .setAddressResolverOptions(
                    new AddressResolverOptions()
                        .setHostsValue(Buffer.buffer("127.0.0.1 " + EDGE_HOST + "\n"))));
    ctx = vertx.getOrCreateContext();
    Path keyStore = selfSignedEdge(dir);
    previousStore = System.getProperty("javax.net.ssl.trustStore");
    previousPassword = System.getProperty("javax.net.ssl.trustStorePassword");
    previousType = System.getProperty("javax.net.ssl.trustStoreType");
    System.setProperty("javax.net.ssl.trustStore", dir.resolve("trust.p12").toString());
    System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

    edge =
        vertx.createHttpServer(
            new HttpServerOptions()
                .setSsl(true)
                .setSni(true)
                .setKeyCertOptions(
                    new PfxOptions().setPath(keyStore.toString()).setPassword("changeit")));
    edge.requestHandler(
        req -> {
          seen.add(
              req.path()
                  + " sni="
                  + req.connection().indicatedServerName()
                  + " auth="
                  + req.getHeader("Authorization"));
          req.toWebSocket()
              .onSuccess(
                  socket -> {
                    if (req.path().equals(STREAM_PATH)) {
                      tunnelDial.complete(socket);
                    } else {
                      controlDial.complete(socket);
                    }
                  });
        });
    await(edge.listen(0, "127.0.0.1"));

    api = vertx.createHttpServer();
    api.requestHandler(
        req -> req.response().putHeader("Connection", "close").end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (control != null) {
        control.close();
      }
      if (tunnel != null) {
        tunnel.close();
      }
      if (vertx != null) {
        await(vertx.close());
      }
    } finally {
      restore("javax.net.ssl.trustStore", previousStore);
      restore("javax.net.ssl.trustStorePassword", previousPassword);
      restore("javax.net.ssl.trustStoreType", previousType);
    }
  }

  @Test
  void withTheTokenBothDialsCarryItOverTls() throws Exception {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.of("qits_tok_test");
    // A pair is configured too, pointing nowhere: the token must win without a mint being tried.
    socket.commissionedClientId = Optional.of("dyn-agent");
    socket.commissionedClientSecret = Optional.of("one-time-secret");
    socket.authTokenUrl = Optional.of("http://127.0.0.1:1/idp/token");
    socket.authAudience = Optional.of("dev-qits-projects");

    dialBoth(socket);

    String auth = " sni=" + EDGE_HOST + " auth=Bearer qits_tok_test";
    assertEquals(List.of(CONTROL_PATH + auth, STREAM_PATH + auth), seen);
  }

  @Test
  void withoutTheTokenTheDialBackCarriesTheMintedBearer() throws Exception {
    HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    idp.createContext(
        "/idp/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] answer = "{\"access_token\":\"machine-token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    idp.start();
    try {
      ControlSocket socket = new ControlSocket();
      socket.token = Optional.empty();
      socket.commissionedClientId = Optional.of("dyn-agent");
      socket.commissionedClientSecret = Optional.of("one-time-secret");
      socket.authTokenUrl =
          Optional.of("http://127.0.0.1:" + idp.getAddress().getPort() + "/idp/token");
      socket.authAudience = Optional.of("dev-qits-projects");

      dialBoth(socket);

      String auth = " sni=" + EDGE_HOST + " auth=Bearer machine-token";
      assertEquals(List.of(CONTROL_PATH + auth, STREAM_PATH + auth), seen);
    } finally {
      idp.stop(0);
    }
  }

  /**
   * The control socket's dial, then one dial-back through the real tunnel, then a request through
   * that tunnel so the pipe is proven to carry bytes and not merely to have upgraded.
   */
  private void dialBoth(ControlSocket socket) throws Exception {
    String socketUrl = "wss://" + EDGE_HOST + ":" + edge.actualPort() + CONTROL_PATH;
    Optional<String> authorization = socket.authorization().get(10, TimeUnit.SECONDS);

    control = vertx.createWebSocketClient(DaemonDial.clientOptions());
    Promise<WebSocket> connected = Promise.promise();
    ctx.runOnContext(
        v ->
            control
                .connect(ControlSocket.dialOptions(URI.create(socketUrl), authorization))
                .onComplete(connected));
    await(connected.future());
    controlDial.get(15, TimeUnit.SECONDS);

    tunnel =
        new DaemonStreamTunnel(vertx, socketUrl, socket::dialBackAuthorization, api.actualPort());
    tunnel.start();
    ctx.runOnContext(v -> tunnel.open("test-nonce", STREAM_PATH));
    ServerWebSocket remote = tunnelDial.get(15, TimeUnit.SECONDS);

    CompletableFuture<String> answered = new CompletableFuture<>();
    StringBuilder received = new StringBuilder();
    remote.handler(
        buffer -> {
          received.append(buffer.toString(StandardCharsets.UTF_8));
          if (received.indexOf("api:/files") >= 0) {
            answered.complete(received.toString());
          }
        });
    remote.writeBinaryMessage(
        Buffer.buffer("GET /files HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
    String response = answered.get(15, TimeUnit.SECONDS);
    assertTrue(response.startsWith("HTTP/1.1 200"), response);
  }

  /**
   * A PKCS12 key store holding a self-signed certificate for {@link #EDGE_HOST}, and beside it
   * ({@code trust.p12}) a trust store holding only that certificate. {@code keytool} rather than a
   * certificate library: it ships with every JDK, and nothing new lands on the test classpath.
   */
  private static Path selfSignedEdge(Path dir) throws Exception {
    Path keyStore = dir.resolve("server.p12");
    Path cert = dir.resolve("server.cer");
    Path trust = dir.resolve("trust.p12");
    keytool(
        "-genkeypair", "-alias", "edge", "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=" + EDGE_HOST, "-ext", "san=dns:" + EDGE_HOST, "-validity", "2",
        "-storetype", "PKCS12", "-keystore", keyStore.toString(),
        "-storepass", "changeit", "-keypass", "changeit");
    keytool(
        "-exportcert", "-alias", "edge", "-keystore", keyStore.toString(),
        "-storepass", "changeit", "-file", cert.toString());
    keytool(
        "-importcert", "-noprompt", "-alias", "edge", "-file", cert.toString(),
        "-storetype", "PKCS12", "-keystore", trust.toString(), "-storepass", "changeit");
    assertTrue(Files.exists(trust));
    return keyStore;
  }

  private static void keytool(String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), "keytool failed: " + output);
  }

  private static void restore(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }
}
