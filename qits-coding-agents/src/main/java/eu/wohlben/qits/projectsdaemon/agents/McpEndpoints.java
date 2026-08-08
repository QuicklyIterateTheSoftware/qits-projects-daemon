package eu.wohlben.qits.projectsdaemon.agents;

/**
 * Where the agent reaches qits' MCP server from inside this container, and which project it is
 * scoped to.
 *
 * <p>Both answers used to take work. On the qits host the URL came from a resolver that probed
 * {@code /proc/version} and opened a UDP socket, because a container cannot reach qits on {@code
 * localhost} and the right address differs between a shared docker network, plain Linux docker, and
 * WSL2. The project id came from a database lookup in its own transaction.
 *
 * <p>Inside the container the project id is one of the environment values every agent container is
 * created with, and the address is derivable: the control socket and the {@code repository} MCP
 * server are <em>the same service</em>, qits-projects, so its authority is the one the daemon was
 * already handed. The implementation lives in the daemon module because both parts come from
 * configuration; see {@code DaemonMcpEndpoints}.
 */
public interface McpEndpoints {

  /**
   * The base URL of one named MCP server, e.g. {@code http://qits-projects:8080/projects/mcp} for
   * {@code repository}. Callers append their own scope query parameters.
   *
   * <p>Throws rather than inventing a base for a server it has no address for: a fabricated URL
   * fails as a 404 the agent silently reports as a missing tool, which is indistinguishable from a
   * successful launch.
   */
  String mcpUrl(String server);

  /** The project this container serves. */
  String projectId();
}
