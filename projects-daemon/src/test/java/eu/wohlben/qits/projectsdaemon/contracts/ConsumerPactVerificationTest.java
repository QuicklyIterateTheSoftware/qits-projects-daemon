package eu.wohlben.qits.projectsdaemon.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Verifies every consumer's pact against the running daemon API</b> (ticket qits-1149), the way
 * qits-edge-service and qits-projects-service do.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-projects-daemon.json} (repository names on both sides), this repo
 * pins that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases
 * a changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>No consumer pins a pact yet</b> (qits-projects-service is the one expected), so {@code
 * @IgnoreNoPactsToVerify} lets an empty classpath pass and the loader logs that nothing was
 * verified. When the first consumer's pact jar is pinned, drop the annotation and set {@link
 * ClasspathPactLoader#REQUIRED} to true.
 *
 * <p>Plain JUnit, no Quarkus application: the API is a raw Vert.x server the state starts on its own
 * ({@link ProviderStates}). Each interaction gets a fresh daemon in the state it names, over real
 * HTTP on loopback. {@link #target} fails an unknown state, and an interaction without {@code
 * comments.references.qits-call} or {@code qits-trigger}.
 */
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name. */
  static final String PROVIDER = "qits-projects-daemon";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  private final ProviderStates states = new ProviderStates();

  @TempDir Path root;

  private ProviderStates.Setup running;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // no pact to verify: @IgnoreNoPactsToVerify's single empty run
    }
    String consumer = pact.getConsumer().getName();
    List<ProviderState> named = interaction.getProviderStates();
    if (named.size() != 1) {
      fail(
          "Consumer '"
              + consumer
              + "' interaction '"
              + interaction.getDescription()
              + "' names "
              + named.size()
              + " provider states; each daemon interaction names exactly one");
    }
    String state = named.get(0).getName();
    if (!states.names().contains(state)) {
      fail(
          "Consumer '"
              + consumer
              + "' needs the provider state '"
              + state
              + "' (interaction '"
              + interaction.getDescription()
              + "'), which qits-projects-daemon does not answer for — it answers for "
              + states.names());
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    running = states.setUp(state, root);
    context.setTarget(new HttpTestTarget("127.0.0.1", running.api().port()));
  }

  @AfterEach
  void stop() {
    if (running != null) {
      running.close();
      running = null;
    }
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    if (context != null) {
      context.verifyInteraction();
    }
  }

  // --- the states: the daemon is already started in @BeforeEach; these hand back the params ----

  @State(ProviderStates.A_DAEMON_WHOSE_HARNESSES_WERE_PROBED)
  Map<String, String> aDaemonWhoseHarnessesWereProbed() {
    return running == null ? Map.of() : running.params();
  }
}
