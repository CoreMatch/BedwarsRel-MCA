package io.github.bedwarsrel.mca;

import java.text.SimpleDateFormat;
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
}
