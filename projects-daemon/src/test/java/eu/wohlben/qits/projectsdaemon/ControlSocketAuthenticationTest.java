package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ControlSocketAuthenticationTest {

  @Test
  void commissionedClientMintsTheBearerUsedForDialHome() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    idp.createContext(
        "/idp/token",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] answer = "{\"access_token\":\"machine-token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    idp.start();
    try {
      ControlSocket socket = new ControlSocket();
      socket.commissionedClientId = Optional.of("dyn-agent");
      socket.commissionedClientSecret = Optional.of("one-time-secret");
      socket.authTokenUrl =
          Optional.of("http://127.0.0.1:" + idp.getAddress().getPort() + "/idp/token");
      socket.authAudience = Optional.of("dev-qits-projects");

      assertEquals(Optional.of("Bearer machine-token"), socket.authorization().get());
      // client_secret_post: the pair travels in the form body and no Basic header is sent — the
      // edge eats a Basic header rather than forwarding it (qits-767, as qits-625 did).
      assertNull(authorization.get(), "no Authorization header on the mint");
      assertTrue(body.get().contains("client_id=dyn-agent"), body.get());
      assertTrue(body.get().contains("client_secret=one-time-secret"), body.get());
      assertTrue(body.get().contains("grant_type=client_credentials"), body.get());
      assertTrue(body.get().contains("audience=dev-qits-projects"), body.get());
    } finally {
      idp.stop(0);
    }
  }

  @Test
  void noCommissionKeepsTheDeveloperSocketAnonymous() throws Exception {
    ControlSocket socket = new ControlSocket();
    socket.commissionedClientId = Optional.empty();
    socket.commissionedClientSecret = Optional.empty();
    socket.authTokenUrl = Optional.empty();
    socket.authAudience = Optional.empty();

    assertEquals(Optional.empty(), socket.authorization().get());
  }
}
