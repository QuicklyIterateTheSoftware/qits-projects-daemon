package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * {@code projects-daemon} → qits: {@code /workspace} holds the project's wrapper repository (and
 * its submodules are materialized) — the autonomous self-clone on boot succeeded. Carries the
 * resulting {@code HEAD} sha. qits awaits this to settle the clone segment and mark the container
 * ready.
 */
public record Provisioned(String projectId, String head) implements DaemonMessage {}
