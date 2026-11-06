package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.bedwarsrel.mca.MatchSummaryBuilder.ParticipantInput;
import io.github.bedwarsrel.mca.MatchSummaryBuilder.TeamInput;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DeadLetterReplayTest {

  private static final Logger LOGGER = Logger.getLogger("DeadLetterReplayTest");
  private static final AtomicInteger FILE_COUNTER = new AtomicInteger();

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /** Scripted report endpoint (same fake transport as MatchReportServiceTest). */
  private static final class FakeTransport implements ReportTransport {

    private final List<Object> scripted = new ArrayList<Object>();

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
        Map<String, String> headers, int timeoutMillis) throws IOException {
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

  private MatchSummary summary(long startedAt) {
    return MatchSummaryBuilder.build("arena", startedAt, startedAt + 60_000L, Arrays.asList(
        new TeamInput("red", 1, 1, true,
            Arrays.asList(new ParticipantInput("u1", "p1", 60, 1, 0))),
        new TeamInput("blue", 1, 0, false,
            Arrays.asList(new ParticipantInput("u2", "p2", 30, 0, 1)))));
  }

  private MatchReportService serviceWithDeadLetters(int queueCapacity, int howMany,
      FakeTransport transport) throws IOException {
    // force-exhaust retries so each summary ends up in the dead-letter store
    transport.enqueueStatus(500, "");
    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches", 1000,
        1, 0L, queueCapacity, 100, 50, "service-token-abc", "", "", "", "match:report");

    ReportLedger ledger = new ReportLedger(
        new File(folder.getRoot(), "ledger-" + FILE_COUNTER.incrementAndGet() + ".log"),
        100, LOGGER);
    DeadLetterStore deadLetter = new DeadLetterStore(
        new File(folder.getRoot(), "dead-" + FILE_COUNTER.incrementAndGet() + ".log"),
        100, LOGGER);
    MatchReportService service = new MatchReportService(config, ledger, deadLetter,
        TokenProvider.forServiceToken(config.getServiceToken()), transport, LOGGER);

    for (int i = 0; i < howMany; i++) {
      long t = 1_000L * (i + 1);
      service.sendWithRetry(summary(t));
    }
    return service;
  }

  @Test
  public void replayFromEmptyStoreReturnsZero() throws IOException {
    FakeTransport transport = new FakeTransport();
    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches", 1000,
        1, 0L, 4, 100, 50, "service-token", "", "", "", "match:report");
    ReportLedger ledger = new ReportLedger(
        new File(folder.getRoot(), "ledger-" + FILE_COUNTER.incrementAndGet() + ".log"),
        100, LOGGER);
    DeadLetterStore deadLetter = new DeadLetterStore(
        new File(folder.getRoot(), "dead-" + FILE_COUNTER.incrementAndGet() + ".log"),
        50, LOGGER);
    MatchReportService service = new MatchReportService(config, ledger, deadLetter,
        TokenProvider.forServiceToken("service-token"), transport, LOGGER);

    MatchReportService.ReplayResult r = service.replayDeadLetters(10);

    assertEquals(0, r.replayed);
    assertEquals(0, r.total);
    assertFalse(r.queueFull);
    assertEquals(0, service.deadLetterCount());
  }

  @Test
  public void replayMostRecentEntries() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueueStatus(500, "").enqueueStatus(500, "").enqueueStatus(500, "");
    MatchReportService service = serviceWithDeadLetters(4, 3, transport);
    assertEquals(3, service.deadLetterCount());

    // Replay the 2 most recent ones. The store is oldest-first internally;
    // replay must pick the newest ones.
    MatchReportService.ReplayResult r = service.replayDeadLetters(2);

    assertEquals(2, r.replayed);
    assertEquals(3, r.total);
    assertFalse(r.queueFull);
    // the 2 replayed entries must have been removed
    assertEquals(1, service.deadLetterCount());
    // both queued summaries should be the newer ones (game-2 and game-3)
    assertEquals(2, service.pendingCount());
  }

  @Test
  public void replayRespectsQueueCapacity() throws IOException {
    // 3 dead-letter entries, queue capacity 1 → replay must stop after the
    // first enqueue because the second one fails
    FakeTransport transport = new FakeTransport()
        .enqueueStatus(500, "").enqueueStatus(500, "").enqueueStatus(500, "");
    MatchReportService service = serviceWithDeadLetters(1, 3, transport);
    assertEquals(3, service.deadLetterCount());

    MatchReportService.ReplayResult r = service.replayDeadLetters(10);

    assertTrue("should have stopped because the queue filled", r.queueFull);
    assertEquals(1, r.replayed);
    assertEquals(3, r.total);
    // only the one that was actually re-enqueued must be removed
    assertEquals(2, service.deadLetterCount());
  }

  @Test
  public void replayAllClearsTheStore() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueueStatus(500, "").enqueueStatus(500, "");
    MatchReportService service = serviceWithDeadLetters(4, 2, transport);
    assertEquals(2, service.deadLetterCount());

    MatchReportService.ReplayResult r = service.replayDeadLetters(10);

    assertEquals(2, r.replayed);
    assertEquals(2, r.total);
    assertFalse(r.queueFull);
    assertEquals(0, service.deadLetterCount());
    assertEquals(2, service.pendingCount());
  }

  @Test
  public void replayIsCappedAtFifty() throws IOException {
    FakeTransport transport = new FakeTransport();
    // add 60 dead-letter entries manually to bypass the single retry setup
    File file = new File(folder.getRoot(), "dead-manual-" + FILE_COUNTER.incrementAndGet()
        + ".log");
    Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
    for (int i = 0; i < 60; i++) {
      MatchSummary s = summary(1_000L * i);
      String payload = MatchReportPayload.toJson(s);
      w.write("{\"at\":" + i + ",\"game_id\":\"manual-" + i
          + "\",\"reason\":\"HTTP 500\",\"payload\":" + payload + "}\n");
    }
    w.close();

    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches", 1000,
        1, 0L, 100, 100, 500, "service-token", "", "", "", "match:report");
    ReportLedger ledger = new ReportLedger(
        new File(folder.getRoot(), "ledger-" + FILE_COUNTER.incrementAndGet() + ".log"),
        1000, LOGGER);
    DeadLetterStore deadLetter = new DeadLetterStore(file, 500, LOGGER);
    assertEquals(60, deadLetter.count());

    MatchReportService service = new MatchReportService(config, ledger, deadLetter,
        TokenProvider.forServiceToken("service-token"), transport, LOGGER);

    MatchReportService.ReplayResult r = service.replayDeadLetters(100);

    // maxCount must be silently capped at 50
    assertEquals(50, r.replayed);
    assertEquals(60, r.total);
    assertFalse(r.queueFull);
    assertEquals(10, service.deadLetterCount());
  }

  @Test
  public void replaySkipsMalformedEntries() throws IOException {
    File file = new File(folder.getRoot(), "dead-bad-" + FILE_COUNTER.incrementAndGet() + ".log");
    Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
    // one good line
    MatchSummary s = summary(1234L);
    String payload = MatchReportPayload.toJson(s);
    w.write("{\"at\":1,\"game_id\":\"good-1\",\"reason\":\"fail\",\"payload\":"
        + payload + "}\n");
    // one malformed line
    w.write("this is not valid json at all\n");
    w.close();

    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches", 1000,
        1, 0L, 10, 100, 50, "service-token", "", "", "", "match:report");
    ReportLedger ledger = new ReportLedger(
        new File(folder.getRoot(), "ledger-" + FILE_COUNTER.incrementAndGet() + ".log"),
        100, LOGGER);
    DeadLetterStore deadLetter = new DeadLetterStore(file, 50, LOGGER);
    assertEquals(2, deadLetter.count());

    FakeTransport transport = new FakeTransport();
    MatchReportService service = new MatchReportService(config, ledger, deadLetter,
        TokenProvider.forServiceToken("service-token"), transport, LOGGER);

    MatchReportService.ReplayResult r = service.replayDeadLetters(10);

    // malformed entry is counted as "handled" and removed; good one is queued
    assertEquals(2, r.replayed);
    assertEquals(0, service.deadLetterCount());
    assertEquals(1, service.pendingCount());
  }

  @Test
  public void deadLetterCountGetterMatchesStore() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueueStatus(500, "").enqueueStatus(500, "");
    MatchReportService service = serviceWithDeadLetters(4, 2, transport);
    assertEquals(2, service.deadLetterCount());
  }

  @Test
  public void payloadRoundTripThroughMatchReportPayload() {
    MatchSummary original = summary(5_000L);
    String json = MatchReportPayload.toJson(original);
    assertNotNull(json);

    MatchSummary roundTripped = MatchReportPayload.fromJson(json);
    assertNotNull("fromJson must succeed on a payload produced by toJson", roundTripped);
    assertEquals(original.getGameId(), roundTripped.getGameId());
    assertEquals(original.getMapName(), roundTripped.getMapName());
    assertEquals(original.getMatchType(), roundTripped.getMatchType());
    assertEquals(original.getDurationSeconds(), roundTripped.getDurationSeconds());
    assertEquals(original.getTeams().size(), roundTripped.getTeams().size());
    for (int i = 0; i < original.getTeams().size(); i++) {
      MatchSummary.TeamResult ot = original.getTeams().get(i);
      MatchSummary.TeamResult rt = roundTripped.getTeams().get(i);
      assertEquals(ot.getTeamId(), rt.getTeamId());
      assertEquals(ot.isWinner(), rt.isWinner());
      assertEquals(ot.getInitialCount(), rt.getInitialCount());
      assertEquals(ot.getAliveCount(), rt.getAliveCount());
      assertEquals(ot.getMembers().size(), rt.getMembers().size());
    }
  }

  @Test
  public void rfc3339RoundTrip() {
    long millis = 1_730_567_698_000L; // 2024-11-02T20:34:58Z
    String formatted = MatchReportPayload.formatRfc3339Utc(millis);
    long parsed = MatchReportPayload.parseRfc3339Utc(formatted);
    assertEquals(millis, parsed);

    assertEquals(-1L, MatchReportPayload.parseRfc3339Utc(null));
    assertEquals(-1L, MatchReportPayload.parseRfc3339Utc("garbage"));
  }
}
