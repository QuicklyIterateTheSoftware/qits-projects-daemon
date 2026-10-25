package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentConfigurationDocument;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What remains of the delivery desk in the daemon (qits-1597): the delivery settings and the loop's
 * brief. Nothing here ticks — the front desk's launch with the delivery rights is {@link
 * FrontDeskKeeperTest}'s, and turns typed into it are {@link FrontDeskInboxTest}'s.
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

  // --- the brief ------------------------------------------------------------------------------

  private static AgentSurfaceConfigurations document(String deliveryPrompt) {
    return AgentSurfaceConfigurations.of(
        AgentConfigurationDocument.parse(
            """
            {
              "version": 2,
              "generatedAt": "2026-10-22T10:00:00Z",
              "surfaces": [
                {
                  "configuration": {
                    "surface": "project.delivery",
                    "harness": "CLAUDE",
                    "model": "",
                    "effort": "",
                    "remoteControl": true,
                    "permissionMode": "SKIP_PERMISSIONS",
                    "activityTracking": true,
                    "systemPrompt": %s,
                    "initialPrompt": "",
                    "mcpServers": []
                  }
                }
              ]
            }
            """
                .formatted(deliveryPrompt),
            "test"));
  }

  @Test
  void theBriefIsTheDeliverySurfacesPrompt(@TempDir Path dir) throws Exception {
    Path brief = dir.resolve("qits-delivery-brief.md");
    DeliveryKeeper.writeBrief(brief, document("\"You are the delivery desk.\\nShip it.\""), "qits");

    assertEquals("You are the delivery desk.\nShip it.\n", Files.readString(brief));
    assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(brief)));
  }

  @Test
  void theBriefFallsBackWhenTheDocumentCarriesNone(@TempDir Path dir) throws Exception {
    Path brief = dir.resolve("qits-delivery-brief.md");

    DeliveryKeeper.writeBrief(brief, AgentSurfaceConfigurations.shipped(), "qits");
    String fallback = Files.readString(brief);
    assertTrue(fallback.contains("qits projects delivery --project qits"), fallback);
    assertTrue(fallback.contains("one pass"), fallback);

    DeliveryKeeper.writeBrief(brief, document("\"  \""), "qits");
    assertEquals(fallback, Files.readString(brief), "a blank prompt is no prompt");
  }

  @Test
  void theBriefIsWrittenWhereAPromptedPassLooksForIt() {
    assertEquals(Path.of("/tmp/qits-delivery-brief.md"), DeliveryKeeper.BRIEF_PATH);
  }
}
