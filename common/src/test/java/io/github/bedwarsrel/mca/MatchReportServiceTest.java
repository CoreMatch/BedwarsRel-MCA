package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.bedwarsrel.mca.MatchSummaryBuilder.ParticipantInput;
import io.github.bedwarsrel.mca.MatchSummaryBuilder.TeamInput;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MatchReportServiceTest {

  private static final Logger LOGGER = Logger.getLogger("MatchReportServiceTest");
  private static final AtomicInteger FILE_COUNTER = new AtomicInteger();

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /** Scripted report endpoint. */
  private static final class FakeTransport implements ReportTransport {

    private final List<Object> scripted = new ArrayList<Object>();
    private final List<String> authHeaders = new ArrayList<String>();
    private int calls = 0;

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
      this.calls++;
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

    @Override
    public Response get(String url, Map<String, String> headers, int timeoutMillis) {
      throw new UnsupportedOperationException("GET not scripted in MatchReportServiceTest");
    }
  }

  /** Captures log records of one logger. */
  private static final class CapturingHandler extends Handler {

    private final List<LogRecord> records = new ArrayList<LogRecord>();

    @Override
    public void publish(LogRecord record) {
      this.records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    private List<String> messages() {
      List<String> messages = new ArrayList<String>();
      for (LogRecord record : this.records) {
        messages.add(record.getMessage());
      }
      return messages;
    }

    private String messageAtLevel(Level level) {
      for (LogRecord record : this.records) {
        if (record.getLevel().equals(level)) {
          return record.getMessage();
        }
      }
      return null;
    }
  }

  private MatchReportConfig config(int maxAttempts, int queueCapacity) {
    return new MatchReportConfig(true, "http://mca.local/api/matches", 1000, maxAttempts,
        0L, queueCapacity, 100, 50, "service-token-abc", "", "", "", "match:report");
  }

  private MatchSummary summary(long startedAt) {
    return MatchSummaryBuilder.build("arena", startedAt, startedAt + 60_000L, Arrays.asList(
        new TeamInput("red", 1, 1, true,
            Arrays.asList(new ParticipantInput("u1", "p1", 60, 1, 0))),
        new TeamInput("blue", 1, 0, false,
            Arrays.asList(new ParticipantInput("u2", "p2", 30, 0, 1)))));
  }

  private ReportLedger newLedger() throws IOException {
    return new ReportLedger(
        new File(folder.getRoot(), "ledger-" + FILE_COUNTER.incrementAndGet() + ".log"),
        100, LOGGER);
  }

  private DeadLetterStore newDeadLetter() {
    return new DeadLetterStore(
        new File(folder.getRoot(), "dead-" + FILE_COUNTER.incrementAndGet() + ".log"),
        50, LOGGER);
  }

  private MatchReportService service(MatchReportConfig config, FakeTransport transport,
      ReportLedger ledger, DeadLetterStore deadLetter) {
    return new MatchReportService(config, ledger, deadLetter,
        TokenProvider.forServiceToken(config.getServiceToken()), transport, LOGGER);
  }

  @Test
  public void successIsRecordedInLedger() throws IOException {
    FakeTransport transport = new FakeTransport().enqueueStatus(201, "{\"data\":{}}");
    ReportLedger ledger = newLedger();
    MatchReportService service = service(config(3, 4), transport, ledger, newDeadLetter());
    MatchSummary summary = summary(1000L);

    service.sendWithRetry(summary);

    assertEquals(1, transport.calls);
    assertTrue(ledger.isReported(summary.getGameId()));
    assertEquals("Bearer service-token-abc", transport.authHeaders.get(0));
  }

  @Test
  public void idempotentReplayIsAlsoSuccess() throws IOException {
    // v1.4.0: re-reporting the same game_id with an identical payload
    // replays the existing record with 200 and must not settle again
    FakeTransport transport = new FakeTransport().enqueueStatus(200, "{\"data\":{}}");
    ReportLedger ledger = newLedger();
    MatchReportService service = service(config(3, 4), transport, ledger, newDeadLetter());
    MatchSummary summary = summary(1000L);

    service.sendWithRetry(summary);

    assertEquals(1, transport.calls);
    assertTrue(ledger.isReported(summary.getGameId()));
  }

  @Test
  public void clientErrorIsLoggedAndNeverRetriedOrDeadLettered() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueueStatus(404, "{\"error\":{\"code\":\"mca_user_not_found\"}}");
    ReportLedger ledger = newLedger();
    DeadLetterStore deadLetter = newDeadLetter();
    MatchReportService service = service(config(5, 4), transport, ledger, deadLetter);
    MatchSummary summary = summary(1000L);

    service.sendWithRetry(summary);

    assertEquals("4xx must not be retried", 1, transport.calls);
    assertFalse("rejected report must not be ledgered", ledger.isReported(summary.getGameId()));
    assertEquals("final rejections are not undelivered reports", 0, deadLetter.count());
  }

  @Test
  public void serverErrorIsRetriedUntilSuccess() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueueStatus(502, "{\"error\":{\"code\":\"oauth_hrpauth_down\"}}")
        .enqueueStatus(503, "")
        .enqueueStatus(201, "{\"data\":{}}");
    ReportLedger ledger = newLedger();
    MatchReportService service = service(config(3, 4), transport, ledger, newDeadLetter());
    MatchSummary summary = summary(1000L);

    service.sendWithRetry(summary);

    assertEquals(3, transport.calls);
    assertTrue(ledger.isReported(summary.getGameId()));
  }

  @Test
  public void networkFailureIsRetried() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueueError(new IOException("connection refused"))
        .enqueueStatus(201, "{}");
    ReportLedger ledger = newLedger();
    MatchReportService service = service(config(2, 4), transport, ledger, newDeadLetter());
    MatchSummary summary = summary(1000L);

    service.sendWithRetry(summary);

    assertEquals(2, transport.calls);
    assertTrue(ledger.isReported(summary.getGameId()));
  }

  @Test
  public void exhaustedRetriesAreWrittenToTheDeadLetter() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueueStatus(500, "")
        .enqueueStatus(500, "")
        .enqueueStatus(500, "");
    ReportLedger ledger = newLedger();
    DeadLetterStore deadLetter = newDeadLetter();
    MatchReportService service = service(config(3, 4), transport, ledger, deadLetter);
    MatchSummary summary = summary(1000L);

    service.sendWithRetry(summary);

    assertEquals(3, transport.calls);
    assertFalse(ledger.isReported(summary.getGameId()));
    assertEquals(1, deadLetter.count());
    String entry = deadLetter.snapshot().get(0);
    assertTrue(entry.contains("\"game_id\":\"" + summary.getGameId() + "\""));
    assertTrue("failure reason must be recorded", entry.contains("HTTP 500"));
    assertTrue("payload must be recoverable", entry.contains("\"payload\":{"));
  }

  @Test
  public void alreadyReportedGameIsNotQueuedAgain() throws IOException {
    FakeTransport transport = new FakeTransport();
    ReportLedger ledger = newLedger();
    MatchReportService service = service(config(3, 4), transport, ledger, newDeadLetter());
    MatchSummary summary = summary(1000L);
    ledger.markReported(summary.getGameId()); // reported before a restart

    // duplicate submission (e.g. the game-over sequence runs again) is
    // skipped via the ledger and never occupies the queue
    assertTrue(service.submit(summary));
    assertEquals("queue must stay empty for an already-reported game", 0,
        service.pendingCount());

    // a genuinely new game is still queued
    assertTrue(service.submit(summary(2000L)));
    assertEquals(1, service.pendingCount());
  }

  @Test
  public void boundedQueueDropsWhenFull() throws IOException {
    FakeTransport transport = new FakeTransport();
    ReportLedger ledger = newLedger();
    // worker is never started, so the queue stays as filled
    MatchReportService service = service(config(3, 1), transport, ledger, newDeadLetter());

    assertTrue(service.submit(summary(1000L)));
    assertFalse("second game must be dropped when the queue is full",
        service.submit(summary(2000L)));
    assertEquals(1, service.pendingCount());
  }

  @Test
  public void expiredClientCredentialsTokenIsRefreshedOn401() throws IOException {
    // token endpoint: two tokens, one per exchange
    FakeTransport tokenTransport = new FakeTransport()
        .enqueueStatus(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}")
        .enqueueStatus(200, "{\"access_token\":\"tok-2\",\"expires_in\":3600}");
    TokenProvider provider = TokenProvider.forClientCredentials("http://auth.local/oauth/token",
        "id", "secret", "match:report", tokenTransport, 1000);

    // report endpoint: rejects the first token, accepts the refreshed one
    FakeTransport reportTransport = new FakeTransport()
        .enqueueStatus(401, "{\"error\":{\"code\":\"oauth_token_expired\"}}")
        .enqueueStatus(201, "{}");

    ReportLedger ledger = newLedger();
    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches", 1000,
        3, 0L, 4, 100, 50, "", "http://auth.local/oauth/token", "id", "secret", "match:report");
    MatchReportService service =
        new MatchReportService(config, ledger, newDeadLetter(), provider, reportTransport,
            LOGGER);
    MatchSummary summary = summary(1000L);

    service.sendWithRetry(summary);

    assertEquals(2, reportTransport.calls);
    assertEquals("token must have been re-exchanged after the 401", 2, tokenTransport.calls);
    assertEquals("Bearer tok-1", reportTransport.authHeaders.get(0));
    assertEquals("Bearer tok-2", reportTransport.authHeaders.get(1));
    assertTrue(ledger.isReported(summary.getGameId()));
  }

  @Test
  public void serviceToken401IsNotRetried() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueueStatus(401, "{\"error\":{\"code\":\"oauth_invalid_grant\"}}")
        .enqueueStatus(201, "{}");
    ReportLedger ledger = newLedger();
    MatchReportService service = service(config(3, 4), transport, ledger, newDeadLetter());
    MatchSummary summary = summary(1000L);

    service.sendWithRetry(summary);

    assertEquals("a static service token that is rejected must not be retried", 1,
        transport.calls);
    assertFalse(ledger.isReported(summary.getGameId()));
  }

  @Test
  public void startupSelfCheckLogsStatusWithoutSecrets() throws IOException {
    Logger logger = Logger.getLogger("self-check-operational-" + FILE_COUNTER.incrementAndGet());
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.ALL);
    CapturingHandler handler = new CapturingHandler();
    logger.addHandler(handler);

    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches", 1000,
        3, 0L, 4, 100, 50, "top-secret-service-token", "", "", "", "match:report");
    try {
      MatchReportService.init(config, folder.getRoot(), logger);

      String info = handler.messageAtLevel(Level.INFO);
      assertTrue("operational self-check must be logged", info.contains("OPERATIONAL"));
      assertTrue(info.contains("enabled=true"));
      assertTrue(info.contains("endpoint=http://mca.local/api/matches"));
      assertTrue("credential form must be visible",
          info.contains("credentials=service-token(set)"));
      assertTrue("ledger entry count must be visible", info.contains("ledger=0"));
      assertTrue("dead-letter entry count must be visible", info.contains("dead-letter=0"));
      for (String message : handler.messages()) {
        assertFalse("the raw token must never be logged",
            message.contains("top-secret-service-token"));
      }
    } finally {
      MatchReportService.shutdown();
      logger.removeHandler(handler);
    }
  }

  @Test
  public void startupWarnsAboutUndeliveredDeadLetterRecords() throws IOException {
    // simulate leftovers from a previous run
    File deadLetterFile = new File(folder.getRoot(), MatchReportService.DEAD_LETTER_FILE_NAME);
    Writer writer = new OutputStreamWriter(new FileOutputStream(deadLetterFile), "UTF-8");
    writer.write("{\"at\":1,\"game_id\":\"old-1\",\"reason\":\"HTTP 502\",\"payload\":{}}\n");
    writer.write("{\"at\":2,\"game_id\":\"old-2\",\"reason\":\"timeout\",\"payload\":{}}\n");
    writer.close();

    Logger logger = Logger.getLogger("self-check-deadletter-" + FILE_COUNTER.incrementAndGet());
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.ALL);
    CapturingHandler handler = new CapturingHandler();
    logger.addHandler(handler);

    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches", 1000,
        3, 0L, 4, 100, 50, "service-token", "", "", "", "match:report");
    try {
      MatchReportService.init(config, folder.getRoot(), logger);

      String warning = handler.messageAtLevel(Level.WARNING);
      assertTrue("undelivered records must be surfaced at startup",
          warning.contains("2 undelivered match report"));
      assertTrue(warning.contains(deadLetterFile.getAbsolutePath()));
    } finally {
      MatchReportService.shutdown();
      logger.removeHandler(handler);
    }
  }

  @Test
  public void startupWithoutCredentialsIsNotOperational() throws IOException {
    Logger logger = Logger.getLogger("self-check-nocreds-" + FILE_COUNTER.incrementAndGet());
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.ALL);
    CapturingHandler handler = new CapturingHandler();
    logger.addHandler(handler);

    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches", 1000,
        3, 0L, 4, 100, 50, "", "", "", "", "match:report");
    try {
      MatchReportService.init(config, folder.getRoot(), logger);

      String warning = handler.messageAtLevel(Level.WARNING);
      assertTrue(warning.contains("NOT OPERATIONAL"));
      assertTrue(warning.contains("credentials=missing"));
      assertNull("no worker must be started without credentials",
          MatchReportService.getInstance());
    } finally {
      MatchReportService.shutdown();
      logger.removeHandler(handler);
    }
  }

  @Test
  public void startupDisabledLogsOnlyTheSwitch() throws IOException {
    Logger logger = Logger.getLogger("self-check-disabled-" + FILE_COUNTER.incrementAndGet());
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.ALL);
    CapturingHandler handler = new CapturingHandler();
    logger.addHandler(handler);

    MatchReportConfig config = new MatchReportConfig(false, "http://mca.local/api/matches", 1000,
        3, 0L, 4, 100, 50, "service-token", "", "", "", "match:report");
    try {
      MatchReportService.init(config, folder.getRoot(), logger);

      String info = handler.messageAtLevel(Level.INFO);
      assertTrue(info.contains("enabled=false"));
      assertNull(MatchReportService.getInstance());
    } finally {
      MatchReportService.shutdown();
      logger.removeHandler(handler);
    }
  }
}
