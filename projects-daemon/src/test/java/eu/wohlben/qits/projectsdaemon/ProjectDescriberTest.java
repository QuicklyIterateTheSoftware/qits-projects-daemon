package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdaemon.protocol.ProjectInfo;
import org.junit.jupiter.api.Test;

/**
 * Locks in the {@code git status --porcelain=v2 --branch} parsing that lets a single git fork report
 * both HEAD and the dirty flag — the container-free half of {@link ProjectDescriber#describe}.
 */
class ProjectDescriberTest {

  private static ProjectInfo parse(String statusV2) {
    return ProjectDescriber.parse("proj-1", "qits-qits", statusV2);
  }

  @Test
  void cleanTreeReadsHeadAndIsNotDirty() {
    String out =
        """
        # branch.oid 1a2b3c4d5e6f
        # branch.head main
        # branch.upstream origin/main
        """;
    ProjectInfo info = parse(out);
    assertEquals("1a2b3c4d5e6f", info.head());
    assertFalse(info.dirty());
    assertEquals("proj-1", info.projectId());
    assertEquals("qits-qits", info.repoName());
  }

  @Test
  void anEntryLineMarksTheTreeDirty() {
    String out =
        """
        # branch.oid 1a2b3c4d5e6f
        # branch.head main
        1 .M N... 100644 100644 100644 aaa bbb readme.md
        ? untracked.txt
        """;
    ProjectInfo info = parse(out);
    assertEquals("1a2b3c4d5e6f", info.head());
    assertTrue(info.dirty());
  }

  @Test
  void unbornBranchHasBlankHead() {
    ProjectInfo info = parse("# branch.oid (initial)\n# branch.head (detached)\n");
    assertEquals("", info.head());
    assertFalse(info.dirty());
  }

  @Test
  void blankOutputYieldsBlankHeadAndNotDirty() {
    ProjectInfo info = parse("");
    assertEquals("", info.head());
    assertFalse(info.dirty());
  }
}
