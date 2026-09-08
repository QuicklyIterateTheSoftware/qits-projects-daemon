package eu.wohlben.qits.projectsdaemon.agents;

/**
 * Which front desk a launch opens — the container's <em>steering</em> axis, resolved by {@link
 * AgentLaunchService} into a system-prompt appendix and the command's name.
 *
 * <ul>
 *   <li>{@link #EPICS} — the plan desk this container was built for: refining and drafting the
 *       project's epics. The default, and it steers with nothing at all.
 *   <li>{@link #TICKETS} — intake and triage for the small-scoped work beside the plans: bugs and
 *       improvements, filed and moved through the {@code repository} server's ticket tools.
 * </ul>
 *
 * <p>A desk is deliberately <strong>not</strong> a {@link AgentMcpScope}. The scope decides how
 * narrow the one MCP server's URL is — whether the session sees the whole project or one repository
 * — and it is a property of what the caller is looking at. The desk decides what the session is
 * <em>for</em>: which system prompt it carries and what its command is called. The two axes cross
 * freely, and folding them into one enum would mean a tickets desk could not be narrowed to a
 * repository, or that narrowing to a repository would quietly change what the agent is steered at.
 * Both are wrong for the same reason: steering and addressing are separate questions.
 *
 * <p>{@link #EPICS} reproduces the pre-desk launch byte for byte — no appendix is rendered and the
 * command keeps its {@code (repository MCP)} / {@code (project MCP)} name — so adding this axis
 * changed nothing that was already running. That equivalence is asserted, not assumed.
 */
public enum AgentDesk {
  EPICS,
  TICKETS
}
