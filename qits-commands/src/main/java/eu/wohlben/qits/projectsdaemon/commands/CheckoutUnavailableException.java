package eu.wohlben.qits.projectsdaemon.commands;

/**
 * Nothing can run because the checkout is not there — a 503 at the API boundary.
 *
 * <p>Its own type, and not a 400 or a 500, because a failed self-provision leaves the container
 * running with an empty {@code /workspace}: the caller did nothing wrong and the daemon is not
 * broken, so the only honest answer is "not now". The message is written here rather than taken
 * from an arbitrary exception, so the API can return it instead of hiding it behind "Internal
 * error".
 */
public class CheckoutUnavailableException extends RuntimeException {

  public CheckoutUnavailableException(String message) {
    super(message);
  }
}
