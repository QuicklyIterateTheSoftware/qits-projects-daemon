package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * One streamed slice of a {@link RunCommand}'s output, tagged with its {@link Stream} and
 * correlated back to the request. Emitted zero-or-more times before the terminal {@link
 * CommandExit}. The boot self-clone reuses it under {@link DaemonProtocol#PROVISION_CORRELATION_ID},
 * which is not a request id — a provision is not a round-trip.
 */
public record CommandChunk(String correlationId, Stream stream, String text)
    implements DaemonMessage {}
