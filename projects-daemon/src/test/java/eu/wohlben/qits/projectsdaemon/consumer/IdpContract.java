package eu.wohlben.qits.projectsdaemon.consumer;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;

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
 * <p>The request comes from qits-idp's recording of state {@code a commissioned client}: the form
 * with the state's {@code clientId}, {@code clientSecret} and {@code audience}. The token is opaque,
 * so the pact matches it by type.
 */
final class IdpContract {

  static final String CONSUMER = "qits-projects-daemon";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String A_COMMISSIONED_CLIENT = "a commissioned client";

  static final GoldenInteraction DIAL_HOME =
      GoldenInteraction.of(
              Trigger.schedule("ControlSocket.connect (dial home, at boot and on every reconnect)"),
              A_COMMISSIONED_CLIENT,
              "issueToken")
          .consumes("access_token");

  static final GoldenInteraction BOOT_CLONE =
      GoldenInteraction.of(
              Trigger.schedule("ControlSocket.startProvisioning (the boot clone from qits-githost)"),
              A_COMMISSIONED_CLIENT,
              "issueToken")
          .consumes("access_token");

  static final ConsumerPact PACT = ConsumerPact.of(CONSUMER, IDP, DIAL_HOME, BOOT_CLONE);

  private IdpContract() {}
}
