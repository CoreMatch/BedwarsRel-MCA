package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
  public void payloadCarriesExactlyTheContractFields() {
    long started = 1_700_000_000_000L; // 2023-11-14T22:13:20Z
    long finished = started + 90_000L;

    String json = MatchReportPayload.toJson(summary("arena1", started, finished));

    assertTrue(json.contains("\"match_type\":\"2v2\""));
    assertTrue(json.contains("\"duration_seconds\":90"));
    assertTrue(json.contains("\"started_at\":\"2023-11-14T22:13:20Z\""));
    assertTrue(json.contains("\"finished_at\":\"2023-11-14T22:14:50Z\""));

    // winner_team / loser_teams structure
    int winnerStart = json.indexOf("\"winner_team\":{");
    assertTrue(winnerStart >= 0);
    assertTrue(json.contains("\"loser_teams\":["));
    assertTrue(json.contains("\"team_id\":\"red\""));
    assertTrue(json.contains("\"team_id\":\"blue\""));
    assertTrue(json.contains("\"initial_count\":2"));
    assertTrue(json.contains("\"alive_count\":1"));
    assertTrue(json.contains("\"uuid\":\"11111111-1111-3111-8111-111111111111\""));
    assertTrue(json.contains("\"survival_time\":90"));
    assertTrue(json.contains("\"kills\":3"));
    assertTrue(json.contains("\"deaths\":1"));
    assertTrue(json.contains("\"perf_tweak\":1.0"));

    // the map name is aggregated but must not be sent: the contract input
    // schema of POST /api/matches has no map field
    assertFalse(json.contains("map_name"));
    assertFalse(json.contains("arena1"));

    // game_id is the stable server-side idempotency key: present and UUID-shaped
    assertTrue(json.contains("\"game_id\":\""));
    int gidStart = json.indexOf("\"game_id\":\"") + "\"game_id\":\"".length();
    String gid = json.substring(gidStart, json.indexOf('"', gidStart));
    assertTrue(gid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
  }

  @Test
  public void winnerTeamComesBeforeLoserTeams() {
    String json = MatchReportPayload.toJson(summary("m", 1000L, 2000L));
    assertTrue(json.indexOf("\"winner_team\"") < json.indexOf("\"loser_teams\""));
    // the winner block contains the red team id
    int winnerBlockEnd = json.indexOf("\"loser_teams\"");
    assertTrue(json.substring(0, winnerBlockEnd).contains("\"team_id\":\"red\""));
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
  public void matchWithoutWinnerIsRejected() {
    MatchSummary draw = MatchSummaryBuilder.build("m", 1000L, 2000L, Arrays.asList(
        new TeamInput("red", 1, 0, false, Arrays.asList(
            new ParticipantInput("u1", "n1", 1, 0, 0))),
        new TeamInput("blue", 1, 0, false, Arrays.asList(
            new ParticipantInput("u2", "n2", 1, 0, 0)))));
    try {
      MatchReportPayload.toJson(draw);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // expected: POST /api/matches requires a winner_team
    }
  }
}
