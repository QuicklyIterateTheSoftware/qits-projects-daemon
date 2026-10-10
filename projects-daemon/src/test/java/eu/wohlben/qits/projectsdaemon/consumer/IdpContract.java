package eu.wohlben.qits.projectsdaemon.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.projectsdaemon.ContractSeams;
import eu.wohlben.qits.projectsdaemon.consumer.GoldenMasters.Provider;
import eu.wohlben.qits.projectsdaemon.consumer.GoldenMasters.Request;
import eu.wohlben.qits.projectsdaemon.consumer.GoldenMasters.Trigger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * <b>What the daemon asks qits-idp, and why</b> (ticket qits-1149). Its only REST call to another
 * qits service: {@code ControlSocket.authorization} mints a machine token from the commissioned
 * client, {@code POST qits.projects-daemon.auth-token-url} ({@code .../idp/token}), form-encoded,
 * {@code client_credentials} with the id and secret in the body ({@code client_secret_post}, no
 * Basic header) and an {@code audience}. It reads the status (anything but 2xx is a failure) and
 * {@code access_token}, nothing else.
 *
 * <p>Two triggers press it, each with its own audience: the control socket's dial-home (at boot and
 * on every reconnect, audience qits-projects) and the boot clone of the project checkout (audience
 * qits-githost). Neither runs when the container was handed a project token.
 *
 * <p><b>qits-idp-service records no golden masters yet</b>, so every row is skipped with the
 * provider state it needs, and no pact file is committed for qits-idp until the recordings are
 * published and pinned. The state name and its params ({@code clientId}, {@code clientSecret},
 * {@code audience}) are the ones qits-platform-access-cli asked for the same grant.
 */
final class IdpContract {

  static final Provider PROVIDER = new Provider("qits-idp-service", "qits-idp");

  static final String TOKEN = "token";

  static final String A_COMMISSIONED_CLIENT = "a commissioned client";

  /** One (trigger, call). */
  record Case(Trigger trigger, String state, String operationId, Set<String> consumes) {

    String description() {
      return GoldenMasters.description(operationId, trigger);
    }

    String pending() {
      return "needs provider state '"
          + state
          + "' for "
          + operationId
          + " in qits-idp-service (it publishes no golden masters yet)";
    }
  }

  /**
   * The form the daemon posts, built from the state's client and audience — the daemon is
   * configured with exactly those, so this is what it sends.
   */
  static Request grant(Map<String, String> params) {
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/x-www-form-urlencoded");
    String form =
        "grant_type=client_credentials&client_id="
            + encode(params.get("clientId"))
            + "&client_secret="
            + encode(params.get("clientSecret"))
            + "&audience="
            + encode(params.get("audience"));
    return new Request("POST", "/idp/token", Map.of(), headers, form);
  }

  /** The real mint against {@code baseUrl}, asserting the bearer it makes of the answer. */
  static void mintAndRead(String baseUrl, JsonNode recorded, Map<String, String> params)
      throws Exception {
    Optional<String> bearer =
        ContractSeams.mint(
            baseUrl + "/idp/token",
            params.get("clientId"),
            params.get("clientSecret"),
            params.get("audience"));
    assertEquals(Optional.of("Bearer " + recorded.path("access_token").asText()), bearer);
  }

  static final List<Case> CASES =
      List.of(
          new Case(
              Trigger.schedule("ControlSocket.connect (dial home, at boot and on every reconnect)"),
              A_COMMISSIONED_CLIENT,
              TOKEN,
              Set.of("$.access_token")),
          new Case(
              Trigger.schedule("ControlSocket.startProvisioning (the boot clone from qits-githost)"),
              A_COMMISSIONED_CLIENT,
              TOKEN,
              Set.of("$.access_token")));

  private IdpContract() {}

  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(GoldenMasters.CONSUMER, PROVIDER.repository(), PactSpecVersion.V4);
    for (Case c : cases) {
      GoldenMasters.interaction(
          builder,
          PROVIDER,
          c.state(),
          c.operationId(),
          c.trigger(),
          grant(GoldenMasters.params(PROVIDER, c.state())),
          UnaryOperator.identity(),
          c.consumes());
    }
    return builder.toPact();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
