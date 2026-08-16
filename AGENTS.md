# qits-projects-daemon

The per-project agent container's process. Adapted from `qits-workspace-daemon`, which stays the
reference implementation: same architecture, one project instead of one workspace, and a lot less
of it.

This file records what changed in the adaptation and the two contracts that reach outside this
repository. Read it before adding anything back.

## Modules

    projects-daemon-protocol/   the wire contract: records, the codec, the constants. No dependencies.
    qits-commands/              processes: the PTY, the registry, the store, the chat seams.
    qits-coding-agents/         the harnesses: Claude Code, Kimi, session lineage, transcripts.
    projects-daemon/            the Quarkus module: sockets, provisioning, the loopback API.

`qits-commands` and `qits-coding-agents` are framework-free — no CDI, no JAX-RS, no Jackson — so
they cannot read configuration. `ControlSocket` is the single reader and hands every setting down
as a constructor argument. That is not tidiness: two readers of the hook port is how `HookWebhook`
and `AgentLaunchService` end up bound to different ports, which fails invisibly (the agent runs and
simply never reports lineage).

## The clone-alone rule

`./mvnw verify` must be green from a clone of this repository alone: no monorepo, no sibling
checkouts, no docker, no network beyond Maven Central. Nothing here may depend on a `-SNAPSHOT`
this repository does not build.

Tests that need a PTY are `@EnabledOnOs(LINUX)` — `ForeignPty` calls libc through
`java.lang.foreign`, and the descriptors are Linux ABI. Keep that annotation on anything that
spawns.

## The vendoring contract

`projects-daemon-protocol` is a **source module, vendored** into qits-projects — not a published
jar. qits-projects copies these sources into its own tree so both ends encode and decode against
one mapping.

Nothing at build time notices when the two copies drift. **`DaemonCodecTest` is the drift
detector**, and it only works if it is vendored alongside the sources and run on both sides. A
change to the protocol is therefore three edits, in this order:

1. the record and the `DaemonProtocol` constants here,
2. `DaemonCodecTest` here, green,
3. the same files vendored into qits-projects, its suite green.

Bump `CAPABILITY_VERSION` whenever the backend must branch on the change. It starts at **1**, not
at the workspace daemon's 4: the two protocols share a shape and a lineage but not a namespace,
and a shared number would suggest a compatibility relation that does not exist. Version 1 already
binds loopback and serves `OpenStream`, which the workspace daemon only reached at 4 — there is no
reason to reproduce the versions it took to get there.

## Cross-repo path contracts

Two paths are agreed with qits-projects and are **append-only**. Changing either breaks every
container already running.

    control socket   ws://<host>/projects/daemon/<projectId>
    proxy prefix     /projects/container/<projectId>/

Both are recorded as constants in `DaemonProtocol` and asserted as literals in `DaemonCodecTest`,
because they are a cross-repo agreement rather than a value either side may re-derive.

The daemon **never parses either one**. It dials the control-socket url it was handed, verbatim,
and it is *told* its proxy base path (`QITS_PROJECTS_DAEMON_API_BASE_PATH`) rather than deriving
one by stripping a segment. No hop in the chain rewrites a path, so a daemon that guessed at its
own address would disagree with its caller — and that disagreement surfaces a long way from the
guess that caused it.

## The two channels

Out: the control socket, dialled by the daemon and held open. Everything unsolicited rides it —
clone output, provisioning outcome, agent activity, change nudges.

In: nothing. `ProjectsApi` binds `127.0.0.1` and has no address on the shared docker network at
all. qits sends an `OpenStream` over the socket, `DaemonStreamTunnel` dials *out* and pipes that
connection to loopback. A peer agent container is refused by the network stack rather than by a
token check. The bearer on `ProjectsApi` is defence in depth behind that, never the boundary.

## What was renamed

Everything naming the domain moved from workspace terms to project terms. The workspace daemon's
spellings are on the left.

| Workspace daemon | Here | Note |
| --- | --- | --- |
| `Hello(workspaceId, repoId, branch, parent, …)` | `Hello(projectId, repoName, …)` | no branch claim, so no branch and no parent |
| `Heartbeat(workspaceId)` | `Heartbeat(projectId)` | |
| `WorkspaceInfo(workspaceId, repoId, branch, parent, head, dirty)` | `ProjectInfo(projectId, repoName, head, dirty)` | reply to `Describe` |
| `Provisioned(workspaceId, head)` | `Provisioned(projectId, head)` | |
| `ProvisionFailed(workspaceId, message)` | `ProvisionFailed(projectId, message)` | |
| `WorkspaceChanged(workspaceId, topic)` | `ProjectChanged(projectId, topic)` | |
| `Field.WORKSPACE_ID` / `REPO_ID` / `BRANCH` / `PARENT` | `Field.PROJECT_ID` / `REPO_NAME` | |
| `WorkspaceApi` | `ProjectsApi` | |
| `WorkspaceJson` | `ProjectsJson` | trimmed to the envelope bodies |
| `WorkspaceDescriber` | `ProjectDescriber` | also supplies `head()` for command records |
| `WorkspaceContext` (`repoId`, `workspaceId`, `branch`, `commitHash`) | `ProjectContext` (`projectId`, `repoName`, `branch`, `commitHash`) | |
| `DaemonWorkspaceContext` | `DaemonProjectContext` | |
| `ConfigActionResolver` | `NoDeclaredActions` | the seam survives, the config source did not |
| `QITS_WORKSPACE_DAEMON_*` | `QITS_PROJECTS_DAEMON_*` | env prefix |
| package `…qits.workspacedaemon` | `…qits.projectsdaemon` | |

`AgentActivity`, `CommandChunk`, `CommandExit`, `RunCommand`, `Describe`, `OpenStream`, `Ack` and
`DaemonLog` keep their names and their fields: they name the transport, not the domain.

## What was trimmed

Each of these was deliberate. Adding one back is a decision, not a restoration.

**Whole modules.**

- `workspace-daemon-files` — the file browser and its path-safety policy. Nothing in a project
  agent reads the checkout over HTTP; the agent reads it directly, in-process.
- `workspace-daemon-detection` — framework detection and the Angular component map. Both exist to
  drive a workspace's web view, which a project agent has none of.

**Workspace-resolution machinery.** A project agent claims no branch, so none of this has a
meaning here:

- `OriginSync` — auto-push, incoming `PullBranch`, fast-forward and merge-from-parent. Its two
  write endpoints (`/fast-forward`, `/update-from-parent`) went with it.
- `GitStatusMonitor` — the inotify-debounced working-tree watcher and the `GitStatus` frame. There
  is no dirty badge to drive. `ProjectDescriber.head()` forks git per launch instead, which is
  cheaper than a watcher for something read once per command.
- `PullBranch`, `GitStatus`, `WorkspaceInfo`'s `branch`/`parent` on the wire.

**Repository-declared configuration.** The wrapper checkout declares none of its own, so the reader
went and the seams that consumed it were answered differently:

- `ConfigReader`, `ConfigParser`, `ConfigJson`, `DaemonQitsConfig` — the `.qits-config.yml` parse,
  and with it the `snakeyaml` dependency.
- `BootstrapRunner` and the whole bootstrap chain: `RunBootstrap`, `BootstrapStep`,
  `BootstrapOutcome`, `Bootstrapped`, `/bootstrap-commands`.
- `ServiceSupervisor` and dev-server supervision: `StartService`, `SignalService`,
  `ServiceTransition`, `/services`, and the `QITS_PUBLIC_BASE` service-proxy base.
- `DescribeConfig` / `ConfigView`.
- `ConfigActionResolver` → `NoDeclaredActions`. The `ActionResolver` seam stays and answers empty,
  so `GET /commands/actions` returns `[]` and `POST /commands` answers 400 rather than 500 from a
  null. A real resolver lands in that seam.
- `DaemonAgentDefaults` lost its middle resolution step. Order is now *request > daemon config*,
  where the workspace daemon had *request > `.qits-config.yml` > daemon config*.

**Agent surface.**

- `PromptRefinementService` and `POST /prompt-refinements`. With it, `AgentDefaults.refinementModel()`
  and `qits.refinement.model`.
- `AgentPluginService`, `InstalledPluginDto` and `/agent-plugins`.
- `AgentMcpScope.ACTIONS`. The `actions` MCP server has no address (no service serves it) and the
  `observability` one is a different service; a project agent addresses qits-projects and nothing
  else, so a scope naming an unreachable server would only fail at launch. Scopes are `PROJECT` and
  `REPOSITORY`.
- `READ_ONLY_ACTION_TOOLS` and `READ_ONLY_OBSERVABILITY_TOOLS` allowlists, with their servers.

**A launch attaches exactly one MCP server**, `repository`, and it is the one carrying the epic
tools — the whole reason this container exists. Excluding the workspace world is deliberate: a
refinement agent's job is the project's plan, not workspace actions or another service's telemetry.
Nothing can put the others back at runtime either. Claude is launched with `--strict-mcp-config`, so
the rendered `--mcp-config` is the complete set and the shared `/claude-home` volume's own MCP
entries are ignored; Kimi gets a launch-local `mcp.json` in a throwaway `KIMI_CODE_HOME`. Both are
asserted in `AgentLaunchServiceTest`.

**Other.**

- `docs/openapi.yml` and `OpenApiContractTest`. The surface here is four routes plus two sockets;
  a spec would be a second description of it to keep in step.
- The id-addressed clone fallback. `Provisioner` is always name-addressed
  (`<gitBase>/<repoName>`) because qits-githost exposes repositories directly below `/git` and a
  wrapper's relative submodule urls resolve to sibling repository names there.
- `--branch` on the clone. The wrapper is cloned at its default branch.

## Derivations, and which ones are honest

Two addresses are derived from the one url the container is handed. They are not equally sound, and
the code says so differently.

- **The MCP server** (`DaemonMcpEndpoints`): the control socket and the `repository` MCP server are
  both **qits-projects**. Same service, same authority — the derivation is a property of the
  topology, not a guess. No WARN. It is nonetheless only the *fallback* now: qits-projects states
  the address outright as `QITS_REPOSITORY_MCP_URL`, and a stated address beats a sound derivation
  the day the MCP server stops being co-located. The derivation stays so a container created before
  that env, or a daemon run by hand, still works.
- **The git host** (`Provisioner`): the control socket is qits-projects, the git host is
  qits-artifacts. Different services, so `<authority>/artifacts/git` only holds where one authority
  routes every segment. Taking that fallback emits a `DaemonLog` WARN naming the assumption.
  `QITS_PROJECTS_DAEMON_GIT_BASE` states it outright.

Keep that distinction if either moves. A warning on a sound derivation trains people to ignore
warnings; a silent unsound one loses a whole class of misconfiguration.

## Environment

    QITS_PROJECTS_DAEMON_URL             ws://<host>/projects/daemon/<projectId>; absent ⇒ idle, container stays up
    QITS_PROJECTS_DAEMON_PROJECT_ID      the project served
    QITS_PROJECTS_DAEMON_REPO_NAME       the wrapper repository cloned into /workspace
    QITS_PROJECTS_DAEMON_GIT_BASE        git host; absent ⇒ derived with a WARN
    QITS_PROJECTS_DAEMON_API_TOKEN       bearer for the loopback API; absent ⇒ the API DOES NOT BIND
    QITS_PROJECTS_DAEMON_API_BASE_PATH   /projects/container/<projectId>/; absent ⇒ nothing fronts the daemon
    QITS_PROJECTS_DAEMON_API_PORT        default 13338
    QITS_PROJECTS_DAEMON_HOOKS_PORT      default 13337
    QITS_PROJECTS_DAEMON_CLAUDE_MOUNT    shared credential volume, default /claude-home
    QITS_PROJECTS_DAEMON_AUTH_TOKEN_URL  idp token endpoint for authenticated dial-home
    QITS_PROJECTS_DAEMON_AUTH_AUDIENCE   qits-projects' environment client id
    QITS_PROJECTS_DAEMON_GIT_AUTH_AUDIENCE qits-githost's environment client id
    QITS_REPOSITORY_MCP_URL              the one MCP server a launch attaches; absent ⇒ derived
    QITS_COMMISSIONED_CLIENT_ID          this container's idp client; absent ⇒ anonymous dev dial
    QITS_COMMISSIONED_CLIENT_SECRET      its one-time secret

`QITS_REPOSITORY_MCP_URL` is the odd name out — no `QITS_PROJECTS_DAEMON_` prefix — because it is
the existing `qits.repository-mcp.url` key, the spelling the workspace daemon uses for the same
server. qits-projects injects it on every container it creates, so the address is stated; absent, it
falls back to the derivation in `DaemonMcpEndpoints`.

Every identity value is `Optional<String>` in the code, never `@ConfigProperty(defaultValue = "")`:
SmallRye reads an empty default as *no value* and then fails to resolve a plain `String`, which
kills the binary at startup — and no test sees it, because the tests construct these classes
directly and never resolve config.

## Two rules that keep the container alive

- **Nothing may take the container down.** This process is PID 1's child. Every failure path logs
  and retries or degrades; no exception escapes to the top. A daemon with no url, a backend that is
  down, a tunnel that will not dial — all of them leave the container running.
- **Nothing blocks the event loop.** Frames arrive on a Vert.x event loop. Process launches and git
  reads go to a worker pool, and every reply is marshalled back onto the connection's context to
  write.
