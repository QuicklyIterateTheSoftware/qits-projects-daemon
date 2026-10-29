package eu.wohlben.qits.projectsdaemon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link DeskBrief#pointer} composes the one sentence every prompt uses to send a session at its
 * brief: {@link DeskBrief#INSTRUCTION} plus whichever hash-check fits the caller.
 */
class DeskBriefTest {

  @Test
  void pointerWithNoHashFallsBackToTheHashCheckInstruction() {
    String pointer = DeskBrief.pointer(null);
    assertTrue(pointer.startsWith(DeskBrief.INSTRUCTION), pointer);
    assertEquals(DeskBrief.INSTRUCTION + ". " + DeskBrief.HASH_CHECK, pointer);
  }

  @Test
  void pointerWithAHashNamesIt() {
    String pointer = DeskBrief.pointer("abc");
    assertTrue(pointer.startsWith(DeskBrief.INSTRUCTION), pointer);
    assertEquals(DeskBrief.INSTRUCTION + ". " + DeskBrief.hashNote("abc"), pointer);
    assertTrue(pointer.contains("abc"), pointer);
  }
}
