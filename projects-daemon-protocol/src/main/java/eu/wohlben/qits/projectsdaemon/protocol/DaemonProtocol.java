package eu.wohlben.qits.projectsdaemon.protocol;

/**
 * The single source of truth for the projects-daemon control-plane wire contract's tags and field
 * names.
 *
 * <p>Messages are JSON objects with a {@code "type"} discriminator ({@link Type}) and a flat set of
 * fields ({@link Field}). The records in this package model each message's shape; qits-projects
 * (de)serializes them with its Jackson {@code ObjectMapper}, the {@code projects-daemon} binary
 * maps them to and from a Vert.x {@code JsonObject} field-by-field — both against these constants,
 * so a rename is caught in one place.
 */
public final class DaemonProtocol {

  /**
   * The capability version {@code projects-daemon} announces in its {@link Hello}. Bumped when the
   * wire contract changes in a way the backend must branch on.
   *
   * <p>Starts at <b>1</b> rather than continuing the workspace daemon's numbering. The two
   * protocols share a shape and a lineage but not a namespace: they name different domains, are
   * spoken by different services, and a shared number would suggest a compatibility relation that
   * does not exist.
   *
   * <p>1 already binds the HTTP API to {@code 127.0.0.1} and serves {@link OpenStream}. The
   * workspace daemon reached that arrangement at its version 4, after three versions of a listener
   * on the shared network; there is no reason to reproduce the versions it took to get there.
   *
   * <p>2 understands {@link SessionCredentials} (qits-1482): the backend sends that frame only to a
   * daemon announcing 2 or later, and a daemon that never receives one keeps launching on its own
   * token, exactly as 1 did.
   *
   * <p>3 runs the session multiplex (epic qits-770): it understands {@link Sessions} — the desired
   * list of sessions, one per key — and answers with {@link SessionReport}. The backend sends {@link
   * Sessions} only to a daemon announcing 3 or later; until one arrives a daemon keeps the front desk
   * by its lifecycle setting and reports nothing, exactly as 2 did.
   */
  public static final int CAPABILITY_VERSION = 3;

  /**
   * The fixed {@code correlationId} the daemon tags its autonomous self-provision output ({@link
   * CommandChunk}) with, so the backend can route those chunks to the container's clone segment. A
   * provision is not a request/reply round-trip, so it has no per-call id — this shared constant
   * stands in for one on both sides.
   */
  public static final String PROVISION_CORRELATION_ID = "provision";

  /**
   * The control socket qits-projects serves, as a format string over the project id. <b>Not</b> a
   * value the daemon reads: it dials the url it was handed, verbatim, and parses no path out of it.
   * Recorded here so both repos spell the path against one constant, and because the path is
   * append-only (AGENTS.md, "Cross-repo path contracts").
   */
  public static final String CONTROL_SOCKET_PATH_PREFIX = "/projects/daemon/";

  /**
   * The prefix qits-projects' container proxy answers under, one segment per project. Same status
   * as {@link #CONTROL_SOCKET_PATH_PREFIX}: recorded, append-only, and never parsed by the daemon —
   * the daemon is <em>told</em> its base path rather than deriving one.
   */
  public static final String CONTAINER_PROXY_PATH_PREFIX = "/projects/container/";

  private DaemonProtocol() {}

  /** The {@code "type"} discriminator values. */
  public static final class Type {
    // projects-daemon -> qits
    public static final String HELLO = "hello";
    public static final String HEARTBEAT = "heartbeat";
    public static final String CLIENT_LOG = "clientLog";
    public static final String COMMAND_CHUNK = "commandChunk";
    public static final String COMMAND_EXIT = "commandExit";
    public static final String PROJECT_INFO = "projectInfo";
    public static final String PROVISIONED = "provisioned";
    public static final String PROVISION_FAILED = "provisionFailed";
    public static final String AGENT_ACTIVITY = "agentActivity";
    public static final String PROJECT_CHANGED = "projectChanged";
    public static final String SESSION_REPORT = "sessionReport";
    // qits -> projects-daemon
    public static final String ACK = "ack";
    public static final String RUN_COMMAND = "runCommand";
    public static final String DESCRIBE = "describe";
    public static final String OPEN_STREAM = "openStream";
    public static final String SESSION_CREDENTIALS = "sessionCredentials";
    public static final String SESSIONS = "sessions";

    private Type() {}
  }

  /** The JSON field names shared by both codecs. */
  public static final class Field {
    public static final String TYPE = "type";
    public static final String PROJECT_ID = "projectId";
    public static final String REPO_NAME = "repoName";
    public static final String CAPABILITY_VERSION = "capabilityVersion";
    public static final String DAEMON_VERSION = "daemonVersion";
    public static final String DAEMON_BUILD_TIME = "daemonBuildTime";
    public static final String LEVEL = "level";
    public static final String MESSAGE = "message";
    public static final String CORRELATION_ID = "correlationId";
    public static final String STREAM = "stream";
    public static final String TEXT = "text";
    public static final String EXIT_CODE = "exitCode";
    public static final String HEAD = "head";
    public static final String DIRTY = "dirty";
    public static final String ARGV = "argv";
    public static final String CWD = "cwd";
    public static final String ENV = "env";
    public static final String STATE = "state";
    public static final String COMMAND_ID = "commandId";
    public static final String SESSION_ID = "sessionId";
    public static final String HOOK_EVENT = "hookEvent";
    public static final String SOURCE = "source";
    public static final String TRANSCRIPT_PATH = "transcriptPath";
    public static final String AT = "at";
    public static final String TOPIC = "topic";
    public static final String NONCE = "nonce";
    public static final String PATH = "path";
    public static final String CREDENTIALS = "credentials";
    public static final String KEY = "key";
    public static final String TOKEN = "token";
    public static final String SUBJECT = "subject";
    public static final String SESSIONS = "sessions";
    public static final String SURFACE = "surface";
    public static final String RESUME_SESSION_ID = "resumeSessionId";
    public static final String ENTITY = "entity";
    public static final String QUALIFIED_ID = "qualifiedId";
    public static final String TITLE = "title";
    public static final String STATUS = "status";
    public static final String BLOCKED = "blocked";
    public static final String BLOCK_SOURCE = "blockSource";
    public static final String DETAIL = "detail";

    private Field() {}
  }

  /**
   * The {@code state} values an {@link AgentActivity} frame carries. The wire uses a plain String so
   * this framework-free module stays free of any backend enum; the daemon renders these constants
   * and the backend's own enum mirrors them by name.
   */
  public static final class AgentState {
    /** Session established, or a turn finished and control yielded back. */
    public static final String IDLE = "IDLE";

    /** Prompt submitted — the agent is generating a response. */
    public static final String BUSY = "BUSY";

    /** Blocked on the user (permission prompt / idle input). */
    public static final String WAITING = "WAITING";

    /** Session over. */
    public static final String ENDED = "ENDED";

    private AgentState() {}
  }

  /**
   * The {@code state} a {@link Sessions.Session} asks for. Plain Strings for the same reason {@link
   * AgentState} is; the backend's own enum mirrors them by name.
   */
  public static final class DesiredState {
    /** Keep the key's session running: launch it when missing, relaunch it when it exits. */
    public static final String RUNNING = "RUNNING";

    /** Keep nothing for the key: terminate its session (SIGTERM) and forget its entity. */
    public static final String STOPPED = "STOPPED";

    private DesiredState() {}
  }

  /** The {@code state} a {@link SessionReport.Session} carries; see that record for each word. */
  public static final class ReportedState {
    /** Wanted, not running yet: waiting, or between an exit and its relaunch. */
    public static final String STARTING = "STARTING";

    /** The key's session is up. */
    public static final String RUNNING = "RUNNING";

    /** Not wanted, and nothing of it is running. */
    public static final String STOPPED = "STOPPED";

    /** Wanted, and its last launch was refused; retried on the ladder. */
    public static final String FAILED = "FAILED";

    private ReportedState() {}
  }
}
