package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.bedwarsrel.mca.MatchSummaryBuilder.ParticipantInput;
import io.github.bedwarsrel.mca.MatchSummaryBuilder.TeamInput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class MatchSummaryBuilderTest {

  private static ParticipantInput player(String uuid, int survival, int kills, int deaths) {
    return new ParticipantInput(uuid, "name-" + uuid, survival, kills, deaths);
  }

  private static TeamInput team(String id, boolean winner, int alive, ParticipantInput... members) {
    return new TeamInput(id, members.length, alive, winner, Arrays.asList(members));
  }

  @Test
  public void mapsTeamsAndMembersIntoContractShape() {
    long started = 1_700_000_000_000L;
    long finished = started + 90_000L;

    MatchSummary summary = MatchSummaryBuilder.build("arena1", started, finished,
        Arrays.asList(
            team("blue", false, 0, player("uu-2", 40, 1, 2), player("uu-1", 35, 0, 1)),
            team("red", true, 1, player("uu-4", 90, 3, 0), player("uu-3", 20, 0, 1))));

    assertEquals("arena1", summary.getMapName());
    assertEquals("2v2", summary.getMatchType());
    assertEquals(started, summary.getStartedAtMillis());
    assertEquals(finished, summary.getFinishedAtMillis());
    assertEquals(90, summary.getDurationSeconds());

    // winner first
    assertEquals("red", summary.getTeams().get(0).getTeamId());
    assertTrue(summary.getTeams().get(0).isWinner());
    assertEquals("blue", summary.getTeams().get(1).getTeamId());

    // members sorted by uuid for deterministic payloads and ids
    List<MatchSummary.Participant> red = summary.getWinnerTeam().getMembers();
    assertEquals("uu-3", red.get(0).getUuid());
    assertEquals("uu-4", red.get(1).getUuid());
    assertEquals(20, red.get(0).getSurvivalTimeSeconds());
    assertEquals(3, red.get(1).getKills());

    assertEquals(2, summary.getWinnerTeam().getInitialCount());
    assertEquals(1, summary.getWinnerTeam().getAliveCount());
    assertEquals(1, summary.getLoserTeams().size());
  }

  @Test
  public void summaryIsDeterministicAcrossObservationOrders() {
    long started = 1_700_000_000_000L;
    long finished = started + 60_000L;

    MatchSummary first = MatchSummaryBuilder.build("m", started, finished,
        Arrays.asList(
            team("a", true, 2, player("u2", 10, 0, 0), player("u1", 10, 0, 0)),
            team("b", false, 0, player("u4", 10, 0, 0), player("u3", 10, 0, 0))));
    MatchSummary second = MatchSummaryBuilder.build("m", started, finished,
        Arrays.asList(
            team("b", false, 0, player("u3", 10, 0, 0), player("u4", 10, 0, 0)),
            team("a", true, 2, player("u1", 10, 0, 0), player("u2", 10, 0, 0))));

    assertEquals(first.getGameId(), second.getGameId());
    assertEquals(first.getMatchType(), second.getMatchType());
  }

  @Test
  public void countsAreClampedToSaneBounds() {
    MatchSummary summary = MatchSummaryBuilder.build("m", 1000L, 2000L,
        Arrays.asList(
            // alive > initial, negative stats, initial underreported
            new TeamInput("red", 1, 5, true,
                Arrays.asList(player("u1", -5, -1, -2), player("u2", 1, 0, 0))),
            team("blue", false, 0, player("u3", 1, 0, 0))));

    MatchSummary.TeamResult red = summary.getWinnerTeam();
    assertEquals(2, red.getInitialCount());
    assertEquals(2, red.getAliveCount());
    assertEquals(0, red.getMembers().get(0).getSurvivalTimeSeconds());
    assertEquals(0, red.getMembers().get(0).getKills());
    assertEquals(0, red.getMembers().get(0).getDeaths());
  }

  @Test
  public void finishedBeforeStartedClampsToZeroDuration() {
    MatchSummary summary = MatchSummaryBuilder.build("m", 5000L, 4000L,
        Arrays.asList(
            team("red", true, 1, player("u1", 1, 0, 0)),
            team("blue", false, 0, player("u2", 1, 0, 0))));

    assertEquals(0, summary.getDurationSeconds());
    assertEquals(5000L, summary.getFinishedAtMillis());
  }

  @Test
  public void matchTypeUsesEachTeamSize() {
    MatchSummary summary = MatchSummaryBuilder.build("m", 1000L, 2000L,
        Arrays.asList(
            team("red", true, 1, player("u1", 1, 0, 0)),
            team("blue", false, 0, player("u2", 1, 0, 0), player("u3", 1, 0, 0)),
            team("green", false, 0, player("u4", 1, 0, 0))));

    // team sizes in deterministic team-id order: blue(2), green(1), red(1)
    assertEquals("2v1v1", summary.getMatchType());
  }

  @Test
  public void noWinnerMeansNoWinnerTeam() {
    MatchSummary summary = MatchSummaryBuilder.build("m", 1000L, 2000L,
        Arrays.asList(
            team("red", false, 0, player("u1", 1, 0, 0)),
            team("blue", false, 0, player("u2", 1, 0, 0))));

    assertNull(summary.getWinnerTeam());
    assertEquals(2, summary.getLoserTeams().size());
  }

  @Test
  public void emptyTeamListIsRejected() {
    try {
      MatchSummaryBuilder.build("m", 1000L, 2000L,
          Collections.<TeamInput>emptyList());
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void gameIdIsStableAndNonNull() {
    List<TeamInput> teams = new ArrayList<TeamInput>(Arrays.asList(
        team("red", true, 1, player("u1", 1, 2, 0)),
        team("blue", false, 0, player("u2", 1, 0, 1))));

    MatchSummary first = MatchSummaryBuilder.build("m", 1000L, 2000L, teams);
    MatchSummary second = MatchSummaryBuilder.build("m", 1000L, 2000L, teams);

    assertEquals(first.getGameId(), second.getGameId());
    assertTrue(first.getGameId().matches("[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}"));
  }
}
