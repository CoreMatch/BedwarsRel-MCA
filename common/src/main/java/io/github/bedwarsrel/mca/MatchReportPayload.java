package io.github.bedwarsrel.mca;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Serializes a {@link MatchSummary} into the JSON body of the HA-Contract
 * v1.4.0 {@code POST /api/matches} request ({@code TeamReportInput},
 * unified shape).
 *
 * <p>Sent fields: {@code match_type}, {@code teams} (each with
 * {@code result} win/lose/draw), {@code duration_seconds}, {@code map_name},
 * {@code game_id} (server-side idempotency key), {@code source} ("game"),
 * {@code started_at} and {@code finished_at}. A game without a winner is
 * reported as an all-draw match (settled with S=0.5 server-side).
 */
public final class MatchReportPayload {

  private MatchReportPayload() {
  }

  public static String toJson(MatchSummary summary) {
    boolean hasWinner = summary.getWinnerTeam() != null;

    StringBuilder sb = new StringBuilder(512);
    sb.append('{');
    appendStringField(sb, "match_type", summary.getMatchType());
    sb.append(',');
    sb.append("\"teams\":[");
    List<MatchSummary.TeamResult> teams = summary.getTeams();
    for (int i = 0; i < teams.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      appendTeam(sb, teams.get(i), hasWinner);
    }
    sb.append("],");
    appendNumberField(sb, "duration_seconds", summary.getDurationSeconds());
    sb.append(',');
    appendStringField(sb, "map_name", summary.getMapName());
    sb.append(',');
    appendStringField(sb, "game_id", summary.getGameId());
    sb.append(',');
    appendStringField(sb, "source", "game");
    sb.append(',');
    appendStringField(sb, "started_at", formatRfc3339Utc(summary.getStartedAtMillis()));
    sb.append(',');
    appendStringField(sb, "finished_at", formatRfc3339Utc(summary.getFinishedAtMillis()));
    sb.append('}');
    return sb.toString();
  }

  private static void appendTeam(StringBuilder sb, MatchSummary.TeamResult team,
      boolean hasWinner) {
    sb.append('{');
    appendStringField(sb, "team_id", team.getTeamId());
    sb.append(',');
    appendStringField(sb, "result", resultOf(team, hasWinner));
    sb.append(',');
    appendNumberField(sb, "initial_count", team.getInitialCount());
    sb.append(',');
    appendNumberField(sb, "alive_count", team.getAliveCount());
    sb.append(',');
    sb.append("\"members\":[");
    List<MatchSummary.Participant> members = team.getMembers();
    for (int i = 0; i < members.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      appendParticipant(sb, members.get(i));
    }
    sb.append("]}");
  }

  private static String resultOf(MatchSummary.TeamResult team, boolean hasWinner) {
    if (team.isWinner()) {
      return "win";
    }
    // a game without any winner is reported as an all-draw match
    return hasWinner ? "lose" : "draw";
  }

  private static void appendParticipant(StringBuilder sb, MatchSummary.Participant member) {
    sb.append('{');
    appendStringField(sb, "uuid", member.getUuid());
    sb.append(',');
    appendNumberField(sb, "survival_time", member.getSurvivalTimeSeconds());
    sb.append(',');
    appendNumberField(sb, "kills", member.getKills());
    sb.append(',');
    appendNumberField(sb, "deaths", member.getDeaths());
    sb.append(',');
    sb.append("\"perf_tweak\":1.0");
    sb.append('}');
  }

  private static void appendStringField(StringBuilder sb, String key, String value) {
    sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
  }

  private static void appendNumberField(StringBuilder sb, String key, int value) {
    sb.append('"').append(key).append("\":").append(value);
  }

  /** RFC 3339 timestamp in UTC, e.g. {@code 2026-11-06T12:34:56Z}. */
  public static String formatRfc3339Utc(long millis) {
    SimpleDateFormat format =
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(new Date(millis));
  }

  static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
          break;
      }
    }
    return sb.toString();
  }

  // ---- deserialization (for dead-letter replay) ----

  /** Parsed dead-letter envelope: timestamp, game id, failure reason and the full payload. */
  public static final class DeadLetterEntry {
    public final long atMillis;
    public final String gameId;
    public final String reason;
    public final String payloadJson;

    public DeadLetterEntry(long atMillis, String gameId, String reason, String payloadJson) {
      this.atMillis = atMillis;
      this.gameId = gameId;
      this.reason = reason;
      this.payloadJson = payloadJson;
    }
  }

  /**
   * Parses one dead-letter file line into its envelope. Returns {@code null}
   * when the line is malformed or lacks the required fields.
   */
  public static DeadLetterEntry parseDeadLetterLine(String line) {
    if (line == null) {
      return null;
    }
    JsonObject obj;
    try {
      obj = JsonParser.parseObject(line);
    } catch (JsonParseException e) {
      return null;
    }
    if (obj == null) {
      return null;
    }
    long atMillis = obj.getLong("at", 0L);
    String gameId = obj.getString("game_id");
    String reason = obj.getString("reason");
    // payload is stored verbatim as a JSON value; we need its raw string form
    String payloadJson = obj.getRaw("payload");
    if (gameId == null || payloadJson == null) {
      return null;
    }
    return new DeadLetterEntry(atMillis, gameId, reason == null ? "" : reason, payloadJson);
  }

  /**
   * Reconstructs a {@link MatchSummary} from the full POST payload JSON.
   * Returns {@code null} on any parse failure.
   */
  public static MatchSummary fromJson(String json) {
    if (json == null) {
      return null;
    }
    JsonObject obj;
    try {
      obj = JsonParser.parseObject(json);
    } catch (JsonParseException e) {
      return null;
    }
    if (obj == null) {
      return null;
    }
    String gameId = obj.getString("game_id");
    String mapName = obj.getString("map_name");
    String matchType = obj.getString("match_type");
    String startedAt = obj.getString("started_at");
    String finishedAt = obj.getString("finished_at");
    JsonArray teamsArr = obj.getArray("teams");

    if (gameId == null || mapName == null || matchType == null
        || startedAt == null || finishedAt == null || teamsArr == null) {
      return null;
    }

    long startedAtMillis = parseRfc3339Utc(startedAt);
    long finishedAtMillis = parseRfc3339Utc(finishedAt);
    if (startedAtMillis < 0 || finishedAtMillis < 0) {
      return null;
    }

    List<MatchSummary.TeamResult> teams = new ArrayList<MatchSummary.TeamResult>();
    for (int i = 0; i < teamsArr.size(); i++) {
      JsonObject t = teamsArr.getObject(i);
      if (t == null) {
        return null;
      }
      String teamId = t.getString("team_id");
      String result = t.getString("result");
      int initialCount = t.getInt("initial_count", 0);
      int aliveCount = t.getInt("alive_count", 0);
      boolean winner = "win".equals(result);

      JsonArray membersArr = t.getArray("members");
      List<MatchSummary.Participant> members = new ArrayList<MatchSummary.Participant>();
      if (membersArr != null) {
        for (int j = 0; j < membersArr.size(); j++) {
          JsonObject m = membersArr.getObject(j);
          if (m == null) {
            return null;
          }
          String uuid = m.getString("uuid");
          String name = ""; // name is not part of the outgoing payload; use uuid fallback
          int survivalTime = m.getInt("survival_time", 0);
          int kills = m.getInt("kills", 0);
          int deaths = m.getInt("deaths", 0);
          if (uuid == null) {
            return null;
          }
          members.add(new MatchSummary.Participant(uuid, name, survivalTime, kills, deaths));
        }
      }
      if (teamId == null) {
        return null;
      }
      teams.add(new MatchSummary.TeamResult(teamId, initialCount, aliveCount, winner, members));
    }

    if (teams.isEmpty()) {
      return null;
    }

    return new MatchSummary(gameId, mapName, matchType, startedAtMillis, finishedAtMillis, teams);
  }

  /**
   * Parses an RFC 3339 UTC timestamp like {@code 2026-11-06T12:34:56Z}.
   * Returns the wall-clock epoch millis, or {@code -1L} if the string does
   * not match the expected shape.
   */
  public static long parseRfc3339Utc(String value) {
    if (value == null || value.length() < 20) {
      return -1L;
    }
    if (value.charAt(19) != 'Z') {
      return -1L;
    }
    SimpleDateFormat format =
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    try {
      Date d = format.parse(value);
      return d.getTime();
    } catch (ParseException e) {
      return -1L;
    }
  }

  // ---- minimal recursive-descent JSON parser ----

  /** Lightweight object wrapper around a JSON object (string-keyed map). */
  public static final class JsonObject {
    final java.util.LinkedHashMap<String, JsonValue> fields =
        new java.util.LinkedHashMap<String, JsonValue>();

    String getString(String key) {
      JsonValue v = this.fields.get(key);
      return v == null ? null : v.asString();
    }

    int getInt(String key, int defaultValue) {
      JsonValue v = this.fields.get(key);
      return v == null ? defaultValue : v.asInt(defaultValue);
    }

    long getLong(String key, long defaultValue) {
      JsonValue v = this.fields.get(key);
      return v == null ? defaultValue : v.asLong(defaultValue);
    }

    JsonArray getArray(String key) {
      JsonValue v = this.fields.get(key);
      return v == null ? null : v.asArray();
    }

    /** Returns the raw JSON value as its source string (for nested objects). */
    String getRaw(String key) {
      JsonValue v = this.fields.get(key);
      return v == null ? null : v.raw;
    }
  }

  /** Lightweight wrapper around a JSON array. */
  public static final class JsonArray {
    final List<JsonValue> items = new ArrayList<JsonValue>();

    int size() {
      return this.items.size();
    }

    JsonObject getObject(int index) {
      JsonValue v = this.items.get(index);
      return v == null ? null : v.asObject();
    }
  }

  /** A parsed JSON value, carrying its source substring for lazy extraction. */
  static final class JsonValue {
    final String raw;
    final String stringValue;
    final Number numberValue;
    final boolean booleanValue;
    final boolean isNull;
    final JsonObject objectValue;
    final JsonArray arrayValue;

    JsonValue(String raw, String stringValue, Number numberValue, boolean booleanValue,
        boolean isNull, JsonObject objectValue, JsonArray arrayValue) {
      this.raw = raw;
      this.stringValue = stringValue;
      this.numberValue = numberValue;
      this.booleanValue = booleanValue;
      this.isNull = isNull;
      this.objectValue = objectValue;
      this.arrayValue = arrayValue;
    }

    String asString() {
      if (this.stringValue != null) {
        return this.stringValue;
      }
      return null;
    }

    int asInt(int defaultValue) {
      if (this.numberValue != null) {
        return this.numberValue.intValue();
      }
      return defaultValue;
    }

    long asLong(long defaultValue) {
      if (this.numberValue != null) {
        return this.numberValue.longValue();
      }
      return defaultValue;
    }

    JsonArray asArray() {
      return this.arrayValue;
    }

    JsonObject asObject() {
      return this.objectValue;
    }
  }

  static final class JsonParseException extends Exception {
    JsonParseException(String message) {
      super(message);
    }
  }

  /** Simple recursive-descent JSON parser (object at the top level). */
  static final class JsonParser {
    private final String src;
    private int pos;

    private JsonParser(String src) {
      this.src = src;
      this.pos = 0;
    }

    static JsonObject parseObject(String src) throws JsonParseException {
      JsonParser p = new JsonParser(src);
      p.skipWs();
      if (p.pos >= src.length() || src.charAt(p.pos) != '{') {
        throw new JsonParseException("expected '{' at position " + p.pos);
      }
      p.pos++;
      JsonObject obj = new JsonObject();
      p.skipWs();
      if (p.peek() == '}') {
        p.pos++;
        return obj;
      }
      while (true) {
        p.skipWs();
        String key = p.readString();
        p.skipWs();
        if (p.peek() != ':') {
          throw new JsonParseException("expected ':' at position " + p.pos);
        }
        p.pos++;
        p.skipWs();
        JsonValue value = p.readValue();
        obj.fields.put(key, value);
        p.skipWs();
        char c = p.peek();
        if (c == ',') {
          p.pos++;
          continue;
        }
        if (c == '}') {
          p.pos++;
          return obj;
        }
        throw new JsonParseException("expected ',' or '}' at position " + p.pos);
      }
    }

    private char peek() {
      return this.pos >= this.src.length() ? '\0' : this.src.charAt(this.pos);
    }

    private void skipWs() {
      while (this.pos < this.src.length()) {
        char c = this.src.charAt(this.pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          this.pos++;
        } else {
          break;
        }
      }
    }

    private String readString() throws JsonParseException {
      if (this.peek() != '"') {
        throw new JsonParseException("expected '\"' at position " + this.pos);
      }
      this.pos++;
      StringBuilder sb = new StringBuilder();
      while (this.pos < this.src.length()) {
        char c = this.src.charAt(this.pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          if (this.pos >= this.src.length()) {
            throw new JsonParseException("unterminated escape");
          }
          char esc = this.src.charAt(this.pos++);
          switch (esc) {
            case '"': sb.append('"'); break;
            case '\\': sb.append('\\'); break;
            case '/': sb.append('/'); break;
            case 'b': sb.append('\b'); break;
            case 'f': sb.append('\f'); break;
            case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break;
            case 't': sb.append('\t'); break;
            case 'u':
              if (this.pos + 4 > this.src.length()) {
                throw new JsonParseException("bad \\u escape");
              }
              String hex = this.src.substring(this.pos, this.pos + 4);
              this.pos += 4;
              try {
                sb.append((char) Integer.parseInt(hex, 16));
              } catch (NumberFormatException e) {
                throw new JsonParseException("bad \\u escape: " + hex);
              }
              break;
            default:
              throw new JsonParseException("unknown escape: \\" + esc);
          }
        } else {
          sb.append(c);
        }
      }
      throw new JsonParseException("unterminated string");
    }

    private JsonValue readValue() throws JsonParseException {
      int start = this.pos;
      this.skipWs();
      char c = this.peek();
      if (c == '"') {
        String s = readString();
        return new JsonValue(this.src.substring(start, this.pos), s, null, false, false, null,
            null);
      }
      if (c == '{') {
        JsonObject obj = readObjectInline();
        return new JsonValue(this.src.substring(start, this.pos), null, null, false, false, obj,
            null);
      }
      if (c == '[') {
        JsonArray arr = readArrayInline();
        return new JsonValue(this.src.substring(start, this.pos), null, null, false, false, null,
            arr);
      }
      if (c == 't' || c == 'f') {
        String word = this.src.substring(this.pos);
        if (word.startsWith("true")) {
          this.pos += 4;
          return new JsonValue(this.src.substring(start, this.pos), null, null, true, false,
              null, null);
        }
        if (word.startsWith("false")) {
          this.pos += 5;
          return new JsonValue(this.src.substring(start, this.pos), null, null, false, false,
              null, null);
        }
        throw new JsonParseException("bad literal at " + this.pos);
      }
      if (c == 'n') {
        String word = this.src.substring(this.pos);
        if (word.startsWith("null")) {
          this.pos += 4;
          return new JsonValue(this.src.substring(start, this.pos), null, null, false, true,
              null, null);
        }
        throw new JsonParseException("bad literal at " + this.pos);
      }
      if (c == '-' || (c >= '0' && c <= '9')) {
        return readNumber(start);
      }
      throw new JsonParseException("unexpected char '" + c + "' at " + this.pos);
    }

    private JsonObject readObjectInline() throws JsonParseException {
      if (this.peek() != '{') {
        throw new JsonParseException("expected '{'");
      }
      this.pos++;
      JsonObject obj = new JsonObject();
      this.skipWs();
      if (this.peek() == '}') {
        this.pos++;
        return obj;
      }
      while (true) {
        this.skipWs();
        String key = readString();
        this.skipWs();
        if (this.peek() != ':') {
          throw new JsonParseException("expected ':'");
        }
        this.pos++;
        this.skipWs();
        JsonValue value = readValue();
        obj.fields.put(key, value);
        this.skipWs();
        char c = this.peek();
        if (c == ',') {
          this.pos++;
          continue;
        }
        if (c == '}') {
          this.pos++;
          return obj;
        }
        throw new JsonParseException("expected ',' or '}'");
      }
    }

    private JsonArray readArrayInline() throws JsonParseException {
      if (this.peek() != '[') {
        throw new JsonParseException("expected '['");
      }
      this.pos++;
      JsonArray arr = new JsonArray();
      this.skipWs();
      if (this.peek() == ']') {
        this.pos++;
        return arr;
      }
      while (true) {
        this.skipWs();
        JsonValue value = readValue();
        arr.items.add(value);
        this.skipWs();
        char c = this.peek();
        if (c == ',') {
          this.pos++;
          continue;
        }
        if (c == ']') {
          this.pos++;
          return arr;
        }
        throw new JsonParseException("expected ',' or ']'");
      }
    }

    private JsonValue readNumber(int start) throws JsonParseException {
      while (this.pos < this.src.length()) {
        char c = this.src.charAt(this.pos);
        if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
          this.pos++;
        } else {
          break;
        }
      }
      String numStr = this.src.substring(start, this.pos);
      try {
        if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
          return new JsonValue(numStr, null, Double.parseDouble(numStr), false, false, null,
              null);
        }
        // try long first, fall back to double
        try {
          long l = Long.parseLong(numStr);
          return new JsonValue(numStr, null, l, false, false, null, null);
        } catch (NumberFormatException ignored) {
          return new JsonValue(numStr, null, Double.parseDouble(numStr), false, false, null,
              null);
        }
      } catch (NumberFormatException e) {
        throw new JsonParseException("bad number: " + numStr);
      }
    }
  }
}
