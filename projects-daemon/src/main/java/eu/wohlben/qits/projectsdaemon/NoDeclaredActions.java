package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.commands.ActionResolver;
import java.util.List;
import java.util.Optional;

/**
 * The action resolver for a container that declares none.
 *
 * <p>{@link ActionResolver} is how {@code CommandService} turns a {@code POST /commands} action id
 * into a script to run. The workspace daemon answered it from the checkout's own {@code
 * .qits-config.yml}; a project agent's wrapper checkout declares no actions of its own, and the
 * config reader was trimmed with the rest of that machinery.
 *
 * <p>So the seam stays and the answer is empty: {@code GET /commands/actions} returns an empty
 * list, and {@code POST /commands} answers 400 "Unknown action" for anything. That is honest —
 * contrast leaving the resolver null, which would answer 500 for the same request. Wiring a real
 * resolver here is a feature, and the seam is where it lands.
 */
final class NoDeclaredActions implements ActionResolver {

  @Override
  public Optional<ResolvedAction> resolve(String actionId) {
    return Optional.empty();
  }

  @Override
  public List<ResolvedAction> actions() {
    return List.of();
  }
}
