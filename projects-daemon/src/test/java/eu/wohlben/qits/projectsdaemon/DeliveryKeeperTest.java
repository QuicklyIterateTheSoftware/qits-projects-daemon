package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What remains of the delivery desk in the daemon (qits-1597): the delivery settings. Nothing here
 * ticks — the front desk's launch with the delivery rights is {@link FrontDeskKeeperTest}'s, and
 * turns typed into it are {@link FrontDeskInboxTest}'s. The loop's brief is no longer a file this
 * daemon writes (qits-1734); see {@code DeskBrief} in projects-daemon-protocol.
 */
class DeliveryKeeperTest {

  private static final String TOKEN = "delivery-token-value";

  // --- the settings ---------------------------------------------------------------------------

  @Test
  void settingsParseLeniently() {
    assertFalse(parse(null).enabled());
    assertFalse(parse("off").enabled());
    assertFalse(parse("sometimes").enabled(), "a typo is off");
    assertTrue(parse("on").enabled());
    assertTrue(parse("ON").enabled());
    assertTrue(parse("on").runnable());
    assertFalse(DeliveryKeeper.Settings.OFF.runnable());
    assertFalse(
        DeliveryKeeper.Settings.parse(Optional.of("on"), Optional.of(" "), Optional.empty())
            .runnable(),
        "on without a token holds no rights");
  }

  private static DeliveryKeeper.Settings parse(String delivery) {
    return DeliveryKeeper.Settings.parse(
        Optional.ofNullable(delivery), Optional.of(TOKEN), Optional.empty());
  }
}
