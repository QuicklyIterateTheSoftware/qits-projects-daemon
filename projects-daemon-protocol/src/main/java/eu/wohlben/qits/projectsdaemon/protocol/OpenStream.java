package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * qits → {@code projects-daemon}: dial back to {@code path} and pipe that connection to the
 * daemon's own loopback HTTP API. The whole of the reverse tunnel, on the wire.
 *
 * <p><b>Why the direction flips.</b> A daemon HTTP API that listened on the shared docker network
 * would be reachable by DNS name from every other agent container — each running a coding agent
 * over someone else's untrusted checkout, and each able to read a shared secret out of its own
 * environment. So the listener does not exist: {@link
 * eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol#CAPABILITY_VERSION 1} binds {@code
 * 127.0.0.1}, qits asks for a stream over the control socket the daemon already holds open, and the
 * daemon dials <em>out</em> to serve it. There is then no port on the network for a peer container
 * to reach at all.
 *
 * <p><b>Why the tunnel carries bytes and not requests.</b> One message, two fields, and adding a
 * daemon endpoint after this costs nothing on the wire — the protocol grows with the transport, not
 * with the endpoint count. An HTTP-envelope framing would need a response envelope, body frames and
 * a stream id, and would still have to special-case the WebSocket upgrades that must themselves
 * traverse the tunnel ({@code /terminal/commands/{id}}, {@code /chat/commands/{id}}). A byte pipe
 * carries an upgrade the same way it carries a GET, because it does not know the difference.
 *
 * @param nonce the credential, and the only thing that names the stream. Host-minted, single-use,
 *     short-lived, and bound to the project it was sent to — it is <em>not</em> a shared API token
 *     by another name. The control socket identifies its caller by a path parameter, so anything on
 *     the network can already claim to be any project's daemon; a dial-back that named its own
 *     project would reproduce that in a second place, so this one names nothing and proves
 *     everything.
 * @param path where to dial it, relative to the authority of the daemon's own configured
 *     control-socket url. Carried rather than derived so the endpoint literal lives in one repo,
 *     and so {@code ControlSocket}'s standing property — it dials the url it was handed and parses
 *     no path out of it — survives. <b>The daemon must refuse a path that is not host-relative</b>:
 *     an absolute URL here would be exactly the SSRF primitive that "the host never learns an
 *     address from a container" forbids, only pointed the other way.
 */
public record OpenStream(String nonce, String path) implements DaemonMessage {}
