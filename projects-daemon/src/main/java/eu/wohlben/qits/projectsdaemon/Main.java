package eu.wohlben.qits.projectsdaemon;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * Entry point for {@code projects-daemon} — the per-project agent container's process. A Quarkus
 * command-mode app with no web stack, compiled to a GraalVM native binary and shipped in the agent
 * image at {@code /usr/local/bin/qits-projects-daemon} (its ENTRYPOINT).
 *
 * <p>Unlike a CLI command-mode app (run a command, exit), this one <em>blocks forever</em> on
 * {@link Quarkus#waitForExit()}: it is the container's long-lived process, so it must never return
 * on its own. {@link ControlSocket} owns the dial-home connection and reconnects indefinitely; a
 * down backend or a missing dial-home URL leaves the container alive exactly as {@code sleep
 * infinity} would.
 */
@QuarkusMain
public class Main {

  public static void main(String... args) {
    Quarkus.run(DaemonApplication.class, args);
  }

  public static class DaemonApplication implements QuarkusApplication {

    @Inject ControlSocket controlSocket;

    @Override
    public int run(String... args) {
      controlSocket.start();
      Quarkus.waitForExit();
      return 0;
    }
  }
}
