package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Test;

/**
 * Tests the Bukkit-free layers of {@link RoomAssignmentService}: endpoint
 * derivation, the assignment lookup (HTTP semantics + envelope parsing) and
 * the group-id round-trip store.
 */
public class RoomAssignmentServiceTest {

  private static final Logger LOGGER = Logger.getLogger("RoomAssignmentServiceTest");

  /** Scripted assignment endpoint. */
  private static final class FakeTransport implements ReportTransport {

    private final List<Object> scripted = new ArrayList<Object>();
    private final List<String> requestedUrls = new ArrayList<String>();
    private final List<String> authHeaders = new ArrayList<String>();

    private FakeTransport enqueueStatus(int status, String body) {
      this.scripted.add(new Response(status, body));
      return this;
    }

    private FakeTransport enqueueError(IOException error) {
      this.scripted.add(error);
      return this;
    }

    @Override
    public Response post(String url, String contentType, byte[] body,
        Map<String, String> headers, int timeoutMillis) {
      throw new UnsupportedOperationException("POST not scripted in RoomAssignmentServiceTest");
    }

    @Override
    public Response get(String url, Map<String, String> headers, int timeoutMillis)
        throws IOException {
      this.requestedUrls.add(url);
      this.authHeaders.add(headers == null ? "" : String.valueOf(headers.get("Authorization")));
      if (this.scripted.isEmpty()) {
        throw new IOException("no scripted response left");
      }
      Object next = this.scripted.remove(0);
      if (next instanceof IOException) {
        throw (IOException) next;
      }
      return (Response) next;
    }
  }

  private static MatchReportConfig config(String apiUrl, String assignmentUrl) {
    return new MatchReportConfig(true, apiUrl, assignmentUrl, 1000, 3, 2000L, 64, 2000, 200,
        "svc-token", "", "", "", "match:report");
  }

  private static RoomAssignmentService service(FakeTransport transport) {
    return new RoomAssignmentService(
        config("http://mca.local/api/matches", ""),
        "http://mca.local/api/matchmaking/assignment/",
        TokenProvider.forServiceToken("svc-token"), transport, LOGGER);
  }

  @After
  public void tearDown() {
    RoomAssignmentService.shutdown();
  }

  // ---- endpoint derivation ----

  @Test
  public void assignmentUrlIsDerivedFromTheReportEndpoint() {
    assertEquals("http://mca.local/api/matchmaking/assignment/",
        RoomAssignmentService.deriveAssignmentUrl(config("http://mca.local/api/matches", "")));
  }

  @Test
  public void explicitAssignmentUrlWinsAndGetsTrailingSlash() {
    assertEquals("https://arena.example/api/matchmaking/assignment/",
        RoomAssignmentService.deriveAssignmentUrl(
            config("http://mca.local/api/matches",
                "https://arena.example/api/matchmaking/assignment")));
    assertEquals("https://arena.example/api/matchmaking/assignment/",
        RoomAssignmentService.deriveAssignmentUrl(
            config("http://mca.local/api/matches",
                "https://arena.example/api/matchmaking/assignment/")));
  }

  @Test
  public void nonStandardApiUrlWithoutExplicitAssignmentUrlIsRejected() {
    assertNull(RoomAssignmentService.deriveAssignmentUrl(
        config("http://mca.local/other/endpoint", "")));
  }

  // ---- assignment lookup ----

  @Test
  public void validAssignmentYieldsRoomAndGroupId() {
    FakeTransport transport = new FakeTransport().enqueueStatus(200,
        "{\"success\":true,\"data\":{\"assigned\":true,\"group_id\":42,"
            + "\"match_type\":\"1v1\",\"team_id\":\"A\",\"room\":\"arena-1\","
            + "\"join_target\":{\"host\":\"10.0.0.5\",\"port\":25566}}}");
    RoomAssignmentService service = service(transport);

    RoomAssignmentService.Decision decision =
        service.queryAssignment("11111111-1111-3111-8111-111111111111");

    assertEquals(RoomAssignmentService.Outcome.JOIN_ROOM, decision.outcome);
    assertEquals("arena-1", decision.room);
    assertEquals(42L, decision.groupId);
    assertEquals("http://mca.local/api/matchmaking/assignment/"
        + "11111111-1111-3111-8111-111111111111", transport.requestedUrls.get(0));
    assertEquals("Bearer svc-token", transport.authHeaders.get(0));
  }

  @Test
  public void plainNoAssignmentYieldsNoAssignmentDecision() {
    FakeTransport transport = new FakeTransport().enqueueStatus(200,
        "{\"success\":true,\"data\":{\"assigned\":false}}");

    RoomAssignmentService.Decision decision =
        service(transport).queryAssignment("11111111-1111-3111-8111-111111111111");

    assertEquals(RoomAssignmentService.Outcome.NO_ASSIGNMENT, decision.outcome);
    assertEquals("", decision.detail);
  }

  @Test
  public void noRoomAvailableReasonIsCarried() {
    FakeTransport transport = new FakeTransport().enqueueStatus(200,
        "{\"success\":true,\"data\":{\"assigned\":false,\"reason\":\"no_room_available\"}}");

    RoomAssignmentService.Decision decision =
        service(transport).queryAssignment("11111111-1111-3111-8111-111111111111");

    assertEquals(RoomAssignmentService.Outcome.NO_ASSIGNMENT, decision.outcome);
    assertEquals("no_room_available", decision.detail);
  }

  @Test
  public void assignedWithoutRoomIsUnavailable() {
    FakeTransport transport = new FakeTransport().enqueueStatus(200,
        "{\"success\":true,\"data\":{\"assigned\":true,\"group_id\":7}}");

    RoomAssignmentService.Decision decision =
        service(transport).queryAssignment("11111111-1111-3111-8111-111111111111");

    assertEquals(RoomAssignmentService.Outcome.UNAVAILABLE, decision.outcome);
  }

  @Test
  public void non200DegradesToUnavailable() {
    FakeTransport transport = new FakeTransport().enqueueStatus(502,
        "{\"success\":false,\"error\":{\"code\":\"oauth_hrpauth_down\"}}");

    RoomAssignmentService.Decision decision =
        service(transport).queryAssignment("11111111-1111-3111-8111-111111111111");

    assertEquals(RoomAssignmentService.Outcome.UNAVAILABLE, decision.outcome);
    assertTrue(decision.detail.contains("502"));
  }

  @Test
  public void networkFailureDegradesToUnavailable() {
    FakeTransport transport = new FakeTransport()
        .enqueueError(new IOException("connect timed out"));

    RoomAssignmentService.Decision decision =
        service(transport).queryAssignment("11111111-1111-3111-8111-111111111111");

    assertEquals(RoomAssignmentService.Outcome.UNAVAILABLE, decision.outcome);
    assertTrue(decision.detail.contains("connect timed out"));
  }

  @Test
  public void malformedBodyDegradesToUnavailable() {
    FakeTransport transport = new FakeTransport().enqueueStatus(200, "not json at all");

    RoomAssignmentService.Decision decision =
        service(transport).queryAssignment("11111111-1111-3111-8111-111111111111");

    assertEquals(RoomAssignmentService.Outcome.UNAVAILABLE, decision.outcome);
  }

  @Test
  public void envelopeWithoutSuccessTrueDegradesToUnavailable() {
    FakeTransport transport = new FakeTransport().enqueueStatus(200,
        "{\"success\":false,\"error\":{\"code\":\"mca_internal\"}}");

    RoomAssignmentService.Decision decision =
        service(transport).queryAssignment("11111111-1111-3111-8111-111111111111");

    assertEquals(RoomAssignmentService.Outcome.UNAVAILABLE, decision.outcome);
  }

  // ---- group-id round-trip store ----

  @Test
  public void groupIdIsConsumedExactlyOnce() {
    RoomAssignmentService.init(config("http://mca.local/api/matches", ""), LOGGER);
    assertTrue(RoomAssignmentService.isOperational());
    RoomAssignmentService.getInstance().rememberGroupIdForGame("arena-1", 42L);

    assertEquals(Long.valueOf(42L), RoomAssignmentService.consumeGroupIdForGame("arena-1"));
    assertNull(RoomAssignmentService.consumeGroupIdForGame("arena-1"));
    assertNull(RoomAssignmentService.consumeGroupIdForGame("other-room"));
  }

  @Test
  public void groupIdIsOverwrittenWhenRoomIsReassigned() {
    RoomAssignmentService.init(config("http://mca.local/api/matches", ""), LOGGER);
    RoomAssignmentService.getInstance().rememberGroupIdForGame("arena-1", 42L);
    RoomAssignmentService.getInstance().rememberGroupIdForGame("arena-1", 99L);

    assertEquals(Long.valueOf(99L), RoomAssignmentService.consumeGroupIdForGame("arena-1"));
  }

  @Test
  public void consumeWithoutOperationalServiceIsNull() {
    assertFalse(RoomAssignmentService.isOperational());
    assertNull(RoomAssignmentService.consumeGroupIdForGame("arena-1"));
  }

  @Test
  public void initWithoutCredentialsIsNotOperational() {
    MatchReportConfig noCredentials = new MatchReportConfig(true,
        "http://mca.local/api/matches", "", 1000, 3, 2000L, 64, 2000, 200,
        "", "", "", "", "match:report");

    RoomAssignmentService.init(noCredentials, LOGGER);

    assertFalse(RoomAssignmentService.isOperational());
  }
}
