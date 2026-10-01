package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.net.URI;
import java.util.Optional;

/**
 * Resolves the agent's MCP base URLs: {@code repository} at {@code /projects/mcp}, and the central
 * platform server {@code qits} (qits-630) at a wholly separate address this daemon is only ever
 * told, never derives.
 *
 * <p><b>Why there is no WARN here.</b> The workspace daemon derived three MCP hosts from its
 * control-socket authority and warned on every one of them, because the control socket was
 * qits-workspaces while the servers were qits-projects and qits-observability — three services, one
 * address, and the derivation only held where a single authority routed every segment. In this
 * daemon both ends are <b>qits-projects</b>: the control socket it dialled and the MCP server it
 * needs are the same service, so its authority is not a guess about anyone else's. That is a
 * property of the topology, not a convention, which is why it is stated once here and never warned
 * about at runtime.
 *
 * <p><b>In a deployment the address is stated, not derived.</b> qits-projects injects {@code
 * QITS_REPOSITORY_MCP_URL} (→ {@code qits.repository-mcp.url}) into every agent container it
 * creates, and that value wins here. The derivation below is the fallback: a container created
 * before that env existed, or a daemon run by hand. Keeping both is what makes the injection safe to
 * add — no running container had to be recreated for it.
 *
 * <p>The override is also what points an agent at a different qits instance, or at a separately
 * deployed MCP server if that ever splits out. Contrast {@link Provisioner}, whose git base
 * <em>is</em> a guess about another service and does warn.
 *
 * <p><b>{@code qits}, the central platform server (qits-630), is a third case: no derivation at
 * all.</b> {@code repository} can fall back to the control socket's own authority because the
 * control socket and that server are the same service; {@code qits} is a separately deployed
 * service with no relationship to this daemon's dial-home address, so there is nothing sound to
 * derive it from. It is configured or it is absent — never guessed.
 */
final class DaemonMcpEndpoints implements McpEndpoints {

  /** The one MCP server this daemon knows how to address, and its owning segment. */
  static final String REPOSITORY_SERVER = "repository";

  static final String REPOSITORY_SEGMENT = "/projects/mcp";

  /** The central platform-access MCP server (qits-630) — see the class javadoc. */
  static final String PLATFORM_SERVER = "qits";

  private final String httpBase;
  private final String projectId;
  private final Optional<String> repositoryOverride;
  private final Optional<String> platformOverride;

  /**
   * @param daemonUrl the control-socket URL, {@code ws://host:port/projects/daemon/<projectId>}
   * @throws IllegalStateException if {@code daemonUrl} carries no usable authority — a daemon
   *     without one never connected, so it cannot be serving agent launches either
   */
  DaemonMcpEndpoints(
      String daemonUrl,
      String projectId,
      Optional<String> repositoryOverride,
      Optional<String> platformOverride) {
    this.httpBase = httpBaseOf(daemonUrl);
    this.projectId = projectId;
    this.repositoryOverride = repositoryOverride;
    this.platformOverride = platformOverride;
  }

  /**
   * @throws InvalidCommandRequestException for a server name this daemon does not address, or for
   *     {@link #PLATFORM_SERVER} when it has no configured address. Deliberately not a silent
   *     fallback: a made-up base fails later as a 404 the agent reports as "tool unavailable", and
   *     the launch reads as having worked. {@code InvalidCommandRequestException} because it is the
   *     one {@link ProjectsApi} answers with the message attached (400) rather than swallowing into
   *     "Internal error".
   */
  @Override
  public String mcpUrl(String server) {
    if (REPOSITORY_SERVER.equals(server)) {
      return configured(repositoryOverride).orElse(httpBase + REPOSITORY_SEGMENT);
    }
    if (PLATFORM_SERVER.equals(server)) {
      // Thrown, never made up: ProjectMcpServers treats this as "not configured, attach nothing"
      // by catching it rather than letting it reach a launch, exactly as it already does for a key
      // (like "observability") this host never serves at all.
      return configured(platformOverride)
          .orElseThrow(
              () ->
                  new InvalidCommandRequestException(
                      "No address for the 'qits' platform MCP server: set"
                          + " qits.platform-mcp.url (QITS_PLATFORM_MCP_URL). There is no segment to"
                          + " derive it from — it is a separately deployed service, not qits-projects."));
    }
    throw new InvalidCommandRequestException(
        "Unknown MCP server '"
            + server
            + "': this daemon addresses "
            + REPOSITORY_SERVER
            + " and "
            + PLATFORM_SERVER
            + " only.");
  }

  @Override
  public String projectId() {
    return projectId;
  }

  private static Optional<String> configured(Optional<String> override) {
    return override.map(String::trim).filter(value -> !value.isEmpty());
  }

  /**
   * The authority of the dial-home url with an http scheme — {@code
   * ws://qits-projects:8080/projects/daemon/x} → {@code http://qits-projects:8080}. The <b>path</b>
   * is discarded and {@link #REPOSITORY_SEGMENT} appended in its place: the control-socket path
   * addresses the socket, not the MCP server, even though the same service serves both.
   */
  static String httpBaseOf(String daemonUrl) {
    if (daemonUrl == null || daemonUrl.isBlank()) {
      throw new IllegalStateException(
          "No qits.projects-daemon.url configured — the agent MCP endpoint cannot be derived");
    }
    URI uri = URI.create(daemonUrl.trim());
    String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalStateException("Malformed qits.projects-daemon.url: " + daemonUrl);
    }
    return uri.getPort() < 0 ? scheme + "://" + host : scheme + "://" + host + ":" + uri.getPort();
  }
}
