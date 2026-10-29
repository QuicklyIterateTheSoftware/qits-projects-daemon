package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.projectsdaemon.protocol.DeskBrief;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The retirement guard for the delivery brief FILE (qits-1734): nothing in this repository names
 * the old brief path any more (it used to live under {@code /tmp}), and nothing but {@link
 * DeskBrief} itself spells out its {@link DeskBrief#INSTRUCTION} sentence — every other caller is
 * required to compose it from that class, so a reviewer grepping for either string finds this
 * test's failure rather than a second, drifting copy.
 *
 * <p>The forbidden strings are built here rather than written as literals, on purpose: a literal
 * copy of either one would make this very file the next thing a plain grep (or this test) has to
 * treat as an exception. This file excludes itself from both scans for the same reason.
 */
class BriefSourceScanTest {

  private static final String THIS_FILE_NAME = BriefSourceScanTest.class.getSimpleName() + ".java";
  private static final String DESK_BRIEF_SIMPLE_NAME = DeskBrief.class.getSimpleName() + ".java";

  /** The retired brief path, assembled piecemeal so no literal copy sits in this source file. */
  private static final String RETIRED_BRIEF_PATH =
      "/" + "tmp" + "/" + "qits" + "-" + "delivery" + "-" + "brief" + ".md";

  @Test
  void theRetiredBriefPathAppearsNowhere() {
    scan(this::assertFileDoesNotContain, RETIRED_BRIEF_PATH);
  }

  @Test
  void theInstructionSentenceIsSpelledOutOnlyInDeskBrief() {
    scan(
        (path, needle) -> {
          if (!path.getFileName().toString().equals(DESK_BRIEF_SIMPLE_NAME)
              && contains(path, needle)) {
            fail("found " + DeskBrief.class.getSimpleName() + ".INSTRUCTION spelled out in " + path);
          }
        },
        DeskBrief.INSTRUCTION);
  }

  // --- the scan ----------------------------------------------------------------------------

  private interface Check {
    void check(Path path, String needle);
  }

  private void assertFileDoesNotContain(Path path, String needle) {
    if (!path.getFileName().toString().equals(THIS_FILE_NAME) && contains(path, needle)) {
      fail("found the retired brief path in " + path);
    }
  }

  private void scan(Check check, String needle) {
    List<Path> files = sourceFiles();
    assertTrue(files.size() > 10, "the scan found suspiciously few files: " + files.size());
    for (Path path : files) {
      check.check(path, needle);
    }
  }

  private static boolean contains(Path path, String needle) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8).contains(needle);
    } catch (IOException e) {
      // A handful of test fixtures are binary or non-UTF-8; those cannot carry the brief text
      // either way, so skip them rather than fail the scan over an unrelated fixture.
      return false;
    }
  }

  /**
   * Every {@code .java} file under both modules' {@code src/main} and {@code src/test}, plus
   * {@code AGENTS.md} and {@code README.md} at this submodule's root.
   */
  private static List<Path> sourceFiles() {
    Path root = repositoryRoot();
    List<Path> roots =
        List.of(
            root.resolve("projects-daemon-protocol/src/main"),
            root.resolve("projects-daemon-protocol/src/test"),
            root.resolve("projects-daemon/src/main"),
            root.resolve("projects-daemon/src/test"));
    try {
      List<Path> files =
          roots.stream()
              .filter(Files::isDirectory)
              .flatMap(
                  dir -> {
                    try {
                      return Files.walk(dir);
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  })
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".java"))
              .collect(Collectors.toCollection(ArrayList::new));
      Stream.of("AGENTS.md", "README.md")
          .map(root::resolve)
          .filter(Files::isRegularFile)
          .forEach(files::add);
      return files;
    } catch (UncheckedIOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * This submodule's root — the directory holding {@code projects-daemon-protocol/},
   * {@code projects-daemon/}, {@code AGENTS.md} and {@code README.md} — found by walking up from
   * the JVM's working directory rather than assuming surefire's default (the module's own
   * {@code basedir}), so the test survives a future {@code workingDirectory} override.
   */
  private static Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve("projects-daemon-protocol"))
          && Files.isDirectory(dir.resolve("projects-daemon"))
          && Files.isRegularFile(dir.resolve("AGENTS.md"))) {
        return dir;
      }
    }
    throw new IllegalStateException(
        "could not find the qits-projects-daemon repository root above " + Path.of("").toAbsolutePath());
  }
}
