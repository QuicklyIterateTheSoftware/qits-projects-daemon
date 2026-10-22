package eu.wohlben.qits.projectsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdaemon.SessionCredentialStore.AwaitingSessionCredentialsException;
import eu.wohlben.qits.projectsdaemon.SessionCredentialStore.MissingSessionCredentialException;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials.Credential;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionCredentialStore}: legacy until the first frame, per-session for good after it, and
 * a key without a credential fails closed rather than falling back (qits-1482).
 */
class SessionCredentialStoreTest {

  private final SessionCredentialStore store =
      new SessionCredentialStore(() -> "qits_tok_container", () -> "tok-agent-container-p-1");

  @Test
  void legacyModeHandsEveryKeyTheContainersOwnToken() {
    assertFalse(store.perSessionMode());
    Map<String, String> expected =
        Map.of("QITS_TOKEN", "qits_tok_container", "QITS_TOKEN_SUBJECT", "tok-agent-container-p-1");
    assertEquals(expected, store.overlayFor("front-desk"));
    assertEquals(expected, store.overlayFor("any-entity"), "legacy knows no keys");
    assertEquals(Optional.empty(), store.find("front-desk"));
  }

  /** Legacy mode may be answered by a substitute (the front desk's delivery token); only there. */
  @Test
  void aLegacySubstituteAnswersLegacyModeOnly() {
    Map<String, String> delivery = Map.of("QITS_TOKEN", "qits_tok_dd", "QITS_TOKEN_SUBJECT", "dd");
    assertEquals(delivery, store.overlayFor("front-desk", () -> Optional.of(delivery)));
    assertEquals(
        "qits_tok_container",
        store.overlayFor("front-desk", Optional::empty).get("QITS_TOKEN"),
        "an empty substitute is the container's own");

    store.replace(new SessionCredentials(List.of(new Credential("front-desk", "t1", "s1"))));
    assertEquals("t1", store.overlayFor("front-desk", () -> Optional.of(delivery)).get("QITS_TOKEN"));
    assertThrows(
        MissingSessionCredentialException.class,
        () -> store.overlayFor("desk-agents", () -> Optional.of(delivery)),
        "per-session mode never falls back to the substitute");
  }

  @Test
  void legacyWithoutAnOwnTokenRemovesIt() {
    assertEquals(
        Map.of("QITS_TOKEN", "", "QITS_TOKEN_SUBJECT", ""),
        SessionCredentialStore.withoutOwnToken().overlayFor("front-desk"));
  }

  @Test
  void theFirstFrameSwitchesToPerSessionModeForGood() {
    store.replace(
        new SessionCredentials(
            List.of(new Credential("front-desk", "qits_tok_desk", "tok-desk-session-p-1"))));

    assertTrue(store.perSessionMode());
    assertEquals(
        Map.of("QITS_TOKEN", "qits_tok_desk", "QITS_TOKEN_SUBJECT", "tok-desk-session-p-1"),
        store.overlayFor(SessionCredentials.FRONT_DESK_KEY));
    MissingSessionCredentialException missing =
        assertThrows(MissingSessionCredentialException.class, () -> store.overlayFor("entity-9"));
    assertEquals("entity-9", missing.key());
    assertFalse(missing.getMessage().contains("qits_tok"), "names the key, never a token");

    store.replace(new SessionCredentials(List.of()));
    assertTrue(store.perSessionMode(), "an empty frame never reverts to legacy");
    assertThrows(MissingSessionCredentialException.class, () -> store.overlayFor("front-desk"));
  }

  @Test
  void aFrameReplacesTheWholeSet() {
    store.replace(
        new SessionCredentials(
            List.of(
                new Credential("front-desk", "t1", "s1"), new Credential("entity-9", "t2", "s2"))));
    store.replace(new SessionCredentials(List.of(new Credential("entity-9", "t3", "s3"))));

    assertEquals(Optional.empty(), store.find("front-desk"), "left out means withdrawn");
    assertEquals(Optional.of(new Credential("entity-9", "t3", "s3")), store.find("entity-9"));
  }

  private long now = 1_000_000;

  private SessionCredentialStore clocked() {
    return new SessionCredentialStore(() -> "qits_tok_container", () -> "tok-c", () -> now);
  }

  @Test
  void insideTheGraceWindowALaunchIsDeferredThenLegacy() {
    SessionCredentialStore windowed = clocked();
    windowed.openWindow(Duration.ofSeconds(20));

    assertTrue(windowed.awaiting());
    assertThrows(AwaitingSessionCredentialsException.class, () -> windowed.overlayFor("front-desk"));

    now += 20_000;
    assertFalse(windowed.awaiting());
    assertEquals("qits_tok_container", windowed.overlayFor("front-desk").get("QITS_TOKEN"));
  }

  @Test
  void aFrameInsideTheWindowEndsTheWait() {
    SessionCredentialStore windowed = clocked();
    windowed.openWindow(Duration.ofSeconds(20));
    windowed.replace(new SessionCredentials(List.of(new Credential("front-desk", "t1", "s1"))));

    assertFalse(windowed.awaiting());
    assertEquals("t1", windowed.overlayFor("front-desk").get("QITS_TOKEN"));
  }

  @Test
  void theFirstConnectionRestartsTheWindowAndNoLaterOneDoes() {
    SessionCredentialStore windowed = clocked();
    windowed.openWindow(Duration.ofSeconds(20));
    now += 15_000;
    windowed.connectionOpened(); // the window now ends at 35 s
    now += 15_000;
    assertTrue(windowed.awaiting(), "measured from the connection, not from start");
    now += 5_000;
    assertFalse(windowed.awaiting());

    windowed.connectionOpened(); // a reconnect to an old server
    assertFalse(windowed.awaiting(), "a reconnect does not hold launches up again");
  }

  @Test
  void noWindowOpenedMeansNoWait() {
    SessionCredentialStore unwindowed = clocked();
    unwindowed.connectionOpened();
    assertFalse(unwindowed.awaiting());
  }

  @Test
  void theGraceParsesLeniently() {
    assertEquals(Duration.ofSeconds(20), SessionCredentialStore.parseGrace(Optional.empty()));
    assertEquals(Duration.ofSeconds(5), SessionCredentialStore.parseGrace(Optional.of("pt5s")));
    assertEquals(Duration.ZERO, SessionCredentialStore.parseGrace(Optional.of("PT0S")));
    assertEquals(
        SessionCredentialStore.DEFAULT_GRACE,
        SessionCredentialStore.parseGrace(Optional.of("twenty seconds")));
    assertEquals(
        SessionCredentialStore.DEFAULT_GRACE, SessionCredentialStore.parseGrace(Optional.of("-PT1S")));
  }

  @Test
  void aCredentialWithoutATokenIsNoCredential() {
    store.replace(new SessionCredentials(List.of(new Credential("front-desk", " ", "s1"))));
    assertEquals(Optional.empty(), store.find("front-desk"));
    assertThrows(MissingSessionCredentialException.class, () -> store.overlayFor("front-desk"));
  }
}
