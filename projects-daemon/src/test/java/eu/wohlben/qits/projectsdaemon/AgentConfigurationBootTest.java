package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentPermissionMode;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.InvalidAgentConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the container does with the two variables it was created with — and with each of the three
 * states they can be in.
 */
class AgentConfigurationBootTest {

  @TempDir Path tempDir;

  private static final String DOCUMENT =
      """
      {
        "version": 2,
        "generatedAt": "2026-09-09T10:00:00Z",
        "surfaces": [
          {
            "configuration": {
              "surface": "project.tickets",
              "harness": "KIMI",
              "model": "kimi-k2",
              "effort": "",
              "remoteControl": false,
              "permissionMode": "SKIP_PERMISSIONS",
              "activityTracking": true,
              "systemPrompt": "You are the front desk.",
              "initialPrompt": "Triage {{project}}.",
              "mcpServers": []
            }
          }
        ]
      }
      """;

  @Test
  void neitherVariableIsQuietAndFallsBackToTheShippedConstants() {
    AgentSurfaceConfigurations configurations =
        AgentConfigurationBoot.materialize(Optional.empty(), Optional.empty());

    assertFalse(
        configurations.documentPresent(),
        "a container created before this shipped is a real and permanent state, not a fault");
  }

  @Test
  void aDocumentIsWrittenWhereTheHostSaidAndReadBack() throws IOException {
    Path target = tempDir.resolve("qits").resolve("agent-configuration.json");

    AgentSurfaceConfigurations configurations =
        AgentConfigurationBoot.materialize(
            Optional.of(DOCUMENT), Optional.of(target.toString()));

    assertTrue(Files.exists(target), "the daemon materializes it — nothing else can");
    assertEquals(DOCUMENT.trim(), Files.readString(target).trim());
    assertEquals(
        AgentType.KIMI,
        configurations
            .resolve(AgentSurface.PROJECT_TICKETS, AgentType.CLAUDE, true)
            .harness(),
        "and the surface it configures runs as it says, not as the daemon's default");
    assertEquals(
        AgentPermissionMode.SKIP_PERMISSIONS,
        configurations.resolve(AgentSurface.PROJECT_TICKETS, AgentType.CLAUDE, true).permissionMode());
    assertEquals(
        AgentType.CLAUDE,
        configurations.resolve(AgentSurface.PROJECT_EPICS, AgentType.CLAUDE, true).harness(),
        "a surface the document does not mention still launches, on the shipped default");
  }

  @Test
  void aPathWithNoDocumentFailsTheBoot() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentConfigurationBoot.materialize(
                    Optional.empty(), Optional.of(tempDir.resolve("nothing.json").toString())));

    assertTrue(
        refused.getMessage().contains("Both travel together"),
        "half a contract must not read as 'this container was given no configuration'");
  }

  @Test
  void aDocumentWithNoPathFailsTheBoot() {
    assertThrows(
        IllegalStateException.class,
        () -> AgentConfigurationBoot.materialize(Optional.of(DOCUMENT), Optional.empty()));
  }

  @Test
  void aMalformedDocumentIsLoudAtBootAndNamesTheKey() {
    String broken = DOCUMENT.replace("\"KIMI\"", "\"CLOUDE\"");

    InvalidAgentConfigurationException refused =
        assertThrows(
            InvalidAgentConfigurationException.class,
            () ->
                AgentConfigurationBoot.materialize(
                    Optional.of(broken),
                    Optional.of(tempDir.resolve("agent-configuration.json").toString())));

    assertTrue(refused.getMessage().contains("harness"), refused.getMessage());
    assertTrue(refused.getMessage().contains("CLOUDE"), refused.getMessage());
  }

  @Test
  void aBlankPairIsTheSameAsAnAbsentOne() {
    assertFalse(
        AgentConfigurationBoot.materialize(Optional.of("  "), Optional.of(" "))
            .documentPresent(),
        "an injected-empty variable is how a deployment turns the injection off");
  }
}
