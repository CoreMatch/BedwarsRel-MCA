package io.github.bedwarsrel.mca;

import io.github.bedwarsrel.game.Game;
import io.github.bedwarsrel.game.Team;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
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
 *       {@code max-attempts}; afterwards the report is dropped with an
 *       error log.</li>
 *   <li>Missing credentials or endpoint: a warning is logged once and the
 *       report is skipped — never an exception into the game flow.</li>
 * </ul>
 */
public final class MatchReportService {

  static final String LEDGER_FILE_NAME = "mca-reported-matches.log";
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static final long MAX_RETRY_DELAY_MILLIS = 30_000L;

  private static MatchReportService instance = null;

  private final MatchReportConfig config;
  private final ReportLedger ledger;
  private final TokenProvider tokenProvider;
  private final ReportTransport transport;
  private final Logger logger;
  private final BlockingQueue<MatchSummary> queue;
  private final Thread worker;
  private final AtomicBoolean skipWarningLogged = new AtomicBoolean(false);
  private volatile boolean running = true;

  MatchReportService(MatchReportConfig config, ReportLedger ledger, TokenProvider tokenProvider,
      ReportTransport transport, Logger logger) {
    this.config = config;
    this.ledger = ledger;
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
      logger.info("MCA match reporting is disabled (match-reporting.enabled=false)");
      return;
    }
    if (config.getApiUrl().isEmpty()) {
      logger.warning("MCA match reporting is disabled: match-reporting.api-url is not set");
      return;
    }
    if (!config.hasCredentials()) {
      logger.warning("MCA match reporting is disabled: no credentials configured. Set "
          + "match-reporting.credentials.service-token (static service token) or the "
          + "client_credentials triple (token-url/client-id/client-secret)");
      return;
    }

    ReportLedger ledger = new ReportLedger(new File(dataFolder, LEDGER_FILE_NAME),
        config.getLedgerCapacity(), logger);
    TokenProvider tokenProvider = buildTokenProvider(config);
    MatchReportService service = new MatchReportService(config, ledger, tokenProvider,
        new UrlConnectionTransport(), logger);
    service.worker.start();
    instance = service;

    logger.info("MCA match reporting enabled (endpoint=" + config.getApiUrl()
        + ", credentials=" + (tokenProvider.getKind() == TokenProvider.Kind.SERVICE_TOKEN
            ? "service token" : "client_credentials")
        + ", max-attempts=" + config.getMaxAttempts() + ")");
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

  static synchronized MatchReportService getInstance() {
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
    if (winner == null) {
      // The game-report contract requires winner_team; draws cannot be
      // expressed on this endpoint (see PENDING-DECISIONS.md).
      this.logger.warning("MCA match report skipped: the game ended without a winner and the "
          + "POST /api/matches contract does not accept draw results");
      return;
    }
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

    this.logger.severe("MCA match report dropped after " + maxAttempts + " attempts (game_id="
        + summary.getGameId() + ", map=" + summary.getMapName() + "). The result was NOT "
        + "recorded by MatchCoreArena; see PENDING-DECISIONS.md for manual recovery options.");
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
