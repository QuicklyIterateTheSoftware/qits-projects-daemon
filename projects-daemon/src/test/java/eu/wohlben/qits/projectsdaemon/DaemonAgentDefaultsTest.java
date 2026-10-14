package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.WorkLinkProjects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two answers qits-1204 added to {@link DaemonAgentDefaults}: {@link
 * DaemonAgentDefaults#platformDomain()} and {@link DaemonAgentDefaults#workLinkProjects()}.
 *
 * <p>This daemon never overrides {@code entityId()} — a project agent's container is about one
 * project, not one ticket — so {@code AgentDefaults.super.workLinkProjects()} is always empty here
 * and the union {@link DaemonAgentDefaults#workLinkProjects()} computes reduces to whatever {@link
 * WorkLinkProjects#slugs()} answers. These tests exercise that fetcher-empty path — the one every
 * container actually boots with before, or without, the estate's project list arrives — rather than
 * a successful fetch, which would need a live {@code projects.qits.<domain>} to answer.
 */
class DaemonAgentDefaultsTest {

  private static DaemonAgentDefaults withDomain(Optional<String> domain, WorkLinkProjects links) {
    return new DaemonAgentDefaults(
        Optional.empty(),
        true,
        AgentSurfaceConfigurations.shipped(),
        Map.of(),
        domain,
        links);
  }

  @Nested
  class PlatformDomain {

    @Test
    void readsTheDomainItWasGiven() {
      DaemonAgentDefaults defaults = withDomain(Optional.of("dev.qits.internal"), null);

      assertEquals(Optional.of("dev.qits.internal"), defaults.platformDomain());
    }

    @Test
    void trimsIt() {
      DaemonAgentDefaults defaults = withDomain(Optional.of("  dev.qits.internal  "), null);

      assertEquals(Optional.of("dev.qits.internal"), defaults.platformDomain());
    }

    @Test
    void blankReadsAsEmpty() {
      DaemonAgentDefaults defaults = withDomain(Optional.of("   "), null);

      assertEquals(Optional.empty(), defaults.platformDomain());
    }

    @Test
    void absentIsEmpty() {
      DaemonAgentDefaults defaults = withDomain(Optional.empty(), null);

      assertEquals(Optional.empty(), defaults.platformDomain());
    }

    @Test
    void theOlderConstructorsAnswerEmptyToo() {
      // The pre-qits-1204 shapes: a caller with no document to hand, or with one that never
      // learned about a domain. Both must still compile and both answer empty.
      assertEquals(Optional.empty(), new DaemonAgentDefaults(Optional.empty(), true).platformDomain());
      assertEquals(
          Optional.empty(),
          new DaemonAgentDefaults(
                  Optional.empty(), true, AgentSurfaceConfigurations.shipped(), Map.of())
              .platformDomain());
    }

    @Test
    void aNullOptionalIsTreatedAsAbsentRatherThanThrowing() {
      DaemonAgentDefaults defaults =
          new DaemonAgentDefaults(
              Optional.empty(), true, AgentSurfaceConfigurations.shipped(), Map.of(), null, null);

      assertEquals(Optional.empty(), defaults.platformDomain());
    }
  }

  @Nested
  class WorkLinkProjectsSlugs {

    @Test
    void withoutAFetcherFallsBackToTheLibraryDefault() {
      // The pre-qits-1204 shapes pass no WorkLinkProjects at all.
      DaemonAgentDefaults defaults =
          new DaemonAgentDefaults(
              Optional.empty(), true, AgentSurfaceConfigurations.shipped(), Map.of());

      // No entityId is ever set on this daemon's defaults, so the library's own default (the
      // project slug of entityId()) answers nothing either.
      assertEquals(List.of(), defaults.workLinkProjects());
    }

    @Test
    void aBlankDomainFetcherNeverHasAnythingCached() {
      // WorkLinkProjects treats a blank domain as "never fetch" and answers an empty cache
      // forever — no network call is made, so this is deterministic.
      WorkLinkProjects links = new WorkLinkProjects("", Optional::empty);
      DaemonAgentDefaults defaults = withDomain(Optional.empty(), links);

      assertEquals(List.of(), defaults.workLinkProjects());
    }

    @Test
    void aMissingBearerLeavesTheCacheEmptyWithoutTouchingTheNetwork() {
      // A non-blank domain, but a bearer supplier that never has a token: WorkLinkProjects's
      // refresh short-circuits before it would open a connection (see its own tests), so this is
      // the "container booted, but has no project token yet" shape and still deterministic here.
      WorkLinkProjects links = new WorkLinkProjects("dev.qits.internal", Optional::empty);
      DaemonAgentDefaults defaults = withDomain(Optional.of("dev.qits.internal"), links);

      assertEquals(List.of(), defaults.workLinkProjects());
    }

    @Test
    void theAnswerIsSortedAndDistinctEvenWhenEmpty() {
      DaemonAgentDefaults defaults = withDomain(Optional.empty(), null);

      List<String> slugs = defaults.workLinkProjects();

      assertTrue(slugs.isEmpty());
    }
  }
}
