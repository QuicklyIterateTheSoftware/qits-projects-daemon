package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * A line of {@code projects-daemon}'s own output or a structured event, streamed home so a crashing
 * or misbehaving container is visible in qits without {@code docker logs}.
 */
public record DaemonLog(String level, String message) implements DaemonMessage {}
