package io.github.bedwarsrel.mca;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Derives the stable idempotency key ({@code game_id}) of a finished game.
 *
 * <p>The MatchCoreArena settlement endpoint (HA-Contract
 * {@code POST /api/matches}) performs no deduplication and accepts no
 * client-supplied idempotency key, so repeated reports of the same game
 * would settle ratings twice. The plugin therefore keys every game by a
 * deterministic id derived from identity-bearing facts only (start time,
 * map, team composition) — never from volatile values such as kill counts —
 * and refuses to report a game whose id was already recorded as reported.
 */
public final class GameIdGenerator {

  private static final Charset UTF8 = Charset.forName("UTF-8");

  private GameIdGenerator() {
  }

  /**
   * Builds the canonical string of a game: start time, map, match type and
   * every team with its member UUIDs, all in a deterministic order.
   */
  public static String canonicalize(String mapName, String matchType, long startedAtMillis,
      List<MatchSummary.TeamResult> teams) {
    StringBuilder sb = new StringBuilder();
    sb.append(startedAtMillis).append('|');
    sb.append(safe(mapName)).append('|');
    sb.append(safe(matchType)).append('|');

    List<MatchSummary.TeamResult> sorted =
        new ArrayList<MatchSummary.TeamResult>(teams);
    Collections.sort(sorted, new Comparator<MatchSummary.TeamResult>() {
      @Override
      public int compare(MatchSummary.TeamResult a, MatchSummary.TeamResult b) {
        return a.getTeamId().compareTo(b.getTeamId());
      }
    });

    boolean firstTeam = true;
    for (MatchSummary.TeamResult team : sorted) {
      if (!firstTeam) {
        sb.append('|');
      }
      firstTeam = false;
      sb.append(safe(team.getTeamId())).append(':');

      List<String> uuids = new ArrayList<String>();
      for (MatchSummary.Participant member : team.getMembers()) {
        uuids.add(safe(member.getUuid()));
      }
      Collections.sort(uuids);
      boolean firstMember = true;
      for (String uuid : uuids) {
        if (!firstMember) {
          sb.append(',');
        }
        firstMember = false;
        sb.append(uuid);
      }
    }
    return sb.toString();
  }

  /**
   * Generates the stable game id: a name-based (RFC 4122 version 3) UUID
   * over the canonical string. The same game always yields the same id,
   * independent of the order in which teams or members were observed.
   */
  public static String generate(String mapName, String matchType, long startedAtMillis,
      List<MatchSummary.TeamResult> teams) {
    String canonical = canonicalize(mapName, matchType, startedAtMillis, teams);
    return UUID.nameUUIDFromBytes(canonical.getBytes(UTF8)).toString();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
