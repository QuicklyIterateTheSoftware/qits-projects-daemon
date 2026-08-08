package eu.wohlben.qits.projectsdaemon.agents;

/**
 * How an agent session's MCP server is scoped — a first-class launch parameter, resolved to a
 * scoped server URL by {@link AgentLaunchService}.
 *
 * <ul>
 *   <li>{@link #PROJECT} — the {@code repository} server scoped to the whole project, with no
 *       narrowing, so the session can drive every repository in it. The default shape for a project
 *       agent.
 *   <li>{@link #REPOSITORY} — the same server narrowed to one repository, for driving a single
 *       repository from within the wrapper checkout.
 * </ul>
 *
 * <p>The workspace daemon had a third value, {@code ACTIONS}, pairing the {@code actions} server
 * with a narrowed {@code repository} one. It is gone here along with the {@code actions} and {@code
 * observability} servers: a project agent addresses qits-projects and nothing else (see {@link
 * McpEndpoints}), so a scope naming a server this daemon cannot reach would only fail at launch.
 */
public enum AgentMcpScope {
  PROJECT,
  REPOSITORY
}
