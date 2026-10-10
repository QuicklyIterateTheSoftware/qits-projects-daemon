package eu.wohlben.qits.projectsdaemon.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.projectsdaemon.ContractSeams;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-idp contract</b> (ticket qits-1149): the daemon's real token
 * mint ({@code ControlSocket.authorization}) against a pact mock server, one per row, and the
 * committed pact file {@code pacts/qits-projects-daemon_qits-idp-service.json}.
 */
class IdpConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Test
  void theDialHomeMintsABearerFromTheCommissionedClient() {
    mint(IdpContract.DIAL_HOME);
  }

  @Test
  void theBootCloneMintsABearerFromTheCommissionedClient() {
    mint(IdpContract.BOOT_CLONE);
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    IdpContract.PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    IdpContract.PACT.assertEveryInteractionCarriesBothReferences();
  }

  private static void mint(GoldenInteraction row) {
    IdpContract.PACT.run(
        row,
        (url, recorded) -> {
          Map<String, String> params = recorded.params();
          Optional<String> bearer =
              ContractSeams.mint(
                  url + "/idp/token",
                  params.get("clientId"),
                  params.get("clientSecret"),
                  params.get("audience"));
          assertEquals(
              Optional.of(
                  "Bearer "
                      + IdpContract.IDP
                          .json(row.state(), row.operationId())
                          .path("access_token")
                          .asText()),
              bearer);
        });
  }
}
