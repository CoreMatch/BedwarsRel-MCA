package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.bedwarsrel.mca.MatchSummaryBuilder.ParticipantInput;
import io.github.bedwarsrel.mca.MatchSummaryBuilder.TeamInput;
import java.util.Arrays;
import org.junit.Test;

public class MatchReportPayloadTest {

  private static MatchSummary summary(String mapName, long started, long finished) {
    return MatchSummaryBuilder.build(mapName, started, finished, Arrays.asList(
        new TeamInput("red", 2, 1, true, Arrays.asList(
            new ParticipantInput("11111111-1111-3111-8111-111111111111", "Alice", 90, 3, 1),
            new ParticipantInput("22222222-2222-3222-8222-222222222222", "Bob", 20, 0, 1))),
        new TeamInput("blue", 2, 0, false, Arrays.asList(
            new ParticipantInput("33333333-3333-3333-8333-333333333333", "Carol", 45, 1, 2)))));
  }

  @Test
  public void payloadCarriesTheUnifiedContractShape() {
    long started = 1_700_000_000_000L; // 2023-11-14T22:13:20Z
    long finished = started + 90_000L;

    MatchSummary summary = summary("arena1", started, finished);
    String json = MatchReportPayload.toJson(summary);

    assertTrue(json.contains("\"match_type\":\"2v2\""));
    assertTrue(json.contains("\"teams\":["));
    assertTrue(json.contains("\"team_id\":\"red\""));
    assertTrue(json.contains("\"result\":\"win\""));
    assertTrue(json.contains("\"team_id\":\"blue\""));
    assertTrue(json.contains("\"result\":\"lose\""));
    assertTrue(json.contains("\"initial_count\":2"));
    assertTrue(json.contains("\"alive_count\":1"));
    assertTrue(json.contains("\"uuid\":\"11111111-1111-3111-8111-111111111111\""));
    assertTrue(json.contains("\"survival_time\":90"));
    assertTrue(json.contains("\"kills\":3"));
    assertTrue(json.contains("\"deaths\":1"));
    assertTrue(json.contains("\"perf_tweak\":1.0"));
    assertTrue(json.contains("\"duration_seconds\":90"));
    assertTrue(json.contains("\"started_at\":\"2023-11-14T22:13:20Z\""));
    assertTrue(json.contains("\"finished_at\":\"2023-11-14T22:14:50Z\""));

    // v1.4.0 alignment: map name and the idempotency key are transmitted,
    // the report is marked as game-sourced
    assertTrue(json.contains("\"map_name\":\"arena1\""));
    assertTrue(json.contains("\"game_id\":\"" + summary.getGameId() + "\""));
    assertTrue(json.contains("\"source\":\"game\""));

    // the legacy shape must not be used anymore
    assertFalse(json.contains("winner_team"));
    assertFalse(json.contains("loser_teams"));
  }

  @Test
  public void winningTeamComesFirstInTheTeamsArray() {
    String json = MatchReportPayload.toJson(summary("m", 1000L, 2000L));
    int red = json.indexOf("\"team_id\":\"red\"");
    int blue = json.indexOf("\"team_id\":\"blue\"");
    assertTrue(red >= 0 && blue >= 0);
    assertTrue("winner must be listed first", red < blue);
  }

  @Test
  public void gameWithoutWinnerIsReportedAsAllDraw() {
    MatchSummary draw = MatchSummaryBuilder.build("m", 1000L, 61_000L, Arrays.asList(
        new TeamInput("red", 1, 0, false, Arrays.asList(
            new ParticipantInput("u1", "n1", 60, 1, 1))),
        new TeamInput("blue", 1, 0, false, Arrays.asList(
            new ParticipantInput("u2", "n2", 55, 1, 1)))));

    String json = MatchReportPayload.toJson(draw);

    assertFalse(json.contains("\"result\":\"win\""));
    assertFalse(json.contains("\"result\":\"lose\""));
    // both teams are draws
    int first = json.indexOf("\"result\":\"draw\"");
    int second = json.indexOf("\"result\":\"draw\"", first + 1);
    assertTrue(first >= 0);
    assertTrue(second > first);
  }

  @Test
  public void allDrawPayloadIsSymmetricSortedByTeamId() {
    // When no winner, teams are NOT winner-first sorted — they fall through
    // to team-id alphabetical order (blue before red). This test verifies
    // the sorting falls back to team-id ordering for all-draw matches.
    MatchSummary draw = MatchSummaryBuilder.build("m", 1000L, 61_000L, Arrays.asList(
        // intentionally pass red first so we can see if sorting changes it
        new TeamInput("red", 1, 0, false, Arrays.asList(
            new ParticipantInput("u1", "n1", 60, 1, 1))),
        new TeamInput("blue", 1, 0, false, Arrays.asList(
            new ParticipantInput("u2", "n2", 55, 1, 1)))));

    String json = MatchReportPayload.toJson(draw);
    int blue = json.indexOf("\"team_id\":\"blue\"");
    int red = json.indexOf("\"team_id\":\"red\"");
    assertTrue("team-id alphabetical order must be preserved when no winner",
        blue < red);
  }

  @Test
  public void threeWayAllDrawCarriesAllDraws() {
    MatchSummary draw = MatchSummaryBuilder.build("m", 1000L, 61_000L, Arrays.asList(
        new TeamInput("alpha", 1, 0, false, Arrays.asList(
            new ParticipantInput("u1", "n1", 60, 1, 1))),
        new TeamInput("beta", 1, 0, false, Arrays.asList(
            new ParticipantInput("u2", "n2", 55, 1, 1))),
        new TeamInput("gamma", 1, 0, false, Arrays.asList(
            new ParticipantInput("u3", "n3", 50, 2, 2)))));

    String json = MatchReportPayload.toJson(draw);

    assertFalse(json.contains("\"result\":\"win\""));
    assertFalse(json.contains("\"result\":\"lose\""));

    int count = 0;
    int idx = 0;
    while ((idx = json.indexOf("\"result\":\"draw\"", idx)) >= 0) {
      count++;
      idx += 1;
    }
    assertEquals("all 3 teams must report draw", 3, count);
  }

  @Test
  public void allDrawWithMatchmakingGroupIdRoundTrips() {
    // All-draw payload must still carry matchmaking_group_id when present,
    // so MCA settlement can release the room reservation even on draws.
    MatchSummary draw = MatchSummaryBuilder.build("m", 1000L, 61_000L, Arrays.asList(
        new TeamInput("red", 1, 0, false, Arrays.asList(
            new ParticipantInput("u1", "n1", 60, 1, 1))),
        new TeamInput("blue", 1, 0, false, Arrays.asList(
            new ParticipantInput("u2", "n2", 55, 1, 1)))),
        12345L);

    String json = MatchReportPayload.toJson(draw);
    assertTrue("all-draw + group id must echo matchmaking_group_id",
        json.contains("\"matchmaking_group_id\":12345"));

    MatchSummary roundTripped = MatchReportPayload.fromJson(json);
    assertNotNull(roundTripped);
    assertEquals(12345L, roundTripped.getMatchmakingGroupId());
    assertEquals(2, roundTripped.getTeams().size());
    assertFalse("no team should be marked winner on all-draw",
        roundTripped.getTeams().get(0).isWinner()
            || roundTripped.getTeams().get(1).isWinner());
  }

  @Test
  public void durationMatchesTheTruncatedTimestamps() {
    // started 500ms after an epoch second, finished 89.7s later: the raw
    // millisecond difference floors to 89, but the RFC 3339 timestamps
    // (second-truncated) differ by 90 — and HA-Contract v1.4.0 rejects a
    // positive duration_seconds that disagrees with the timestamp diff.
    long started = 1_700_000_001_500L;
    long finished = started + 89_700L;

    MatchSummary summary = summary("m", started, finished);
    String json = MatchReportPayload.toJson(summary);

    long timestampDiff = finished / 1000L - started / 1000L;
    assertEquals(90L, timestampDiff);
    assertEquals(89L, (finished - started) / 1000L); // the naive value would be wrong
    assertTrue(json.contains("\"duration_seconds\":90"));
  }

  @Test
  public void stringsAreEscaped() {
    MatchSummary summary = MatchSummaryBuilder.build("map \"x\"\n\\", 1000L, 2000L,
        Arrays.asList(
            new TeamInput("red \"q\"", 1, 1, true, Arrays.asList(
                new ParticipantInput("u1", "n1", 1, 0, 0))),
            new TeamInput("blue", 1, 0, false, Arrays.asList(
                new ParticipantInput("u2", "n2", 1, 0, 0)))));

    String json = MatchReportPayload.toJson(summary);

    assertTrue(json.contains("\\\"q\\\""));
    assertFalse(json.contains("\n"));
  }

  @Test
  public void escapeHandlesControlCharacters() {
    assertEquals("a\\u0001b", MatchReportPayload.escape("a\u0001b"));
    assertEquals("tab\\there", MatchReportPayload.escape("tab\there"));
    assertEquals("", MatchReportPayload.escape(null));
  }

  @Test
  public void rfc3339UsesUtc() {
    assertEquals("1970-01-01T00:00:00Z", MatchReportPayload.formatRfc3339Utc(0L));
    assertEquals("2023-11-14T22:13:20Z", MatchReportPayload.formatRfc3339Utc(1_700_000_000_000L));
  }

  @Test
  public void matchmakingGroupIdIsEchoedWhenPresent() {
    MatchSummary withGroup = MatchSummaryBuilder.build("arena1", 1000L, 61_000L, Arrays.asList(
        new TeamInput("red", 1, 1, true, Arrays.asList(
            new ParticipantInput("u1", "n1", 60, 1, 0))),
        new TeamInput("blue", 1, 0, false, Arrays.asList(
            new ParticipantInput("u2", "n2", 30, 0, 1)))),
        42L);

    String json = MatchReportPayload.toJson(withGroup);
    assertTrue(json.contains("\"matchmaking_group_id\":42"));

    // games without an MCA assignment keep the pre-1.10.0 payload shape
    MatchSummary withoutGroup = MatchSummaryBuilder.build("arena1", 1000L, 61_000L, Arrays.asList(
        new TeamInput("red", 1, 1, true, Arrays.asList(
            new ParticipantInput("u1", "n1", 60, 1, 0))),
        new TeamInput("blue", 1, 0, false, Arrays.asList(
            new ParticipantInput("u2", "n2", 30, 0, 1)))));
    assertFalse(MatchReportPayload.toJson(withoutGroup).contains("matchmaking_group_id"));
  }

  @Test
  public void deadLetterRoundTripPreservesMatchmakingGroupId() {
    MatchSummary withGroup = MatchSummaryBuilder.build("arena1", 1000L, 61_000L, Arrays.asList(
        new TeamInput("red", 1, 1, true, Arrays.asList(
            new ParticipantInput("u1", "n1", 60, 1, 0))),
        new TeamInput("blue", 1, 0, false, Arrays.asList(
            new ParticipantInput("u2", "n2", 30, 0, 1)))),
        777L);

    MatchSummary parsed = MatchReportPayload.fromJson(MatchReportPayload.toJson(withGroup));

    assertEquals(777L, parsed.getMatchmakingGroupId());
    assertEquals(withGroup.getGameId(), parsed.getGameId());
  }
}
