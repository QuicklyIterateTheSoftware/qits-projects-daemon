package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.commands.ProjectContext;
import java.util.function.Supplier;

/**
 * Answers {@link ProjectContext} from what the daemon already knows.
 *
 * <p>The project id and the wrapper repository name are the identity qits-projects injected at
 * container creation. The branch and commit come from the checkout itself.
 *
 * <p>Branch and commit are suppliers, not fields: an agent can {@code git switch} mid-session, and
 * a command must record what was actually checked out when it launched. A blank or null value is
 * passed through rather than defaulted — it means the git read did not land, which is what the
 * command should record.
 */
final class DaemonProjectContext implements ProjectContext {

  private final String projectId;
  private final String repoName;
  private final Supplier<String> branch;
  private final Supplier<String> commitHash;

  DaemonProjectContext(
      String projectId, String repoName, Supplier<String> branch, Supplier<String> commitHash) {
    this.projectId = projectId;
    this.repoName = repoName;
    this.branch = branch;
    this.commitHash = commitHash;
  }

  @Override
  public String projectId() {
    return projectId;
  }

  @Override
  public String repoName() {
    return repoName;
  }

  @Override
  public String branch() {
    return branch.get();
  }

  @Override
  public String commitHash() {
    return commitHash.get();
  }
}
