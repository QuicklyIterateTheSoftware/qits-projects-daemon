package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentDefaults;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.WorkLinkProjects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Resolves the agent preferences a launch falls back on when the request does not state them, and
 * answers the two questions the configuration epic added to this seam: what this container was
 * created to run its surfaces as, and what it knows about itself.
 *
 * <p>Order is <em>request parameter &gt; daemon configuration</em>. The request half lives in {@link
 * AgentDefaults#resolve}; this supplies the other one.
 *
 * <p>The workspace daemon had a third step between them: the checkout's own {@code
 * .qits-config.yml}. That file is a <em>repository's</em> declaration — its actions, its services,
 * its bootstrap chain — and a project agent's checkout is a wrapper holding none of its own. The
 * config reader went with the rest of that machinery (AGENTS.md, "What was trimmed"), so a
 * preference here comes from configuration or from the request, and nowhere else.
 *
 * <p><b>The document is read once, at boot, by {@link ControlSocket}</b> — the daemon's single
 * configuration reader — and handed in here. Reading it per launch would put file IO on the launch
 * path for a value that cannot change: a container keeps what it was born with, and an edit applies
 * to the next container.
 */
final class DaemonAgentDefaults implements AgentDefaults {

  private final AgentType daemonDefault;
  private final boolean activityTrackingDefault;
  private final AgentSurfaceConfigurations surfaces;
  private final Map<String, String> ambientFacts;
  private final Optional<String> platformDomain;
  private final WorkLinkProjects workLinkProjects;

  /** The pre-configuration shape, for a caller that has no document to hand — every test here. */
  DaemonAgentDefaults(Optional<String> daemonDefault, boolean activityTrackingDefault) {
    this(daemonDefault, activityTrackingDefault, AgentSurfaceConfigurations.shipped(), Map.of());
  }

  /** The pre-qits-1204 shape: no domain and nothing to fetch the estate's slugs from. */
  DaemonAgentDefaults(
      Optional<String> daemonDefault,
      boolean activityTrackingDefault,
      AgentSurfaceConfigurations surfaces,
      Map<String, String> ambientFacts) {
    this(daemonDefault, activityTrackingDefault, surfaces, ambientFacts, Optional.empty(), null);
  }

  /**
   * The qits-1204 shape: adds what {@link #platformDomain()} and {@link #workLinkProjects()} answer.
   * {@code workLinkProjects} is nullable — a caller with nothing to fetch with (the two constructors
   * above) passes none, and {@link #workLinkProjects()} then answers the library's own
   * entity-derived default.
   */
  DaemonAgentDefaults(
      Optional<String> daemonDefault,
      boolean activityTrackingDefault,
      AgentSurfaceConfigurations surfaces,
      Map<String, String> ambientFacts,
      Optional<String> platformDomain,
      WorkLinkProjects workLinkProjects) {
    this.daemonDefault = AgentType.parse(daemonDefault.orElse(null)).orElse(AgentType.CLAUDE);
    this.activityTrackingDefault = activityTrackingDefault;
    this.surfaces = surfaces == null ? AgentSurfaceConfigurations.shipped() : surfaces;
    this.ambientFacts = ambientFacts == null ? Map.of() : Map.copyOf(ambientFacts);
    this.platformDomain =
        platformDomain == null
            ? Optional.empty()
            : platformDomain.map(String::trim).filter(value -> !value.isBlank());
    this.workLinkProjects = workLinkProjects;
  }

  @Override
  public AgentType defaultAgentType() {
    return daemonDefault;
  }

  @Override
  public boolean activityTrackingEnabled() {
    return activityTrackingDefault;
  }

  /**
   * Empty: nothing in this container refines a prompt. {@code PromptRefinementService} is the
   * workspace daemon's — a project agent drafts epics through the qits MCP server's tools, not
   * through a short non-interactive harness call — so the setting the library carries for that flow
   * has no reader here, and answering the harness's own choice is the honest empty rather than a
   * model name nobody would use.
   */
  @Override
  public Optional<String> refinementModel() {
    return Optional.empty();
  }

  /** What this container was created to run its surfaces as; the shipped constants without one. */
  @Override
  public AgentSurfaceConfigurations surfaceConfigurations() {
    return surfaces;
  }

  /**
   * What this container knows about itself, for a surface's initial-prompt template.
   *
   * <p>Two of the seven names, and no others: this container serves one <b>project</b> and has one
   * <b>repository</b> checked out (the wrapper). {@code branch} and {@code commit} are the
   * checkout's and the library fills them from {@link ProjectContext}; {@code epic}, {@code
   * workspace} and {@code ticket} are a workspace container's facts and are left unresolved here, so
   * a template naming one renders it literally — which is exactly what it should do, because a
   * project agent's surfaces have no epic and no ticket to be about.
   */
  @Override
  public Map<String, String> ambientFacts() {
    return ambientFacts;
  }

  /**
   * The platform domain ({@code QITS_DOMAIN}), as {@link ControlSocket} read it; blank or absent
   * answers empty, same as the library's own default — see qits-1204.
   */
  @Override
  public Optional<String> platformDomain() {
    return platformDomain;
  }

  /**
   * The library's own entity-derived slug, unioned with whatever {@link #workLinkProjects} has
   * cached from the estate — empty before its first fetch succeeds, or when this container was
   * built without one (the two older constructors). Sorted and distinct, like {@link
   * WorkLinkProjects#slugs()} itself (qits-1204).
   */
  @Override
  public List<String> workLinkProjects() {
    TreeSet<String> union = new TreeSet<>(AgentDefaults.super.workLinkProjects());
    if (workLinkProjects != null) {
      union.addAll(workLinkProjects.slugs());
    }
    return List.copyOf(union);
  }

  /**
   * The facts a project agent container has, with the blanks left out — a blank value reads as
   * absent to the templater anyway, and omitting it keeps the map honest about what is known.
   */
  static Map<String, String> factsOf(String projectId, String repoName) {
    Map<String, String> facts = new LinkedHashMap<>();
    if (projectId != null && !projectId.isBlank()) {
      facts.put("project", projectId);
    }
    if (repoName != null && !repoName.isBlank()) {
      facts.put("repository", repoName);
    }
    return Map.copyOf(facts);
  }
}
