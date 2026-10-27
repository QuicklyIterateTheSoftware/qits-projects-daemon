package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ControlSocketAuthenticationTest {

  @Test
  void theProjectTokenIsTheBearer() {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.of(" t ");

    assertEquals(Optional.of("Bearer t"), socket.bearer());
  }

  @Test
  void aBlankTokenIsNoToken() {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.of("  ");

    assertEquals(Optional.empty(), socket.bearer());
  }

  /**
   * Deliberate, not a gap: the clone-alone/developer topology dials with no credential at all. Who
   * may open a control socket is the host's decision, not this daemon's.
   */
  @Test
  void noTokenKeepsTheDeveloperSocketAnonymous() {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.empty();

    assertEquals(Optional.empty(), socket.bearer());
    assertEquals(Optional.empty(), socket.dialBackAuthorization());
  }
}
