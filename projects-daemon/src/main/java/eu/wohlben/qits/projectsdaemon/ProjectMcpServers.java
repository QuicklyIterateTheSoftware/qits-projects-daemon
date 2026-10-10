package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentMcpServers;
import eu.wohlben.qits.agents.ScopedMcp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP servers a launch in a project agent container attaches: the central platform-access
 * server {@code qits}, and nothing else (qits-1174).
 *
 * <p>{@code qits} is scoped by the caller's own bearer, not by its url, so the launch scope changes
 * nothing. Its address is configured ({@code qits.platform-mcp.url}, {@code QITS_PLATFORM_MCP_URL})
 * and never derived: it is a separately deployed service. Without an address no server is attached.
 *
 * <p>Claude is launched with {@code --strict-mcp-config}, so this list is the whole set; the shared
 * {@code /claude-home} volume's own MCP entries are ignored.
 */
final class ProjectMcpServers implements AgentMcpServers {

  /** The key the central platform-access MCP server attaches under. */
  static final String PLATFORM_SERVER = "qits";

  /**
   * Its pre-approval. The library renders {@code mcp__qits__*} for this key on Claude anyway; kept so
   * a {@code ScopedMcp} for it never misstates what is approved.
   */
  static final List<String> QITS_TOOLS = List.of("mcp__qits__*");

  private final Optional<String> platformMcpUrl;

  /**
   * {@code Authorization: Bearer <token>}, or empty, computed once from {@code
   * qits.projects-daemon.token} (the same {@code QITS_TOKEN} {@code ControlSocket} dials home with).
   */
  private final Map<String, String> platformHeaders;

  ProjectMcpServers(Optional<String> platformMcpUrl, Optional<String> token) {
    this.platformMcpUrl =
        platformMcpUrl == null
            ? Optional.empty()
            : platformMcpUrl.map(String::trim).filter(value -> !value.isEmpty());
    this.platformHeaders = headersFor(token);
  }

  private static Map<String, String> headersFor(Optional<String> token) {
    return token == null
        ? Map.of()
        : token
            .filter(value -> !value.isBlank())
            .map(value -> Map.of("Authorization", "Bearer " + value.trim()))
            .orElse(Map.of());
  }

  /** {@code qits} when it has an address, else nothing — at every scope. */
  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    return platformMcpUrl
        .map(url -> List.of(new ScopedMcp(PLATFORM_SERVER, url, QITS_TOOLS, platformHeaders)))
        .orElse(List.of());
  }

  /**
   * {@code Authorization: Bearer <token>} when this container was handed a {@code QITS_TOKEN}, empty
   * otherwise. On a runner-placed container the agent reaches {@code qits} only through the edge,
   * which wants a credential on every call.
   */
  @Override
  public Map<String, String> platformHeaders() {
    return platformHeaders;
  }

  /**
   * True: {@code qits} takes no url narrowing, so there is none to ignore. Without this, the pinned
   * library notes on every configured launch that its narrowing was not honoured.
   */
  @Override
  public boolean honoursNarrowing() {
    return true;
  }
}
