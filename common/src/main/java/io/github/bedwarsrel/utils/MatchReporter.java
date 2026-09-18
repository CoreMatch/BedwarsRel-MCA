package io.github.bedwarsrel.utils;

import io.github.bedwarsrel.BedwarsRel;
import io.github.bedwarsrel.game.Game;
import io.github.bedwarsrel.game.Team;
import io.github.bedwarsrel.statistics.PlayerStatistic;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import org.bukkit.entity.Player;

public class MatchReporter {

  public static void report(final Game game, final Team winner) {
    if (winner == null) {
      return;
    }

    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          sendReport(game, winner);
        } catch (Exception e) {
          BedwarsRel.getInstance().getLogger().warning("Failed to report match: " + e.getMessage());
        }
      }
    }).start();
  }

  private static void sendReport(Game game, Team winner) throws IOException {
    String apiUrl = BedwarsRel.getInstance().getConfig().getString("match-reporting.api-url");
    String token = BedwarsRel.getInstance().getConfig().getString("match-reporting.access-token");

    if (apiUrl == null || token == null || token.equals("your-access-token-here")) {
      return;
    }

    URL url = new URL(apiUrl);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("Authorization", "Bearer " + token);
    conn.setDoOutput(true);

    String json = buildJson(game, winner);

    try (DataOutputStream os = new DataOutputStream(conn.getOutputStream())) {
      os.write(json.getBytes("UTF-8"));
      os.flush();
    }

    int responseCode = conn.getResponseCode();
    if (responseCode >= 400) {
      BedwarsRel.getInstance().getLogger().warning("Match report failed with code " + responseCode);
    }
  }

  private static String buildJson(Game game, Team winner) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    appendField(sb, "match_type", getMatchType(game), true);
    sb.append(",");
    
    // winner_team
    sb.append("\"winner_team\":");
    appendTeamInfo(sb, game, winner);
    sb.append(",");

    // loser_teams
    sb.append("\"loser_teams\":[");
    boolean firstTeam = true;
    for (Team team : game.getTeams().values()) {
      if (team.equals(winner)) {
        continue;
      }
      if (!firstTeam) {
        sb.append(",");
      }
      appendTeamInfo(sb, game, team);
      firstTeam = false;
    }
    sb.append("],");

    appendField(sb, "duration_seconds", (int) ((System.currentTimeMillis() - game.getStartedAt()) / 1000), false);
    sb.append(",");
    appendField(sb, "started_at", formatIso8601(game.getStartedAt()), true);
    sb.append(",");
    appendField(sb, "finished_at", formatIso8601(System.currentTimeMillis()), true);
    
    sb.append("}");
    return sb.toString();
  }

  private static void appendTeamInfo(StringBuilder sb, Game game, Team team) {
    sb.append("{");
    appendField(sb, "team_id", team.getName(), true);
    sb.append(",");
    appendField(sb, "initial_count", team.getMaxPlayers(), false);
    sb.append(",");
    appendField(sb, "alive_count", team.getPlayers().size(), false);
    sb.append(",");
    
    sb.append("\"members\":[");
    boolean firstMember = true;
    for (Map.Entry<Player, Team> entry : game.getParticipants().entrySet()) {
      if (!entry.getValue().equals(team)) {
        continue;
      }
      
      if (!firstMember) {
        sb.append(",");
      }
      
      Player player = entry.getKey();
      appendParticipantInfo(sb, game, player);
      firstMember = false;
    }
    sb.append("]");
    sb.append("}");
  }

  private static void appendParticipantInfo(StringBuilder sb, Game game, Player player) {
    sb.append("{");
    // User requested to use UUID as UID. Since API requires int64, we use the hash of UUID.
    // Or we can use getMostSignificantBits() which is a long.
    appendField(sb, "uid", player.getUniqueId().getMostSignificantBits(), false);
    sb.append(",");
    
    int survivalTime = game.getPlayerSurvivalTimes().containsKey(player) 
        ? game.getPlayerSurvivalTimes().get(player) 
        : (int) ((System.currentTimeMillis() - game.getStartedAt()) / 1000);
        
    appendField(sb, "survival_time", survivalTime, false);
    sb.append(",");
    
    PlayerStatistic stats = BedwarsRel.getInstance().getStatisticsManager().getStatistic(player);
    if (stats != null) {
      appendField(sb, "kills", stats.getCurrentKills(), false);
      sb.append(",");
      appendField(sb, "deaths", stats.getCurrentDeaths(), false);
    } else {
      appendField(sb, "kills", 0, false);
      sb.append(",");
      appendField(sb, "deaths", 0, false);
    }
    sb.append(",");
    appendField(sb, "perf_tweak", 1.0, false);
    sb.append("}");
  }

  private static void appendField(StringBuilder sb, String key, Object value, boolean quote) {
    sb.append("\"").append(key).append("\":");
    if (quote) {
      sb.append("\"");
    }
    sb.append(value);
    if (quote) {
      sb.append("\"");
    }
  }

  private static String getMatchType(Game game) {
    int teamCount = game.getTeams().size();
    int playersPerTeam = 0;
    if (teamCount > 0) {
      playersPerTeam = game.getTeams().values().iterator().next().getMaxPlayers();
    }
    
    StringBuilder type = new StringBuilder();
    for (int i = 0; i < teamCount; i++) {
      if (i > 0) {
        type.append("v");
      }
      type.append(playersPerTeam);
    }
    return type.toString();
  }

  private static String formatIso8601(long millis) {
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    return df.format(new Date(millis));
  }
}
