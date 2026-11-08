package io.github.bedwarsrel.mca;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Configuration of the MCA match reporting pipeline, read from the
 * {@code match-reporting} section of the plugin configuration.
 *
 * <p>Credentials come in two supported forms:
 * <ul>
 *   <li><b>service token</b> — a static bearer token registered in the
 *       MatchCoreArena server's {@code config/tokens.yaml} with the
 *       {@code match:report} scope;</li>
 *   <li><b>client credentials</b> — an OAuth2 {@code client_credentials}
 *       exchange ({@code token-url} + {@code client-id} + {@code client-secret})
 *       whose resulting access token is used as the report token.</li>
 * </ul>
 * When both are configured the service token wins. When neither is present
 * the reporting is skipped with a warning — it never crashes the server.
 */
public final class MatchReportConfig {

  public static final boolean DEFAULT_ENABLED = true;
  public static final String DEFAULT_API_URL = "";
  public static final int DEFAULT_TIMEOUT_MILLIS = 5000;
  public static final int DEFAULT_MAX_ATTEMPTS = 3;
  public static final long DEFAULT_RETRY_DELAY_MILLIS = 2000L;
  public static final int DEFAULT_QUEUE_CAPACITY = 64;
  public static final int DEFAULT_LEDGER_CAPACITY = 2000;
  public static final int DEFAULT_DEAD_LETTER_CAPACITY = 200;
  public static final String DEFAULT_SCOPE = "match:report";
  public static final String DEFAULT_ASSIGNMENT_URL = "";
  static final String LEGACY_TOKEN_PLACEHOLDER = "your-access-token-here";

  private final boolean enabled;
  private final String apiUrl;
  private final String assignmentUrl;
  private final int timeoutMillis;
  private final int maxAttempts;
  private final long retryDelayMillis;
  private final int queueCapacity;
  private final int ledgerCapacity;
  private final int deadLetterCapacity;
  private final String serviceToken;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String scope;

  public MatchReportConfig(boolean enabled, String apiUrl, String assignmentUrl,
      int timeoutMillis, int maxAttempts,
      long retryDelayMillis, int queueCapacity, int ledgerCapacity, int deadLetterCapacity,
      String serviceToken, String tokenUrl, String clientId, String clientSecret, String scope) {
    this.enabled = enabled;
    this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
    this.assignmentUrl = assignmentUrl == null ? "" : assignmentUrl.trim();
    this.timeoutMillis = Math.max(100, timeoutMillis);
    this.maxAttempts = Math.max(1, maxAttempts);
    this.retryDelayMillis = Math.max(0L, retryDelayMillis);
    this.queueCapacity = Math.max(1, queueCapacity);
    this.ledgerCapacity = Math.max(1, ledgerCapacity);
    this.deadLetterCapacity = Math.max(1, deadLetterCapacity);
    this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    this.tokenUrl = tokenUrl == null ? "" : tokenUrl.trim();
    this.clientId = clientId == null ? "" : clientId.trim();
    this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    this.scope = scope == null || scope.trim().isEmpty() ? DEFAULT_SCOPE : scope.trim();
  }

  /** Convenience constructor without an explicit {@code assignment-url}. */
  public MatchReportConfig(boolean enabled, String apiUrl, int timeoutMillis, int maxAttempts,
      long retryDelayMillis, int queueCapacity, int ledgerCapacity, int deadLetterCapacity,
      String serviceToken, String tokenUrl, String clientId, String clientSecret, String scope) {
    this(enabled, apiUrl, DEFAULT_ASSIGNMENT_URL, timeoutMillis, maxAttempts, retryDelayMillis,
        queueCapacity, ledgerCapacity, deadLetterCapacity, serviceToken, tokenUrl, clientId,
        clientSecret, scope);
  }

  /**
   * Loads the configuration from the {@code match-reporting} section.
   * A missing section yields all-default values (reporting disabled in
   * effect, because no API URL / credentials are present).
   */
  public static MatchReportConfig load(ConfigurationSection section) {
    if (section == null) {
      return new MatchReportConfig(DEFAULT_ENABLED, DEFAULT_API_URL, DEFAULT_ASSIGNMENT_URL,
          DEFAULT_TIMEOUT_MILLIS,
          DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MILLIS, DEFAULT_QUEUE_CAPACITY,
          DEFAULT_LEDGER_CAPACITY, DEFAULT_DEAD_LETTER_CAPACITY, "", "", "", "", DEFAULT_SCOPE);
    }

    String serviceToken = section.getString("credentials.service-token", "");
    if (serviceToken == null || serviceToken.trim().isEmpty()) {
      // legacy key kept for compatibility with pre-existing configurations
      String legacyToken = section.getString("access-token", "");
      if (legacyToken != null && !legacyToken.trim().isEmpty()
          && !LEGACY_TOKEN_PLACEHOLDER.equals(legacyToken.trim())) {
        serviceToken = legacyToken;
      }
    }

    return new MatchReportConfig(
        section.getBoolean("enabled", DEFAULT_ENABLED),
        section.getString("api-url", DEFAULT_API_URL),
        section.getString("assignment-url", DEFAULT_ASSIGNMENT_URL),
        section.getInt("timeout-millis", DEFAULT_TIMEOUT_MILLIS),
        section.getInt("retry.max-attempts", DEFAULT_MAX_ATTEMPTS),
        section.getLong("retry.delay-millis", DEFAULT_RETRY_DELAY_MILLIS),
        section.getInt("retry.queue-capacity", DEFAULT_QUEUE_CAPACITY),
        section.getInt("ledger-capacity", DEFAULT_LEDGER_CAPACITY),
        section.getInt("dead-letter-capacity", DEFAULT_DEAD_LETTER_CAPACITY),
        serviceToken,
        section.getString("credentials.token-url", ""),
        section.getString("credentials.client-id", ""),
        section.getString("credentials.client-secret", ""),
        section.getString("credentials.scope", DEFAULT_SCOPE));
  }

  public boolean isEnabled() {
    return this.enabled;
  }

  public String getApiUrl() {
    return this.apiUrl;
  }

  /**
   * Explicit room-assignment endpoint prefix (player UUID is appended).
   * Empty when not configured; {@code RoomAssignmentService} then derives it
   * from {@link #getApiUrl()} if that points at {@code /api/matches}.
   */
  public String getAssignmentUrl() {
    return this.assignmentUrl;
  }

  public int getTimeoutMillis() {
    return this.timeoutMillis;
  }

  public int getMaxAttempts() {
    return this.maxAttempts;
  }

  public long getRetryDelayMillis() {
    return this.retryDelayMillis;
  }

  public int getQueueCapacity() {
    return this.queueCapacity;
  }

  public int getLedgerCapacity() {
    return this.ledgerCapacity;
  }

  public int getDeadLetterCapacity() {
    return this.deadLetterCapacity;
  }

  public String getServiceToken() {
    return this.serviceToken;
  }

  public String getTokenUrl() {
    return this.tokenUrl;
  }

  public String getClientId() {
    return this.clientId;
  }

  public String getClientSecret() {
    return this.clientSecret;
  }

  public String getScope() {
    return this.scope;
  }

  /** Form A configured: static service token. */
  public boolean hasServiceToken() {
    return !this.serviceToken.isEmpty()
        && !LEGACY_TOKEN_PLACEHOLDER.equals(this.serviceToken);
  }

  /** Form B configured: full OAuth2 client_credentials triple. */
  public boolean hasClientCredentials() {
    return !this.tokenUrl.isEmpty() && !this.clientId.isEmpty() && !this.clientSecret.isEmpty();
  }

  public boolean hasCredentials() {
    return this.hasServiceToken() || this.hasClientCredentials();
  }

  /** Reporting can only work with an endpoint and some credential form. */
  public boolean isOperational() {
    return this.enabled && !this.apiUrl.isEmpty() && this.hasCredentials();
  }

  /**
   * Renders the configuration state for the startup self-check log.
   * Secrets (service token, client secret) are never included — only their
   * presence and credential form are shown.
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("enabled=").append(this.enabled);
    sb.append(", endpoint=").append(this.apiUrl.isEmpty() ? "<unset>" : this.apiUrl);
    sb.append(", credentials=").append(this.describeCredentials());
    sb.append(", timeout=").append(this.timeoutMillis).append("ms");
    sb.append(", attempts=").append(this.maxAttempts);
    sb.append(", retry-delay=").append(this.retryDelayMillis).append("ms");
    sb.append(", queue-capacity=").append(this.queueCapacity);
    sb.append(", ledger-capacity=").append(this.ledgerCapacity);
    sb.append(", dead-letter-capacity=").append(this.deadLetterCapacity);
    return sb.toString();
  }

  /** Credential form for logs: which form is active, without any secret. */
  public String describeCredentials() {
    if (this.hasServiceToken()) {
      return "service-token(set)";
    }
    if (this.hasClientCredentials()) {
      return "client_credentials(token-url=" + this.tokenUrl
          + ", client-id=" + this.clientId + ", client-secret=set)";
    }
    return "missing";
  }
}
