package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * A periodic liveness ping from {@code projects-daemon}, so the backend sees a silent-but-alive
 * container.
 */
public record Heartbeat(String projectId) implements DaemonMessage {}
