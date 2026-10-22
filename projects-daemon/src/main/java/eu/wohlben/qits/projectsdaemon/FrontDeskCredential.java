package eu.wohlben.qits.projectsdaemon;

import java.util.HashMap;
import java.util.Map;

/**
 * The one place that decides the credential the front desk's session is launched with (qits-1440).
 *
 * <p>The front desk and the delivery desk are one session (owner ruling, 2026-10-22): the delivery
 * loop runs as turns in the {@code project.work} front desk, so that session holds the desk's
 * delivery rights. When delivery is on <em>and</em> a delivery token is set, {@link #launchOverlay}
 * hands the session the delivery token as its {@code QITS_TOKEN} — which the CLI reads, and which
 * the library also makes the bearer on that launch's platform MCP servers — so it acts as {@code
 * qits:desk}. Otherwise it is {@link DeliveryCredentials#SCRUB}, exactly what every other launch
 * carries.
 *
 * <p>{@link FrontDeskKeeper} asks this and nothing else, before every launch; no other launch path
 * consults it, so a {@code project.work} session a person starts from the desk panel is scrubbed
 * like any other browser launch. qits-1482 swaps the transport — a control-socket {@code
 * sessionCredentials} frame instead of the environment — and touches only this class.
 */
final class FrontDeskCredential {

  /** Delivery off: the front desk is launched like every other child, scrubbed. */
  static final FrontDeskCredential SCRUBBED = new FrontDeskCredential(DeliveryKeeper.Settings.OFF);

  private final DeliveryKeeper.Settings settings;

  FrontDeskCredential(DeliveryKeeper.Settings settings) {
    this.settings = settings;
  }

  /** Whether the front desk holds the delivery rights: delivery on, and a token to hold them with. */
  boolean delivering() {
    return settings.runnable();
  }

  /**
   * The front desk launch's environment overlay. Delivering: the delivery token as {@code
   * QITS_TOKEN} and its subject as {@code QITS_TOKEN_SUBJECT} (a blank subject removes the
   * container's own rather than leaving it to name a different token), with the two delivery names
   * themselves still removed — the session holds the token under the spelling it reads, not under
   * the container's. Not delivering: {@link DeliveryCredentials#SCRUB}.
   */
  Map<String, String> launchOverlay() {
    if (!delivering()) {
      return DeliveryCredentials.SCRUB;
    }
    Map<String, String> overlay = new HashMap<>(DeliveryCredentials.SCRUB);
    overlay.put("QITS_TOKEN", settings.token());
    overlay.put("QITS_TOKEN_SUBJECT", settings.tokenSubject());
    return Map.copyOf(overlay);
  }
}
