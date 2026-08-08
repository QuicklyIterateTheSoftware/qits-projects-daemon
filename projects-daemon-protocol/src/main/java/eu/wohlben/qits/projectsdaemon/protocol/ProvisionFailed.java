package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * {@code projects-daemon} → qits: the autonomous self-clone (or submodule materialization) failed
 * in-container — the "degrade loudly" signal. qits marks the container failed with the message and
 * removes it.
 */
public record ProvisionFailed(String projectId, String message) implements DaemonMessage {}
