package eu.wohlben.qits.projectsdaemon;

import eu.wohlben.qits.projectsdaemon.agents.AgentDefaults;
import eu.wohlben.qits.projectsdaemon.agents.AgentType;
import java.util.Optional;

/**
 * Resolves the agent preferences a launch falls back on when the request does not state them.
 *
 * <p>Order is <em>request parameter &gt; daemon configuration</em>. The request half lives in
 * {@link AgentDefaults#resolve}; this supplies the other one.
 *
 * <p>The workspace daemon had a third step between them: the checkout's own {@code
 * .qits-config.yml}. That file is a <em>repository's</em> declaration — its actions, its services,
 * its bootstrap chain — and a project agent's checkout is a wrapper holding none of its own. The
 * config reader went with the rest of that machinery (AGENTS.md, "What was trimmed"), so a
 * preference here comes from configuration or from the request, and nowhere else.
 */
final class DaemonAgentDefaults implements AgentDefaults {

  private final AgentType daemonDefault;
  private final boolean activityTrackingDefault;

  DaemonAgentDefaults(Optional<String> daemonDefault, boolean activityTrackingDefault) {
    this.daemonDefault = AgentType.parse(daemonDefault.orElse(null)).orElse(AgentType.CLAUDE);
    this.activityTrackingDefault = activityTrackingDefault;
  }

  @Override
  public AgentType defaultAgentType() {
    return daemonDefault;
  }

  @Override
  public boolean activityTrackingEnabled() {
    return activityTrackingDefault;
  }
}
