package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * {@code projects-daemon}'s answer to {@link Describe}: the container's identity plus the
 * in-container git {@code HEAD} and dirty flag of the wrapper checkout.
 *
 * <p>The workspace daemon's equivalent carried {@code branch} and {@code parent} as well. Neither
 * survives here: a project agent container claims no branch, so there is no parent to report
 * against and no integration decision to inform.
 */
public record ProjectInfo(String projectId, String repoName, String head, boolean dirty)
    implements DaemonMessage {}
