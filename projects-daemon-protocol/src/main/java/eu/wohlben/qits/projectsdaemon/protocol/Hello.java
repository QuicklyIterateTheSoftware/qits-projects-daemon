package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * The first frame {@code projects-daemon} sends on connect: its identity (read from container env
 * qits-projects injected) plus its {@link DaemonProtocol#CAPABILITY_VERSION} and its build
 * identity. The backend registers the connection keyed by {@code projectId} and replies with {@link
 * Ack}.
 *
 * <p>{@code repoName} is the wrapper repository the container checked out — a project agent has no
 * branch claim, so there is nothing else to announce about the tree.
 *
 * <p>{@code daemonVersion}/{@code daemonBuildTime} are the binary's own release identity, baked
 * into the native image at build time (Maven {@code project.version} + {@code
 * maven.build.timestamp}, see {@code projects-daemon/pom.xml}). Together they distinguish numbered
 * releases and floating pre-release builds sharing one {@code -SNAPSHOT} version. Both are optional
 * on the wire: an older image sends {@code null} and the backend records the connection all the
 * same. {@code daemonBuildTime} is an ISO-8601 instant string ({@code yyyy-MM-dd'T'HH:mm:ss'Z'}).
 */
public record Hello(
    String projectId,
    String repoName,
    int capabilityVersion,
    String daemonVersion,
    String daemonBuildTime)
    implements DaemonMessage {}
