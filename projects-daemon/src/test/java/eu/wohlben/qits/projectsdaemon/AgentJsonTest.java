package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentNotSignedInException;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The agent surface's two wire contracts: what a launch request may say, and what {@code GET
 * /agents/available} answers.
 *
 * <p>Both are asserted as literal keys and literal values on purpose. These bodies cross a
 * repository boundary — qits-projects deserializes them into its own DTOs and the SPA consumes those
 * — so a test that read the keys off the records would rename itself along with the bug.
 */
class AgentJsonTest {

  @Test
  void aLaunchNamesItsSurface() {
    AgentLaunchRequest request =
        AgentJson.launchRequest(
            new JsonObject().put("scope", "PROJECT").put("surface", "project.tickets"));

    assertEquals(AgentSurface.PROJECT_TICKETS, request.surface());
    assertEquals(AgentMcpScope.PROJECT, request.scope());
  }

  @Test
  void aMissingSurfaceIsLeftForTheLibraryToImply() {
    AgentLaunchRequest request =
        AgentJson.launchRequest(new JsonObject().put("scope", "PROJECT"));

    assertNull(request.surface(), "nothing is invented at the door");
    assertEquals(
        AgentSurface.PROJECT_EPICS,
        request.surfaceOrDefault(),
        "a PROJECT-scoped launch is this container's epics desk — the shape-implied guess, and a"
            + " dated migration crutch rather than a contract");
  }

  @Test
  void anUnknownSurfaceIsRefused() {
    InvalidCommandRequestException refused =
        assertThrows(
            InvalidCommandRequestException.class,
            () ->
                AgentJson.launchRequest(
                    new JsonObject().put("scope", "PROJECT").put("surface", "project.epicz")));

    assertTrue(
        refused.getMessage().contains("project.epicz"),
        "the message is what the API answers as a 400, so it names the value");
  }

  /**
   * The desk field is a wire-level compatibility mapping now: {@code AgentDesk} is gone from the
   * library, and these two names are translated here so a frontend that has not shipped the surface
   * keeps working for one release.
   */
  @Test
  void theDeskFieldStillMapsOntoItsSurface() {
    assertEquals(
        AgentSurface.PROJECT_EPICS,
        AgentJson.launchRequest(new JsonObject().put("scope", "PROJECT").put("desk", "EPICS"))
            .surface());
    assertEquals(
        AgentSurface.PROJECT_TICKETS,
        AgentJson.launchRequest(new JsonObject().put("scope", "PROJECT").put("desk", "TICKETS"))
            .surface());
    assertEquals(
        AgentSurface.PROJECT_TICKETS,
        AgentJson.launchRequest(new JsonObject().put("scope", "PROJECT").put("desk", "tickets"))
            .surface(),
        "case-insensitively, exactly as the enum parse was");
  }

  @Test
  void aSurfaceWinsOverTheDeskThatIsBeingReplaced() {
    AgentLaunchRequest request =
        AgentJson.launchRequest(
            new JsonObject()
                .put("scope", "PROJECT")
                .put("desk", "EPICS")
                .put("surface", "project.tickets"));

    assertEquals(
        AgentSurface.PROJECT_TICKETS,
        request.surface(),
        "a caller sending both is mid-migration and means the new key");
  }

  @Test
  void anUnknownDeskIsRefused() {
    assertThrows(
        InvalidCommandRequestException.class,
        () -> AgentJson.launchRequest(new JsonObject().put("scope", "PROJECT").put("desk", "TASKS")));
  }

  @Test
  void theRestOfTheLaunchStillArrives() {
    AgentLaunchRequest request =
        AgentJson.launchRequest(
            new JsonObject()
                .put("scope", "REPOSITORY")
                .put("mode", "INTERACTIVE")
                .put("initialContext", "hello")
                .put("resumeSessionId", "s-1")
                .put("fork", true)
                .put("deliverTaskPrompt", true)
                .put("agentType", "KIMI"));

    assertEquals(AgentMcpScope.REPOSITORY, request.scope());
    assertEquals(AgentLaunchMode.INTERACTIVE, request.mode());
    assertEquals("hello", request.initialContext());
    assertEquals("s-1", request.resumeSessionId());
    assertTrue(request.fork());
    assertTrue(request.deliverTaskPrompt());
    assertEquals(AgentType.KIMI, request.agentType());
  }

  @Test
  void availableCarriesTheHarnessesAndWhatTheyCanBeConfiguredWith() {
    HarnessCapabilities claude = HarnessCapabilities.shipped(AgentType.CLAUDE, "no binary here");

    JsonObject body =
        AgentJson.available(AgentType.CLAUDE, "2026.909.1", "project-agent/p-1", List.of(claude));

    assertEquals(new JsonArray().add("CLAUDE").add("KIMI"), body.getJsonArray("agents"));
    assertEquals("CLAUDE", body.getString("defaultAgent"));
    assertEquals("2026.909.1", body.getString("imageVersion"));
    assertEquals("project-agent/p-1", body.getString("reportedBy"));
    JsonObject reported = body.getJsonArray("capabilities").getJsonObject(0);
    assertEquals("CLAUDE", reported.getString("harness"));
    assertTrue(reported.getBoolean("probeFailed"), "the library's own object, unreshaped");
    assertTrue(reported.getBoolean("effortSupported"));
  }

  @Test
  void availableStillAnswersWhenNothingCouldBeProbed() {
    JsonObject body = AgentJson.available(AgentType.CLAUDE, null, null, List.of());

    assertEquals("", body.getString("imageVersion"), "blank, so the host fills it from its own pin");
    assertEquals("", body.getString("reportedBy"));
    assertTrue(
        body.getJsonArray("capabilities").isEmpty(),
        "an empty report is the absent case the host records nothing for — never a failure");
  }

  /**
   * The refusal body a signed-out platform answers with. Asserted as literal keys because that is
   * what it is: the frontend branches on {@code error}, and reading the case off {@code message}
   * instead would freeze a sentence written for a human — the same display-string-as-contract
   * mistake the " (tickets desk)" match was.
   */
  @Test
  void aRefusalNamesItselfWithAKeyRatherThanASentence() {
    AgentNotSignedInException refusal = new AgentNotSignedInException(AgentType.KIMI);

    JsonObject body = AgentJson.notSignedIn(refusal);

    assertEquals("not-signed-in", body.getString("error"), "the discriminator, and it is required");
    assertEquals("KIMI", body.getString("agentType"), "so the caller need not parse it out of prose");
    assertEquals(refusal.getMessage(), body.getString("message"));
    assertTrue(
        body.getString("message").contains("sign-in terminal"),
        "and the sentence still says what to do about it");
  }
}
