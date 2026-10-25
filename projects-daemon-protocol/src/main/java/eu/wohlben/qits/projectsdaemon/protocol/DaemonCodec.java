package eu.wohlben.qits.projectsdaemon.protocol;

import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.Field;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the control-plane messages become (and un-become) a flat {@code Map<String,Object>}
 * — the wire's lowest common denominator. Framework-free on purpose: qits-projects bridges the map
 * to and from JSON with its Jackson {@code ObjectMapper}, the {@code projects-daemon} binary with a
 * Vert.x {@code JsonObject} ({@code new JsonObject(map)} / {@code jsonObject.getMap()}), so neither
 * side reimplements the field mapping and a rename lands in exactly one file.
 *
 * <p>Numbers are read through {@link Number} so it does not matter whether the JSON layer decoded an
 * {@code int} as {@code Integer} (Jackson) or {@code Long} (Vert.x). Absent optional fields decode
 * to {@code null}.
 */
public final class DaemonCodec {

  private DaemonCodec() {}

  /** Flatten a message to its wire map, including the {@code "type"} discriminator. */
  public static Map<String, Object> encode(DaemonMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (message) {
      case Hello m -> {
        map.put(Field.TYPE, Type.HELLO);
        map.put(Field.PROJECT_ID, m.projectId());
        map.put(Field.REPO_NAME, m.repoName());
        map.put(Field.CAPABILITY_VERSION, m.capabilityVersion());
        map.put(Field.DAEMON_VERSION, m.daemonVersion());
        map.put(Field.DAEMON_BUILD_TIME, m.daemonBuildTime());
      }
      case Heartbeat m -> {
        map.put(Field.TYPE, Type.HEARTBEAT);
        map.put(Field.PROJECT_ID, m.projectId());
      }
      case DaemonLog m -> {
        map.put(Field.TYPE, Type.CLIENT_LOG);
        map.put(Field.LEVEL, m.level());
        map.put(Field.MESSAGE, m.message());
      }
      case CommandChunk m -> {
        map.put(Field.TYPE, Type.COMMAND_CHUNK);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.STREAM, m.stream().name());
        map.put(Field.TEXT, m.text());
      }
      case CommandExit m -> {
        map.put(Field.TYPE, Type.COMMAND_EXIT);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.EXIT_CODE, m.exitCode());
      }
      case ProjectInfo m -> {
        map.put(Field.TYPE, Type.PROJECT_INFO);
        map.put(Field.PROJECT_ID, m.projectId());
        map.put(Field.REPO_NAME, m.repoName());
        map.put(Field.HEAD, m.head());
        map.put(Field.DIRTY, m.dirty());
      }
      case Provisioned m -> {
        map.put(Field.TYPE, Type.PROVISIONED);
        map.put(Field.PROJECT_ID, m.projectId());
        map.put(Field.HEAD, m.head());
      }
      case ProvisionFailed m -> {
        map.put(Field.TYPE, Type.PROVISION_FAILED);
        map.put(Field.PROJECT_ID, m.projectId());
        map.put(Field.MESSAGE, m.message());
      }
      case AgentActivity m -> {
        map.put(Field.TYPE, Type.AGENT_ACTIVITY);
        map.put(Field.COMMAND_ID, m.commandId());
        map.put(Field.SESSION_ID, m.sessionId());
        map.put(Field.STATE, m.state());
        map.put(Field.HOOK_EVENT, m.hookEvent());
        map.put(Field.SOURCE, m.source());
        map.put(Field.TRANSCRIPT_PATH, m.transcriptPath());
        map.put(Field.AT, m.at());
      }
      case ProjectChanged m -> {
        map.put(Field.TYPE, Type.PROJECT_CHANGED);
        map.put(Field.PROJECT_ID, m.projectId());
        map.put(Field.TOPIC, m.topic());
      }
      case Ack _ -> map.put(Field.TYPE, Type.ACK); // no fields beyond the discriminator
      case RunCommand m -> {
        map.put(Field.TYPE, Type.RUN_COMMAND);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.ARGV, m.argv() == null ? List.of() : new ArrayList<>(m.argv()));
        map.put(Field.CWD, m.cwd());
        map.put(Field.ENV, m.env() == null ? Map.of() : new LinkedHashMap<>(m.env()));
      }
      case Describe m -> {
        map.put(Field.TYPE, Type.DESCRIBE);
        map.put(Field.CORRELATION_ID, m.correlationId());
      }
      case OpenStream m -> {
        map.put(Field.TYPE, Type.OPEN_STREAM);
        map.put(Field.NONCE, m.nonce());
        map.put(Field.PATH, m.path());
      }
      case SessionCredentials m -> {
        map.put(Field.TYPE, Type.SESSION_CREDENTIALS);
        List<Map<String, Object>> credentials = new ArrayList<>(m.credentials().size());
        for (SessionCredentials.Credential credential : m.credentials()) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put(Field.KEY, credential.key());
          entry.put(Field.TOKEN, credential.token());
          entry.put(Field.SUBJECT, credential.subject());
          credentials.add(entry);
        }
        map.put(Field.CREDENTIALS, credentials);
      }
      case Sessions m -> {
        map.put(Field.TYPE, Type.SESSIONS);
        List<Map<String, Object>> sessions = new ArrayList<>(m.sessions().size());
        for (Sessions.Session session : m.sessions()) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put(Field.KEY, session.key());
          entry.put(Field.SURFACE, session.surface());
          entry.put(Field.STATE, session.state());
          entry.put(Field.RESUME_SESSION_ID, session.resumeSessionId());
          entry.put(Field.ENTITY, session.entity() == null ? null : entity(session.entity()));
          sessions.add(entry);
        }
        map.put(Field.SESSIONS, sessions);
      }
      case SessionReport m -> {
        map.put(Field.TYPE, Type.SESSION_REPORT);
        List<Map<String, Object>> sessions = new ArrayList<>(m.sessions().size());
        for (SessionReport.Session session : m.sessions()) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put(Field.KEY, session.key());
          entry.put(Field.STATE, session.state());
          entry.put(Field.COMMAND_ID, session.commandId());
          entry.put(Field.SESSION_ID, session.sessionId());
          entry.put(Field.DETAIL, session.detail());
          sessions.add(entry);
        }
        map.put(Field.SESSIONS, sessions);
      }
    }
    return map;
  }

  /** Rebuild a message from its wire map, dispatching on the {@code "type"} discriminator. */
  public static DaemonMessage decode(Map<String, Object> map) {
    String type = str(map, Field.TYPE);
    if (type == null) {
      throw new IllegalArgumentException("projects-daemon message has no '" + Field.TYPE + "' field");
    }
    return switch (type) {
      case Type.HELLO ->
          new Hello(
              str(map, Field.PROJECT_ID),
              str(map, Field.REPO_NAME),
              intVal(map, Field.CAPABILITY_VERSION),
              str(map, Field.DAEMON_VERSION),
              str(map, Field.DAEMON_BUILD_TIME));
      case Type.HEARTBEAT -> new Heartbeat(str(map, Field.PROJECT_ID));
      case Type.CLIENT_LOG -> new DaemonLog(str(map, Field.LEVEL), str(map, Field.MESSAGE));
      case Type.COMMAND_CHUNK ->
          new CommandChunk(
              str(map, Field.CORRELATION_ID),
              Stream.valueOf(str(map, Field.STREAM)),
              str(map, Field.TEXT));
      case Type.COMMAND_EXIT ->
          new CommandExit(str(map, Field.CORRELATION_ID), intVal(map, Field.EXIT_CODE));
      case Type.PROJECT_INFO ->
          new ProjectInfo(
              str(map, Field.PROJECT_ID),
              str(map, Field.REPO_NAME),
              str(map, Field.HEAD),
              boolVal(map, Field.DIRTY));
      case Type.PROVISIONED -> new Provisioned(str(map, Field.PROJECT_ID), str(map, Field.HEAD));
      case Type.PROVISION_FAILED ->
          new ProvisionFailed(str(map, Field.PROJECT_ID), str(map, Field.MESSAGE));
      case Type.AGENT_ACTIVITY ->
          new AgentActivity(
              str(map, Field.COMMAND_ID),
              str(map, Field.SESSION_ID),
              str(map, Field.STATE),
              str(map, Field.HOOK_EVENT),
              str(map, Field.SOURCE),
              str(map, Field.TRANSCRIPT_PATH),
              longVal(map, Field.AT));
      case Type.PROJECT_CHANGED ->
          new ProjectChanged(str(map, Field.PROJECT_ID), str(map, Field.TOPIC));
      case Type.ACK -> new Ack();
      case Type.RUN_COMMAND ->
          new RunCommand(
              str(map, Field.CORRELATION_ID),
              strList(map, Field.ARGV),
              str(map, Field.CWD),
              strMap(map, Field.ENV));
      case Type.DESCRIBE -> new Describe(str(map, Field.CORRELATION_ID));
      case Type.OPEN_STREAM -> new OpenStream(str(map, Field.NONCE), str(map, Field.PATH));
      case Type.SESSION_CREDENTIALS -> new SessionCredentials(credentials(map, Field.CREDENTIALS));
      case Type.SESSIONS -> new Sessions(sessions(map, Field.SESSIONS));
      case Type.SESSION_REPORT -> new SessionReport(reported(map, Field.SESSIONS));
      default -> throw new IllegalArgumentException("unknown projects-daemon message type: " + type);
    };
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static int intVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static long longVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static boolean boolVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Boolean bool && bool;
  }

  private static List<String> strList(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object element : list) {
      out.add(element == null ? null : element.toString());
    }
    return out;
  }

  /**
   * The {@link SessionCredentials} list: an absent or non-list field is empty, and an entry that is
   * not an object or names no key is skipped rather than failing the whole frame — the rest of the
   * desk's sessions still get theirs.
   */
  private static List<SessionCredentials.Credential> credentials(
      Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<SessionCredentials.Credential> out = new ArrayList<>(list.size());
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> raw)) {
        continue;
      }
      Object credentialKey = raw.get(Field.KEY);
      if (credentialKey == null) {
        continue;
      }
      Object token = raw.get(Field.TOKEN);
      Object subject = raw.get(Field.SUBJECT);
      out.add(
          new SessionCredentials.Credential(
              credentialKey.toString(),
              token == null ? null : token.toString(),
              subject == null ? null : subject.toString()));
    }
    return out;
  }

  private static Map<String, Object> entity(Sessions.Entity entity) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.QUALIFIED_ID, entity.qualifiedId());
    map.put(Field.TITLE, entity.title());
    map.put(Field.STATUS, entity.status());
    map.put(Field.BLOCKED, entity.blocked());
    map.put(Field.BLOCK_SOURCE, entity.blockSource());
    return map;
  }

  /**
   * The {@link Sessions} list, as tolerant as {@link #credentials}: an absent or non-list field is
   * empty, an entry that is not an object or names no key is skipped, and an {@code entity} that is
   * not an object is none — one malformed entry must not stop the rest of the desk's sessions.
   */
  private static List<Sessions.Session> sessions(Map<String, Object> map, String key) {
    List<Sessions.Session> out = new ArrayList<>();
    for (Map<String, Object> raw : entries(map, key)) {
      Sessions.Entity entity = null;
      if (raw.get(Field.ENTITY) instanceof Map<?, ?> rawEntity) {
        Map<String, Object> fields = object(rawEntity);
        entity =
            new Sessions.Entity(
                str(fields, Field.QUALIFIED_ID),
                str(fields, Field.TITLE),
                str(fields, Field.STATUS),
                boolVal(fields, Field.BLOCKED),
                str(fields, Field.BLOCK_SOURCE));
      }
      out.add(
          new Sessions.Session(
              str(raw, Field.KEY),
              str(raw, Field.SURFACE),
              str(raw, Field.STATE),
              str(raw, Field.RESUME_SESSION_ID),
              entity));
    }
    return out;
  }

  /** The {@link SessionReport} list; an entry without a key or a state is skipped. */
  private static List<SessionReport.Session> reported(Map<String, Object> map, String key) {
    List<SessionReport.Session> out = new ArrayList<>();
    for (Map<String, Object> raw : entries(map, key)) {
      if (raw.get(Field.STATE) == null) {
        continue;
      }
      out.add(
          new SessionReport.Session(
              str(raw, Field.KEY),
              str(raw, Field.STATE),
              str(raw, Field.COMMAND_ID),
              str(raw, Field.SESSION_ID),
              str(raw, Field.DETAIL)));
    }
    return out;
  }

  /** The object entries of the list at {@code key} that carry a {@code key} field. */
  private static List<Map<String, Object>> entries(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>(list.size());
    for (Object element : list) {
      if (element instanceof Map<?, ?> raw && raw.get(Field.KEY) != null) {
        out.add(object(raw));
      }
    }
    return out;
  }

  private static Map<String, Object> object(Map<?, ?> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    raw.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }

  private static Map<String, String> strMap(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      out.put(
          String.valueOf(entry.getKey()),
          entry.getValue() == null ? null : entry.getValue().toString());
    }
    return out;
  }
}
