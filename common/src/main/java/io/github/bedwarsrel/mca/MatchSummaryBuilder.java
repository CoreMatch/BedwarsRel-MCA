package io.github.bedwarsrel.mca;

import io.github.bedwarsrel.BedwarsRel;
import io.github.bedwarsrel.game.Game;
import io.github.bedwarsrel.game.Team;
import io.github.bedwarsrel.statistics.PlayerStatistic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Builds {@link MatchSummary} instances.
 *
 * <p>{@link #build} is a pure function over plain data (used by unit tests),
 * while {@link #fromGame} is the Bukkit adapter that snapshots a live game.
 * The adapter must be called on the server main thread at game over, before
 * any cleanup mutates the game state.
 */
public final class MatchSummaryBuilder {

  /** Plain participant input, independent of Bukkit. */
  public static final class ParticipantInput {

    private final String uuid;
    private final String name;
    private final int survivalTimeSeconds;
    private final int kills;
    private final int deaths;

    public ParticipantInput(String uuid, String name, int survivalTimeSeconds, int kills,
        int deaths) {
      this.uuid = uuid;
      this.name = name;
      this.survivalTimeSeconds = survivalTimeSeconds;
      this.kills = kills;
      this.deaths = deaths;
    }

    public String getUuid() {
      return this.uuid;
    }

    public String getName() {
      return this.name;
    }

    public int getSurvivalTimeSeconds() {
      return this.survivalTimeSeconds;
    }

    public int getKills() {
      return this.kills;
    }

    public int getDeaths() {
      return this.deaths;
    }
  }

  /** Plain team input, independent of Bukkit. */
  public static final class TeamInput {

    private final String teamId;
    private final int initialCount;
    private final int aliveCount;
    private final boolean winner;
    private final List<ParticipantInput> members;

    public TeamInput(String teamId, int initialCount, int aliveCount, boolean winner,
        List<ParticipantInput> members) {
      this.teamId = teamId;
      this.initialCount = initialCount;
      this.aliveCount = aliveCount;
      this.winner = winner;
      this.members = members == null
          ? Collections.<ParticipantInput>emptyList()
          : new ArrayList<ParticipantInput>(members);
    }

    public String getTeamId() {
      return this.teamId;
    }

    public int getInitialCount() {
      return this.initialCount;
    }

    public int getAliveCount() {
      return this.aliveCount;
    }

    public boolean isWinner() {
      return this.winner;
    }

    public List<ParticipantInput> getMembers() {
      return this.members;
    }
  }

  private MatchSummaryBuilder() {
  }

  /**
   * Pure summary construction from plain data.
   *
   * <p>Determinism guarantees: members are sorted by UUID, teams are ordered
   * winner-first then by team id, and the match type string is derived from
   * team sizes in team-id order. The same logical game therefore always
   * produces the same summary and game id.
   */
  public static MatchSummary build(String mapName, long startedAtMillis, long finishedAtMillis,
      List<TeamInput> teamInputs) {
    if (teamInputs == null || teamInputs.isEmpty()) {
      throw new IllegalArgumentException("a match summary needs at least one team");
    }

    long started = startedAtMillis;
    long finished = Math.max(finishedAtMillis, started);

    List<MatchSummary.TeamResult> teams =
        new ArrayList<MatchSummary.TeamResult>(teamInputs.size());
    for (TeamInput input : teamInputs) {
      teams.add(toTeamResult(input));
    }

    Collections.sort(teams, new Comparator<MatchSummary.TeamResult>() {
      @Override
      public int compare(MatchSummary.TeamResult a, MatchSummary.TeamResult b) {
        // winner first, then deterministic by team id
        if (a.isWinner() != b.isWinner()) {
          return a.isWinner() ? -1 : 1;
        }
        return a.getTeamId().compareTo(b.getTeamId());
      }
    });

    String matchType = buildMatchType(teams);
    String gameId = GameIdGenerator.generate(mapName, matchType, started, teams);
    return new MatchSummary(gameId, mapName == null ? "" : mapName, matchType, started, finished,
        teams);
  }

  /**
   * Snapshots a live game at game over. Must run on the server main thread:
   * it reads participants, team rosters and per-player statistics that are
   * mutated once the game-over sequence continues.
   */
  public static MatchSummary fromGame(Game game, Team winner, long finishedAtMillis) {
    long startedAt = game.getStartedAt();
    if (startedAt <= 0L) {
      startedAt = finishedAtMillis;
    }
    int fallbackSurvival = (int) Math.max(0L, (finishedAtMillis - startedAt) / 1000L);

    Map<Player, Integer> survivalTimes = game.getPlayerSurvivalTimes();
    Map<Player, Team> participants = game.getParticipants();
    boolean statisticsEnabled = BedwarsRel.getInstance().statisticsEnabled();

    List<TeamInput> teamInputs = new ArrayList<TeamInput>();
    for (Team team : game.getTeams().values()) {
      List<ParticipantInput> members = new ArrayList<ParticipantInput>();
      for (Map.Entry<Player, Team> entry : participants.entrySet()) {
        if (!team.equals(entry.getValue())) {
          continue;
        }
        members.add(toParticipantInput(entry.getKey(), survivalTimes, fallbackSurvival,
            statisticsEnabled));
      }

      if (members.isEmpty()) {
        // configured team that never had players: nothing to report for it
        continue;
      }

      int initialCount = members.size();
      int aliveCount = Math.min(team.getPlayers().size(), initialCount);
      teamInputs.add(new TeamInput(team.getName(), initialCount, aliveCount,
          winner != null && team.equals(winner), members));
    }

    if (teamInputs.isEmpty()) {
      throw new IllegalArgumentException("game has no participants to report");
    }

    return build(game.getRegionName(), startedAt, finishedAtMillis, teamInputs);
  }

  private static ParticipantInput toParticipantInput(Player player,
      Map<Player, Integer> survivalTimes, int fallbackSurvival, boolean statisticsEnabled) {
    Integer survival = survivalTimes == null ? null : survivalTimes.get(player);
    int survivalSeconds = survival != null ? Math.max(0, survival.intValue()) : fallbackSurvival;

    int kills = 0;
    int deaths = 0;
    if (statisticsEnabled) {
      PlayerStatistic statistic =
          BedwarsRel.getInstance().getPlayerStatisticManager().getStatistic(player);
      if (statistic != null) {
        kills = Math.max(0, statistic.getCurrentKills());
        deaths = Math.max(0, statistic.getCurrentDeaths());
      }
    }

    return new ParticipantInput(player.getUniqueId().toString(), player.getName(),
        survivalSeconds, kills, deaths);
  }

  private static MatchSummary.TeamResult toTeamResult(TeamInput input) {
    List<MatchSummary.Participant> members =
        new ArrayList<MatchSummary.Participant>(input.getMembers().size());
    for (ParticipantInput member : input.getMembers()) {
      members.add(new MatchSummary.Participant(member.getUuid(), member.getName(),
          Math.max(0, member.getSurvivalTimeSeconds()), Math.max(0, member.getKills()),
          Math.max(0, member.getDeaths())));
    }
    Collections.sort(members, new Comparator<MatchSummary.Participant>() {
      @Override
      public int compare(MatchSummary.Participant a, MatchSummary.Participant b) {
        return a.getUuid().compareTo(b.getUuid());
      }
    });

    int initialCount = Math.max(input.getInitialCount(), members.size());
    initialCount = Math.max(initialCount, 1);
    int aliveCount = Math.max(0, Math.min(input.getAliveCount(), initialCount));

    return new MatchSummary.TeamResult(input.getTeamId(), initialCount, aliveCount,
        input.isWinner(), members);
  }

  /** Match type label such as "1v1", "4v4" or "4v4v4v4". */
  private static String buildMatchType(List<MatchSummary.TeamResult> teams) {
    List<MatchSummary.TeamResult> byId =
        new ArrayList<MatchSummary.TeamResult>(teams);
    Collections.sort(byId, new Comparator<MatchSummary.TeamResult>() {
      @Override
      public int compare(MatchSummary.TeamResult a, MatchSummary.TeamResult b) {
        return a.getTeamId().compareTo(b.getTeamId());
      }
    });

    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (MatchSummary.TeamResult team : byId) {
      if (!first) {
        sb.append('v');
      }
      first = false;
      sb.append(team.getInitialCount());
    }
    return sb.toString();
  }
}
