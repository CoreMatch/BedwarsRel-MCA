package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class GameIdGeneratorTest {

  private static MatchSummary.TeamResult team(String id, boolean winner, String... uuids) {
    List<MatchSummary.Participant> members = new ArrayList<MatchSummary.Participant>();
    for (String uuid : uuids) {
      members.add(new MatchSummary.Participant(uuid, "p-" + uuid, 60, 0, 0));
    }
    return new MatchSummary.TeamResult(id, uuids.length, uuids.length, winner, members);
  }

  @Test
  public void sameGameProducesSameId() {
    List<MatchSummary.TeamResult> teams = Arrays.asList(
        team("red", true, "aaaa", "bbbb"),
        team("blue", false, "cccc", "dddd"));

    String first = GameIdGenerator.generate("map1", "2v2", 1000L, teams);
    String second = GameIdGenerator.generate("map1", "2v2", 1000L, teams);

    assertEquals(first, second);
  }

  @Test
  public void idIsIndependentOfTeamAndMemberOrder() {
    List<MatchSummary.TeamResult> orderA = Arrays.asList(
        team("red", true, "aaaa", "bbbb"),
        team("blue", false, "cccc", "dddd"));
    // same game, teams and members observed in a different order
    List<MatchSummary.TeamResult> orderB = Arrays.asList(
        team("blue", false, "dddd", "cccc"),
        team("red", true, "bbbb", "aaaa"));

    assertEquals(
        GameIdGenerator.generate("map1", "2v2", 1000L, orderA),
        GameIdGenerator.generate("map1", "2v2", 1000L, orderB));
  }

  @Test
  public void differentGamesProduceDifferentIds() {
    List<MatchSummary.TeamResult> teams = Arrays.asList(
        team("red", true, "aaaa"),
        team("blue", false, "bbbb"));

    String base = GameIdGenerator.generate("map1", "1v1", 1000L, teams);
    // a later game with the same players on the same map is a different game
    String laterStart = GameIdGenerator.generate("map1", "1v1", 2000L, teams);
    String otherMap = GameIdGenerator.generate("map2", "1v1", 1000L, teams);
    String otherPlayers = GameIdGenerator.generate("map1", "1v1", 1000L,
        Arrays.asList(team("red", true, "aaaa"), team("blue", false, "cccc")));

    assertFalse(base.equals(laterStart));
    assertFalse(base.equals(otherMap));
    assertFalse(base.equals(otherPlayers));
  }

  @Test
  public void nullMapNameIsTolerated() {
    List<MatchSummary.TeamResult> teams = Arrays.asList(
        team("red", true, "aaaa"),
        team("blue", false, "bbbb"));

    String withNull = GameIdGenerator.generate(null, "1v1", 1000L, teams);
    String withEmpty = GameIdGenerator.generate("", "1v1", 1000L, teams);

    assertEquals(withEmpty, withNull);
  }

  @Test
  public void emptyTeamListStillYieldsStableId() {
    List<MatchSummary.TeamResult> none = Collections.emptyList();
    assertEquals(
        GameIdGenerator.generate("m", "1v1", 5L, none),
        GameIdGenerator.generate("m", "1v1", 5L, none));
  }
}
