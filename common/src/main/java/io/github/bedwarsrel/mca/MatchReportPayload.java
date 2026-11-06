package io.github.bedwarsrel.mca;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Serializes a {@link MatchSummary} into the exact JSON body of the
 * HA-Contract {@code POST /api/matches} request ({@code TeamReportInput}).
 *
 * <p>Emitted fields: {@code match_type}, {@code game_id} (server-side
 * idempotency key), {@code winner_team}, {@code loser_teams},
 * {@code duration_seconds}, {@code started_at} and {@code finished_at}. The
 * map name is intentionally not sent because the game-report input schema has
 * no map field (see PENDING-DECISIONS.md). {@code game_id} lets the server
 * reject duplicate settlements (identical re-replays return the existing
 * record; conflicting reuse is rejected).
 */
public final class MatchReportPayload {

  private MatchReportPayload() {
  }

  public static String toJson(MatchSummary summary) {
    MatchSummary.TeamResult winner = summary.getWinnerTeam();
    if (winner == null) {
      throw new IllegalArgumentException(
          "cannot serialize a match without a winner for POST /api/matches");
    }

    StringBuilder sb = new StringBuilder(512);
    sb.append('{');
    appendStringField(sb, "match_type", summary.getMatchType());
    sb.append(',');
    appendStringField(sb, "game_id", summary.getGameId());
    sb.append(',');
    sb.append("\"winner_team\":");
    appendTeam(sb, winner);
    sb.append(',');
    sb.append("\"loser_teams\":[");
    List<MatchSummary.TeamResult> losers = summary.getLoserTeams();
    for (int i = 0; i < losers.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      appendTeam(sb, losers.get(i));
    }
    sb.append("],");
    appendNumberField(sb, "duration_seconds", summary.getDurationSeconds());
    sb.append(',');
    appendStringField(sb, "started_at", formatRfc3339Utc(summary.getStartedAtMillis()));
    sb.append(',');
    appendStringField(sb, "finished_at", formatRfc3339Utc(summary.getFinishedAtMillis()));
    sb.append('}');
    return sb.toString();
  }

  private static void appendTeam(StringBuilder sb, MatchSummary.TeamResult team) {
    sb.append('{');
    appendStringField(sb, "team_id", team.getTeamId());
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
