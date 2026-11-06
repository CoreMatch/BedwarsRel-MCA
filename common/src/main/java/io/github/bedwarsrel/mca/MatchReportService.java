package io.github.bedwarsrel.mca;

import io.github.bedwarsrel.game.Game;
import io.github.bedwarsrel.game.Team;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous match-report pipeline.
 *
 * <p>Game over (main thread) → synchronous {@link MatchSummary} snapshot →
 * bounded queue → single daemon worker that POSTs to the HA-Contract
 * {@code POST /api/matches} endpoint with retries and idempotency.
 *
 * <p>Reliability rules:
 * <ul>
 *   <li>2xx: the game id is recorded in the persistent ledger — the game is
 *       never reported again.</li>
 *   <li>4xx: logged with the response body and never retried (the server
 *       rejected the report for a reason retrying cannot fix).</li>
 *   <li>5xx / network failure: retried with exponential backoff up to
 *       {@code max-attempts}; afterwards the game (id, failure reason and
 *       full payload) is written to the bounded dead-letter file and an
 *       error log is emitted. At startup the plugin warns when undelivered
 *       records exist.</li>
 *   <li>Missing credentials or endpoint: a warning is logged once and the
 *       report is skipped — never an exception into the game flow.</li>
 * </ul>
 */
public final class MatchReportService {

  static final String LEDGER_FILE_NAME = "mca-reported-matches.log";
  static final String DEAD_LETTER_FILE_NAME = "mca-dead-letter.log";
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static final long MAX_RETRY_DELAY_MILLIS = 30_000L;

  private static MatchReportService instance = null;

  private final MatchReportConfig config;
  private final ReportLedger ledger;
  private final DeadLetterStore deadLetter;
  private final TokenProvider tokenProvider;
  private final ReportTransport transport;
  private final Logger logger;
  private final BlockingQueue<MatchSummary> queue;
  private final Thread worker;
  private final AtomicBoolean skipWarningLogged = new AtomicBoolean(false);
  private volatile boolean running = true;

  MatchReportService(MatchReportConfig config, ReportLedger ledger, DeadLetterStore deadLetter,
      TokenProvider tokenProvider, ReportTransport transport, Logger logger) {
    this.config = config;
    this.ledger = ledger;
    this.deadLetter = deadLetter;
    this.tokenProvider = tokenProvider;
    this.transport = transport;
    this.logger = logger;
    this.queue = new LinkedBlockingQueue<MatchSummary>(config.getQueueCapacity());
    this.worker = new Thread(new Runnable() {
      @Override
      public void run() {
        MatchReportService.this.runLoop();
      }
    }, "BedwarsRel-MCA-Reporter");
    this.worker.setDaemon(true);
  }

  /**
   * Initializes the singleton from plugin configuration. Safe to call
   * again (e.g. on reload): the previous worker is shut down first.
   */
  public static synchronized void init(MatchReportConfig config, File dataFolder, Logger logger) {
    shutdown();

    if (!config.isEnabled()) {
      logger.info("MCA match reporting self-check: enabled=false — reporting is disabled");
      return;
    }

    ReportLedger ledger = new ReportLedger(new File(dataFolder, LEDGER_FILE_NAME),
        config.getLedgerCapacity(), logger);
    DeadLetterStore deadLetter = new DeadLetterStore(new File(dataFolder, DEAD_LETTER_FILE_NAME),
        config.getDeadLetterCapacity(), logger);
    String status = config.describe() + ", ledger=" + ledger.size()
        + " remembered game ids, dead-letter=" + deadLetter.count() + " undelivered records";

    if (config.getApiUrl().isEmpty()) {
      logger.warning("MCA match reporting self-check: NOT OPERATIONAL (api-url missing) — "
          + status);
      return;
    }
    if (!config.hasCredentials()) {
      logger.warning("MCA match reporting self-check: NOT OPERATIONAL (no credentials; set "
          + "match-reporting.credentials.service-token or the client_credentials triple) — "
          + status);
      return;
    }

    TokenProvider tokenProvider = buildTokenProvider(config);
    MatchReportService service = new MatchReportService(config, ledger, deadLetter, tokenProvider,
        new UrlConnectionTransport(), logger);
    service.worker.start();
    instance = service;

    logger.info("MCA match reporting self-check: OPERATIONAL — " + status);
    if (deadLetter.count() > 0) {
      logger.warning("MCA match reporting: " + deadLetter.count()
          + " undelivered match report(s) waiting in the dead-letter file "
          + deadLetter.getFile().getAbsolutePath()
          + " (retries were exhausted; these games were NOT settled)");
    }
  }

  /** Game-over entry point; safe to call even when reporting is not initialized. */
  public static void reportGameEnd(Game game, Team winner) {
    MatchReportService service = instance;
    if (service != null) {
      service.onGameOver(game, winner);
    }
  }

  public static synchronized void shutdown() {
    if (instance != null) {
      instance.stop();
      instance = null;
    }
  }

  /** Singleton accessor (returns {@code null} when reporting is disabled/not initialized). */
  public static synchronized MatchReportService getInstance() {
    return instance;
  }

  private static TokenProvider buildTokenProvider(MatchReportConfig config) {
    if (config.hasServiceToken()) {
      return TokenProvider.forServiceToken(config.getServiceToken());
    }
    return TokenProvider.forClientCredentials(config.getTokenUrl(), config.getClientId(),
        config.getClientSecret(), config.getScope(), new UrlConnectionTransport(),
        config.getTimeoutMillis());
  }

  /**
   * Called on the server main thread from the game-over sequence. Builds the
   * summary snapshot synchronously (before game-state cleanup) and enqueues
   * it for asynchronous delivery.
   */
  void onGameOver(Game game, Team winner) {
    if (!this.config.isEnabled()) {
      return;
    }
    // winner == null is a legitimate all-draw game: HA-Contract v1.4.0
    // accepts it via the unified teams shape and settles with S=0.5.
    if (this.tokenProvider == null) {
      this.warnSkipOnce("MCA match report skipped: no credentials configured");
      return;
    }

    MatchSummary summary;
    try {
      summary = MatchSummaryBuilder.fromGame(game, winner, System.currentTimeMillis());
    } catch (Exception e) {
      this.logger.log(Level.WARNING, "MCA match report skipped: failed to build the summary", e);
      return;
    }

    this.submit(summary);
  }

  /**
   * Enqueues a summary unless it was already reported. Returns {@code false}
   * when the bounded queue is full and the report had to be dropped.
   */
  boolean submit(MatchSummary summary) {
    if (this.ledger.isReported(summary.getGameId())) {
      this.logger.info("MCA match report skipped: game already reported (game_id="
          + summary.getGameId() + ")");
      return true;
    }
    if (!this.queue.offer(summary)) {
      this.logger.warning("MCA match report dropped: report queue is full (capacity="
          + this.config.getQueueCapacity() + ", game_id=" + summary.getGameId() + ")");
      return false;
    }
    this.logger.info("MCA match report queued (game_id=" + summary.getGameId()
        + ", map=" + summary.getMapName() + ", match_type=" + summary.getMatchType()
        + ", duration=" + summary.getDurationSeconds() + "s)");
    return true;
  }

  /** Number of summaries currently waiting in the bounded queue. */
  int pendingCount() {
    return this.queue.size();
  }

  /** Number of undelivered records currently stored in the dead-letter file. */
  public int deadLetterCount() {
    return this.deadLetter.count();
  }

  /** Result of one dead-letter replay attempt. */
  public static final class ReplayResult {
    public final int replayed;
    public final int total;
    public final boolean queueFull;

    ReplayResult(int replayed, int total, boolean queueFull) {
      this.replayed = replayed;
      this.total = total;
      this.queueFull = queueFull;
    }
  }

  /**
   * Re-enqueues up to {@code maxCount} recently-stored dead-letter records.
   *
   * <p>The method is synchronized so that it never races with the delivery
   * worker on the shared queue: it snapshots the dead-letter list first,
   * then feeds summaries one by one and stops as soon as the bounded queue
   * rejects an entry or the requested number is reached. Successfully
   * replayed records are removed from the store.
   *
   * <p>{@code maxCount} is capped at 50 to avoid a single administrative
   * call flooding the queue unexpectedly.
   *
   * @return summary of how many records were replayed, how many were
   *     eligible in total, and whether the run stopped early because the
   *     queue was full
   */
  public synchronized ReplayResult replayDeadLetters(int maxCount) {
    int total = this.deadLetter.count();
    if (total == 0) {
      return new ReplayResult(0, 0, false);
    }

    int limit = Math.max(1, Math.min(maxCount, 50));
    List<String> lines = this.deadLetter.snapshot();
    // snapshot() is oldest-first; we want the most recent `limit` entries
    int start = Math.max(0, lines.size() - limit);
    List<String> selected = lines.subList(start, lines.size());

    int replayed = 0;
    boolean queueFull = false;
    for (String line : selected) {
      MatchReportPayload.DeadLetterEntry entry =
          MatchReportPayload.parseDeadLetterLine(line);
      if (entry == null) {
        this.logger.warning("MCA dead-letter replay skipped: malformed entry, will be removed");
        replayed++; // count as "handled" so it gets cleaned up
        continue;
      }
      MatchSummary summary = MatchReportPayload.fromJson(entry.payloadJson);
      if (summary == null) {
        this.logger.warning("MCA dead-letter replay skipped (game_id=" + entry.gameId
            + "): payload could not be parsed, will be removed");
        replayed++;
        continue;
      }
      if (!this.submit(summary)) {
        queueFull = true;
        break;
      }
      replayed++;
    }

    if (replayed > 0) {
      this.deadLetter.removeFirst(replayed);
    }

    return new ReplayResult(replayed, total, queueFull);
  }

  private void runLoop() {
    while (this.running) {
      MatchSummary summary;
      try {
        summary = this.queue.poll(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        if (!this.running) {
          return;
        }
        continue;
      }
      if (summary == null) {
        continue;
      }
      try {
        this.sendWithRetry(summary);
      } catch (RuntimeException e) {
        // the loop must survive whatever a single report throws
        this.logger.log(Level.WARNING, "MCA match report worker error (game_id="
            + summary.getGameId() + ")", e);
      }
    }
  }

  /** Attempts delivery with classification-driven retries. */
  void sendWithRetry(MatchSummary summary) {
    String payload = MatchReportPayload.toJson(summary);
    byte[] body = payload.getBytes(UTF8);
    int maxAttempts = this.config.getMaxAttempts();
    String lastDetail = "unknown";

    for (int attempt = 1; attempt <= maxAttempts && this.running; attempt++) {
      ReportClassifier.Category category;
      String detail;
      try {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Authorization", this.tokenProvider.getAuthorizationHeader());
        ReportTransport.Response response = this.transport.post(this.config.getApiUrl(),
            "application/json", body, headers, this.config.getTimeoutMillis());
        category = ReportClassifier.classifyStatus(response.getStatus());
        detail = "HTTP " + response.getStatus()
            + (response.getBody().isEmpty() ? "" : " body=" + truncate(response.getBody()));

        if (category == ReportClassifier.Category.SUCCESS) {
          this.ledger.markReported(summary.getGameId());
          this.logger.info("MCA match report accepted (game_id=" + summary.getGameId()
              + ", attempt=" + attempt + ")");
          return;
        }
        if (category == ReportClassifier.Category.CLIENT_ERROR
            && response.getStatus() == 401
            && this.tokenProvider.getKind() == TokenProvider.Kind.CLIENT_CREDENTIALS) {
          // the access token may have expired server-side; re-fetch and retry
          this.tokenProvider.invalidate();
          category = ReportClassifier.Category.RETRYABLE;
        }
      } catch (IOException e) {
        category = ReportClassifier.classifyException(e);
        detail = e.toString();
      }

      lastDetail = detail;
      if (category == ReportClassifier.Category.CLIENT_ERROR) {
        this.logger.warning("MCA match report rejected by server, not retried (game_id="
            + summary.getGameId() + "): " + detail);
        return;
      }

      this.logger.warning("MCA match report attempt " + attempt + "/" + maxAttempts
          + " failed (game_id=" + summary.getGameId() + "): " + detail);
      if (attempt < maxAttempts) {
        this.sleepBackoff(attempt);
      }
    }

    this.deadLetter.add(summary.getGameId(), lastDetail, payload);
    this.logger.severe("MCA match report dropped after " + maxAttempts + " attempts (game_id="
        + summary.getGameId() + ", map=" + summary.getMapName() + "); the game and the failure "
        + "reason were written to the dead-letter file "
        + this.deadLetter.getFile().getAbsolutePath()
        + ". The result was NOT settled by MatchCoreArena.");
  }

  private void sleepBackoff(int attempt) {
    long delay = this.config.getRetryDelayMillis() * (1L << Math.min(attempt - 1, 6));
    delay = Math.min(delay, MAX_RETRY_DELAY_MILLIS);
    if (delay <= 0) {
      return;
    }
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void warnSkipOnce(String message) {
    if (this.skipWarningLogged.compareAndSet(false, true)) {
      this.logger.warning(message);
    }
  }

  void stop() {
    this.running = false;
    this.worker.interrupt();
    try {
      this.worker.join(2000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String truncate(String body) {
    return body.length() <= 256 ? body : body.substring(0, 256) + "...";
  }
}
