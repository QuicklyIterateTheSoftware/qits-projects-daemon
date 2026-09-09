package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.commands.CheckoutContext;

/**
 * Who this project is, for the commands that run in it — this daemon's own half of {@link
 * CheckoutContext}.
 *
 * <p><b>It lives here now, and that is the point of the split.</b> This interface used to be
 * declared inside the copy of {@code qits-commands} this repository carried, where it answered four
 * questions: project id, wrapper repository, branch, commit. The workspace daemon's copy declared
 * the same interface under a different name answering four questions about a workspace — and two of
 * the four were the same question in both. Those two are the only ones the shared library ever
 * asked (a command records the branch and commit it ran at; the chat transport names a
 * remote-control session after the branch), so they stayed there as {@link CheckoutContext} and the
 * other two came here, where the identity of a project agent container belongs.
 *
 * <p>The library therefore cannot tell a project agent container from a workspace one, which is
 * exactly what lets one jar serve both. What reads {@link #projectId()} and {@link #repoName()} is
 * this daemon: its response bodies ({@code CommandJson}) and its MCP narrowing ({@code
 * ProjectMcpServers}).
 *
 * <p>None of these are lookups. The daemon is told its project at container creation, the wrapper
 * checkout is its own working directory, and the commit is read from that checkout.
 *
 * <p>Implemented by {@link DaemonProjectContext}; every method is read at launch time, so a checkout
 * that moves mid-session is reflected on the next command rather than being snapshotted here.
 */
public interface ProjectContext extends CheckoutContext {

  /** The project this container serves. */
  String projectId();

  /** The wrapper repository checked out at {@code /workspace}. */
  String repoName();
}
