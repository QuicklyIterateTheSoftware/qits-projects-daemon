package eu.wohlben.qits.projectsdaemon.protocol;

/** A backend request for a {@link ProjectInfo} snapshot, correlated by {@code correlationId}. */
public record Describe(String correlationId) implements DaemonMessage {}
