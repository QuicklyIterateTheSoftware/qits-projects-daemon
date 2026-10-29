package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * The one owner of the sentence that sends the delivery desk — and agents generally — to its
 * brief (qits-1734).
 *
 * <p>qits-projects-service and this daemon each compose every prompt that points a session at the
 * brief from these constants, rather than writing the sentence out themselves: find-usages on
 * {@link #INSTRUCTION} or {@link #pointer} therefore lists every prompt on either side that relies
 * on it, which a sentence copied by hand into each caller never would.
 */
public final class DeskBrief {

  /** The instruction itself: run the guide command and follow what it prints. */
  public static final String INSTRUCTION = "Run `qits help guide` and follow your brief";

  /**
   * What a caller with no hash to compare against says instead of {@link #hashNote}: the guide
   * carries its own hash, and re-reading it is only owed when that hash has moved.
   */
  public static final String HASH_CHECK =
      "`qits help guide --hash-only` prints its current hash: re-read the guide only when that"
          + " differs from the hash you last read.";

  /**
   * The same rule as {@link #HASH_CHECK}, worded against a hash the caller already holds, so a
   * session can compare without an extra command.
   */
  public static String hashNote(String hash) {
    return "Its guide-hash is "
        + hash
        + ": re-read the guide only when that differs from the hash you last read.";
  }

  /**
   * The full sentence a prompt hands a session: {@link #INSTRUCTION}, followed by {@link
   * #hashNote} when the caller has a hash to compare against, or {@link #HASH_CHECK} when it does
   * not.
   */
  public static String pointer(String hash) {
    return INSTRUCTION + ". " + (hash == null ? HASH_CHECK : hashNote(hash));
  }

  private DeskBrief() {}
}
