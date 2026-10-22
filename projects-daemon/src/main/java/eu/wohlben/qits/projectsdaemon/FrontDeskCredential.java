package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import java.util.Map;
import java.util.Optional;

/**
 * The one place that decides the credential the front desk's session is launched with (qits-1440,
 * qits-1482).
 *
 * <p>The front desk and the delivery desk are one session (owner ruling, 2026-10-22): the delivery
 * loop runs as turns in the {@code project.work} front desk, so that session holds the desk's
 * delivery rights while the loop is on. Where those rights come from depends on what qits sent
 * ({@link SessionCredentialStore}):
 *
 * <ul>
 *   <li><b>Per-session mode</b> — qits sent {@code sessionCredentials}: the {@code front-desk}
 *       credential, whatever it is. qits puts the desk's delivery token under that key while the
 *       loop is on and an ordinary desk-session token otherwise, so the daemon reads neither {@code
 *       QITS_DELIVERY_TOKEN} nor the switch to choose. A missing key fails closed.
 *   <li><b>Legacy mode</b> — an older qits, no frame after the grace window: exactly what qits-1440
 *       shipped. Delivering (delivery on <em>and</em> {@code QITS_DELIVERY_TOKEN} set), the delivery
 *       token as {@code QITS_TOKEN}; otherwise the container's own token.
 * </ul>
 *
 * <p>Either way the overlay starts from {@link DaemonSecrets#SCRUB}, so the session inherits no
 * daemon secret and holds its credential under the spelling it reads ({@code QITS_TOKEN}, which the
 * library also makes the bearer on the launch's platform MCP servers).
 *
 * <p>{@link FrontDeskKeeper} asks this and nothing else, before every launch and on every poll (to
 * notice a changed credential); no other launch path consults it, so a {@code project.work} session
 * a person starts from the desk panel runs on {@code desk-agents} like any other browser launch.
 */
final class FrontDeskCredential {

  /** Delivery off and no credentials ever sent: the front desk runs as the container, scrubbed. */
  static final FrontDeskCredential SCRUBBED =
      new FrontDeskCredential(DeliveryKeeper.Settings.OFF, SessionCredentialStore.withoutOwnToken());

  private final DeliveryKeeper.Settings settings;
  private final SessionCredentialStore credentials;

  FrontDeskCredential(DeliveryKeeper.Settings settings, SessionCredentialStore credentials) {
    this.settings = settings;
    this.credentials = credentials;
  }

  /** Whether the delivery switch is on — the loop is wanted, whether or not it can run yet. */
  boolean deliveryOn() {
    return settings.enabled();
  }

  /**
   * Whether the front desk runs the delivery loop now: delivery on, and rights to run it with — the
   * {@code front-desk} credential once qits sent credentials, the delivery token before that.
   */
  boolean delivering() {
    return settings.enabled() && (credentials.perSessionMode() || settings.runnable());
  }

  /**
   * The front desk launch's environment overlay: {@link DaemonSecrets#SCRUB}, then the credential
   * this class's javadoc describes.
   *
   * @throws SessionCredentialStore.AwaitingSessionCredentialsException inside the grace window
   * @throws SessionCredentialStore.MissingSessionCredentialException in per-session mode without a
   *     {@code front-desk} credential
   */
  Map<String, String> launchOverlay() {
    return DaemonSecrets.overlay(
        Map.of(),
        credentials.overlayFor(
            SessionCredentials.FRONT_DESK_KEY,
            () ->
                settings.runnable()
                    ? Optional.of(
                        DaemonSecrets.credential(settings.token(), settings.tokenSubject()))
                    : Optional.empty()));
  }

  /** Whether qits has sent credentials, so a changed one is a reason to restart the session. */
  boolean perSessionMode() {
    return credentials.perSessionMode();
  }
}
