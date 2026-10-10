package eu.wohlben.qits.projectsdaemon.consumer;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of the qits-idp contract</b> (ticket qits-1149): the daemon's real token
 * mint ({@code ControlSocket.authorization}) against a pact mock server, one per row. Every row is
 * skipped until qits-idp-service publishes golden masters for the state it names; then pin {@code
 * eu.wohlben.qits:qits-idp-golden-masters}, flip {@link #PROVIDER_RECORDED}, and add a pact-file
 * test and a {@code contracts: pacts:} entry in {@code .config/qits/release.yml}, as
 * qits-edge-service does for its qits-events pact.
 */
class IdpConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  /** Flip to true once qits-idp's golden masters are pinned. */
  static final boolean PROVIDER_RECORDED = false;

  @TestFactory
  Stream<DynamicTest> everyRowIsWhatTheDaemonAsksAndUnderstands() {
    return IdpContract.CASES.stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.description() + " [" + row.state() + "]",
                    () -> {
                      Assumptions.assumeTrue(PROVIDER_RECORDED, row.pending());
                      PactVerificationResult result =
                          ConsumerPactRunnerKt.runConsumerTest(
                              IdpContract.pact(List.of(row)),
                              MockProviderConfig.createDefault(PactSpecVersion.V4),
                              (mockServer, context) -> {
                                IdpContract.mintAndRead(
                                    mockServer.getUrl(),
                                    GoldenMasters.json(
                                        IdpContract.PROVIDER, row.state(), row.operationId()),
                                    GoldenMasters.params(IdpContract.PROVIDER, row.state()));
                                return null;
                              });
                      if (!(result instanceof PactVerificationResult.Ok)) {
                        fail(describe(result));
                      }
                    }));
  }

  static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
