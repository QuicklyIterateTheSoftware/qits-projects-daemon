package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentMcpIds;
import eu.wohlben.qits.agents.AgentMcpNarrowing;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentMcpServers;
import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.agents.ScopedMcp;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.util.List;
import java.util.Optional;

/**
 * This daemon's scope→server mapping: which MCP servers a launch in a project agent container
 * attaches, already narrowed, with what is pre-approved on each.
 *
 * <p><b>The mapping is the host's, not the library's.</b> It used to be a {@code switch} inside
 * {@code AgentLaunchService}, once in this repository and once in qits-workspace-daemon, and the two
 * switches did not vary in detail — they described two different products. This one attaches
 * <strong>exactly one</strong> server, {@code repository}, served by qits-projects: the one carrying
 * the epic and ticket tools a project agent container exists for. The workspace daemon attaches
 * three ({@code actions}, {@code repository}, {@code observability}) across three services; none of
 * the other two is wired here, and that is a decision rather than an omission — this agent's job is
 * the project's <em>plan</em>, not a workspace's actions or another service's telemetry, and a
 * server it has no business calling is a tool it can waste a turn on. {@link AgentMcpScope#ACTIONS}
 * is therefore unreachable from this host, and asking for it is a refused launch rather than a
 * silent fall-through to the repository server.
 *
 * <p>Nothing else can put a server back either: Claude is launched with {@code
 * --strict-mcp-config}, so the {@code --mcp-config} rendered from this list is the whole set and the
 * shared {@code /claude-home} volume's own MCP entries are ignored. Kimi gets a launch-local {@code
 * mcp.json} in a throwaway home for the same effect.
 *
 * <p>The pre-approval list travels with the mapping for the same reason the mapping is here: it is
 * not a property of the harness but a policy about what this product's agents may do without asking.
 */
final class ProjectMcpServers implements AgentMcpServers {

  /**
   * The read-only tools of the {@code repository} MCP server, pre-approved so the session can list
   * and inspect without a permission prompt. The mutating tools are left out so the agent still
   * prompts before changing anything. Names are the agent's MCP tool ids: {@code
   * mcp__<server>__<tool>}.
   *
   * <p>The server carries three surfaces, so this list does too: the repository tools, the epic ones
   * a refinement session drafts through, and the ticket ones the tickets desk triages through.
   * {@code list_epics} and {@code get_epic} are the survey the agent has to make before it can tell
   * "extend this draft" from "propose a new epic", and pre-approving them is the same call as
   * pre-approving {@code listRepositories}. {@code list_tickets} and {@code get_ticket} are the
   * exact parallel one surface down — the survey that tells "this is already filed" from "this is
   * new", made before every intake — and {@code get_ticket} returns the comment thread too, so the
   * whole conversation reads without a prompt.
   *
   * <p>Every <em>write</em> stays off the list and still prompts, on both surfaces and for the same
   * reason: {@code propose_epic} and the feature/task mutators change the project's plan, and {@code
   * create_ticket} / {@code update_ticket} / {@code transition_ticket} / the comment writers change
   * its record of work. Surveying is free; filing is not. This is where the two hosts differ most:
   * the workspace host pre-approves four named writes, because a dispatched agent is asked to use
   * them. A project agent is asked to plan, and planning is a prompt.
   *
   * <p>The snake_case half is not a slip: the epic and ticket tools declare those names on the
   * qits-projects side, and the id here must match the declared name character for character or the
   * pre-approval silently matches nothing.
   *
   * <p>The <em>order</em> is load-bearing, not cosmetic: it is rendered into one {@code
   * --allowedTools} argument that the library's suite asserts as a literal, against this same list
   * reproduced there as {@code ProjectHostMcpServers}.
   */
  static final List<String> READ_ONLY_REPOSITORY_TOOLS =
      List.of(
          "mcp__repository__listRepositories",
          "mcp__repository__listBranches",
          "mcp__repository__listCommits",
          "mcp__repository__listCommitChanges",
          "mcp__repository__getCommitFileDiff",
          "mcp__repository__listActions",
          "mcp__repository__taskPrompt",
          "mcp__repository__list_epics",
          "mcp__repository__get_epic",
          "mcp__repository__list_tickets",
          "mcp__repository__get_ticket");

  private final McpEndpoints endpoints;
  private final String repoName;

  ProjectMcpServers(McpEndpoints endpoints, String repoName) {
    this.endpoints = endpoints;
    this.repoName = repoName;
  }

  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    String base = endpoints.mcpUrl(DaemonMcpEndpoints.REPOSITORY_SERVER);
    return switch (scope) {
      // Project scope, no repository narrowing: the session sees every repository in the project,
      // which is what a wrapper checkout is for.
      case PROJECT ->
          List.of(
              new ScopedMcp(
                  DaemonMcpEndpoints.REPOSITORY_SERVER,
                  base + "?projectId=" + projectId(),
                  READ_ONLY_REPOSITORY_TOOLS));
      // Narrowed to the one repository this container checked out, so a per-repository session
      // does not see its siblings.
      case REPOSITORY ->
          List.of(
              new ScopedMcp(
                  DaemonMcpEndpoints.REPOSITORY_SERVER,
                  base + "?projectId=" + projectId() + "&repositoryId=" + repositoryId(),
                  READ_ONLY_REPOSITORY_TOOLS));
      // Not reachable from this host: there is no actions server on the project's segment, so a
      // launch that asks for it must be refused rather than quietly served the repository one.
      case ACTIONS ->
          throw new InvalidCommandRequestException("Scope ACTIONS is not served by this daemon");
    };
  }

  /**
   * One server by key, narrowed exactly as the surface's configuration asks — the seam the
   * configuration epic added, implemented here rather than left on its default.
   *
   * <p>The default implementation answers the scope's own mapping and ignores the narrowing, which
   * keeps an unadopted daemon rendering what it always rendered at the price of every launch
   * recording that its addressing was the host's rather than the document's. This daemon does not
   * stay on it: the three narrowing checkboxes in the editor mean what they say here.
   *
   * <p>Three rules, all of them the seam's:
   *
   * <ul>
   *   <li>the query parameters render in the canonical {@code projectId}, {@code repositoryId},
   *       {@code workspaceId} order, because the rendered command line is asserted as a literal on
   *       both harnesses;
   *   <li>every interpolated id goes through {@link AgentMcpIds#requireId} — the url ends up inside
   *       a single-quoted shell argument and the renderer does no escaping of its own;
   *   <li>a narrowing this container cannot satisfy is <b>refused</b>, never dropped. There is no
   *       workspace here, so {@code narrowWorkspace} on a project agent's server would otherwise
   *       quietly answer for the whole project — a session missing half its narrowing looks
   *       entirely normal and answers about things it was configured not to see.
   * </ul>
   */
  @Override
  public Optional<ScopedMcp> serverFor(
      String key, AgentMcpScope scope, AgentMcpNarrowing narrowing) {
    if (!DaemonMcpEndpoints.REPOSITORY_SERVER.equals(key)) {
      // The one key this host serves. Empty rather than an exception: the launch turns it into its
      // own refusal, naming the surface that asked.
      return Optional.empty();
    }
    // Null is "no narrowing asked", the same as an all-false one: the unscoped, platform-wide url.
    AgentMcpNarrowing asked =
        narrowing == null ? new AgentMcpNarrowing(false, false, false) : narrowing;
    if (asked.workspace()) {
      throw new InvalidCommandRequestException(
          "The repository MCP server cannot be narrowed to a workspace in a project agent"
              + " container: this container serves a project, not a workspace.");
    }
    StringBuilder url = new StringBuilder(endpoints.mcpUrl(DaemonMcpEndpoints.REPOSITORY_SERVER));
    String separator = "?";
    if (asked.project()) {
      url.append(separator).append("projectId=").append(projectId());
      separator = "&";
    }
    if (asked.repository()) {
      url.append(separator).append("repositoryId=").append(repositoryId());
    }
    return Optional.of(
        new ScopedMcp(
            DaemonMcpEndpoints.REPOSITORY_SERVER, url.toString(), READ_ONLY_REPOSITORY_TOOLS));
  }

  /** Yes: {@link #serverFor} builds the url the document asked for, or refuses. */
  @Override
  public boolean honoursNarrowing() {
    return true;
  }

  private String projectId() {
    return AgentMcpIds.requireId(endpoints.projectId(), "project id");
  }

  private String repositoryId() {
    return AgentMcpIds.requireId(repoName, "repository id");
  }
}
