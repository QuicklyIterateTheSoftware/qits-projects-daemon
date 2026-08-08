package eu.wohlben.qits.projectsdaemon.commands;

/**
 * Who this project is, for the commands that run in it.
 *
 * <p>The workspace daemon answered the same four questions about a workspace: repository id,
 * workspace id, branch, commit. A project agent container has no workspace and no branch claim, so
 * the identity is the project it serves and the wrapper repository it checked out.
 *
 * <p>None of these are lookups. The daemon is told its project at container creation, the wrapper
 * checkout is its own working directory, and the commit is read from that checkout.
 *
 * <p>Implemented by the daemon module; every method is read at launch time, so a checkout that
 * moves mid-session is reflected on the next command rather than being snapshotted here.
 */
public interface ProjectContext {

  /** The project this container serves. */
  String projectId();

  /** The wrapper repository checked out at {@code /workspace}. */
  String repoName();

  /** The branch currently checked out. */
  String branch();

  /**
   * The commit currently checked out, or null if it is not known yet (a checkout whose first git
   * read has not landed). Null is recorded as-is.
   */
  String commitHash();
}
