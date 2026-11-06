package io.github.bedwarsrel.mca;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of one finished Bedwars game.
 *
 * <p>The snapshot is assembled synchronously on the server main thread at
 * game over, before any game-state cleanup happens, and is then handed to
 * the asynchronous report pipeline. The reporting thread never touches
 * live game state.
 */
public final class MatchSummary {

  /** Per-game data of one participant, identified by Minecraft UUID. */
  public static final class Participant {

    private final String uuid;
    private final String name;
    private final int survivalTimeSeconds;
    private final int kills;
    private final int deaths;

    public Participant(String uuid, String name, int survivalTimeSeconds, int kills, int deaths) {
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

  /**
   * Result of one team. Members are kept in a deterministic order
   * (sorted by UUID) so that payloads and idempotency keys are stable.
   */
  public static final class TeamResult {

    private final String teamId;
    private final int initialCount;
    private final int aliveCount;
    private final boolean winner;
    private final List<Participant> members;

    public TeamResult(String teamId, int initialCount, int aliveCount, boolean winner,
        List<Participant> members) {
      this.teamId = teamId;
      this.initialCount = initialCount;
      this.aliveCount = aliveCount;
      this.winner = winner;
      this.members = Collections.unmodifiableList(new ArrayList<Participant>(members));
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

    public List<Participant> getMembers() {
      return this.members;
    }
  }

  private final String gameId;
  private final String mapName;
  private final String matchType;
  private final long startedAtMillis;
  private final long finishedAtMillis;
  private final List<TeamResult> teams;

  MatchSummary(String gameId, String mapName, String matchType, long startedAtMillis,
      long finishedAtMillis, List<TeamResult> teams) {
    this.gameId = gameId;
    this.mapName = mapName;
    this.matchType = matchType;
    this.startedAtMillis = startedAtMillis;
    this.finishedAtMillis = finishedAtMillis;
    this.teams = Collections.unmodifiableList(new ArrayList<TeamResult>(teams));
  }

  /** Stable idempotency key of this game, see {@link GameIdGenerator}. */
  public String getGameId() {
    return this.gameId;
  }

  public String getMapName() {
    return this.mapName;
  }

  public String getMatchType() {
    return this.matchType;
  }

  public long getStartedAtMillis() {
    return this.startedAtMillis;
  }

  public long getFinishedAtMillis() {
    return this.finishedAtMillis;
  }

  /**
   * Duration in whole seconds, derived from the same second-truncated
   * values used for the RFC 3339 timestamps: HA-Contract v1.4.0 rejects a
   * positive {@code duration_seconds} that disagrees with
   * {@code finished_at - started_at} (400 mca_report_inconsistent).
   */
  public int getDurationSeconds() {
    return (int) Math.max(0L, this.finishedAtMillis / 1000L - this.startedAtMillis / 1000L);
  }

  /** All teams: the winning team first (if any), then losers sorted by team id. */
  public List<TeamResult> getTeams() {
    return this.teams;
  }

  /** The winning team, or {@code null} when the summary has no winner. */
  public TeamResult getWinnerTeam() {
    for (TeamResult team : this.teams) {
      if (team.isWinner()) {
        return team;
      }
    }
    return null;
  }

  /** All teams that did not win, sorted by team id. */
  public List<TeamResult> getLoserTeams() {
    List<TeamResult> losers = new ArrayList<TeamResult>();
    for (TeamResult team : this.teams) {
      if (!team.isWinner()) {
        losers.add(team);
      }
    }
    return losers;
  }
}
