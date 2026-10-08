package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The scheme decides TLS and the default port, for the control socket and the dial-back alike. */
class DaemonDialTest {

  @Test
  void wssIsTlsOn443() {
    URI uri = URI.create("wss://projects.qits.example.org/projects/daemon/7");
    WebSocketConnectOptions options = DaemonDial.connectOptions(uri, Optional.empty());

    assertTrue(DaemonDial.tls(uri));
    assertTrue(options.isSsl());
    assertEquals(443, options.getPort());
    assertEquals("projects.qits.example.org", options.getHost());
    assertEquals("/projects/daemon/7", options.getURI());
  }

  @Test
  void wsIsPlainOn80() {
    URI uri = URI.create("ws://qits-projects/projects/daemon/7");
    WebSocketConnectOptions options = DaemonDial.connectOptions(uri, Optional.empty());

    assertFalse(DaemonDial.tls(uri));
    assertFalse(options.isSsl());
    assertEquals(80, options.getPort());
    assertTrue(options.getHeaders() == null || options.getHeaders().get("Authorization") == null);
  }

  @Test
  void anExplicitPortWinsOverTheSchemeDefault() {
    assertEquals(8443, DaemonDial.port(URI.create("wss://h:8443/x")));
    assertEquals(8080, DaemonDial.port(URI.create("ws://h:8080/x")));
  }

  @Test
  void theAuthorizationRidesAsTheHeader() {
    WebSocketConnectOptions options =
        DaemonDial.connectOptions(URI.create("wss://h/x"), Optional.of("Bearer qits_tok_test"));

    assertEquals("Bearer qits_tok_test", options.getHeaders().get("Authorization"));
  }

  @Test
  void theClientVerifiesTheHost() {
    assertTrue(DaemonDial.clientOptions().isVerifyHost());
  }
}
