# qits-projects-daemon

The process inside a qits project agent container. On boot it clones the project's wrapper
repository into `/workspace` from its own injected environment — nobody tells it to — and dials home
to qits-projects over a WebSocket it then holds open. Through that socket a user runs interactive
coding-agent sessions (a shelled Claude CLI on a real PTY) from a web terminal, with the agent
reaching qits-projects' MCP tools scoped to the project. The daemon's HTTP API binds `127.0.0.1`
only and is reached over a reverse tunnel the daemon dials outward, so an agent container has no
port on the shared network for a peer container to reach.

Architecture and adaptation notes: [AGENTS.md](AGENTS.md).
