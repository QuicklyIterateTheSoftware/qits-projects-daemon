package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * An unsolicited nudge that something the project UI renders has changed, pushed from {@code
 * projects-daemon} to qits so the browser refetches instead of polling on its own cadence. Payload
 * free by design: a dropped frame costs one stale view until the next one, and carrying the new
 * state would mean two sources of truth for it.
 *
 * <p>Deliberately generic — the frame carries a {@code topic}, so the next thing that needs a nudge
 * costs nothing on the wire. A plain String rather than an enum for the same reason {@link
 * DaemonProtocol.AgentState} is: this module stays free of any backend domain type. A topic the
 * backend does not recognise is dropped rather than treated as an error.
 *
 * @param projectId the project whose view changed
 * @param topic which view, e.g. {@code COMMANDS}
 */
public record ProjectChanged(String projectId, String topic) implements DaemonMessage {}
