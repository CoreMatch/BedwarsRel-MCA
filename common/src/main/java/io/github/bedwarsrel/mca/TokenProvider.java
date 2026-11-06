package io.github.bedwarsrel.mca;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Supplies the {@code Authorization} header for match reports.
 *
 * <p>Two credential forms are supported:
 * <ul>
 *   <li><b>service token</b>: a static bearer token (registered in the
 *       MatchCoreArena server's {@code config/tokens.yaml} with the
 *       {@code match:report} scope) used verbatim;</li>
 *   <li><b>client credentials</b>: an OAuth2 {@code client_credentials}
 *       exchange against {@code token-url}; the resulting access token is
 *       cached until shortly before its expiry and re-fetched on demand.</li>
 * </ul>
 */
public final class TokenProvider {

  public enum Kind {
    SERVICE_TOKEN,
    CLIENT_CREDENTIALS
  }

  /** Safety margin before a cached client-credentials token expires. */
  private static final long EXPIRY_SKEW_MILLIS = 60_000L;
  private static final long DEFAULT_TOKEN_TTL_MILLIS = 5 * 60_000L;

  private final Kind kind;
  private final String serviceToken;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String scope;
  private final ReportTransport transport;
  private final int timeoutMillis;

  private String cachedToken;
  private long cachedExpiryMillis;

  private TokenProvider(Kind kind, String serviceToken, String tokenUrl, String clientId,
      String clientSecret, String scope, ReportTransport transport, int timeoutMillis) {
    this.kind = kind;
    this.serviceToken = serviceToken;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.scope = scope;
    this.transport = transport;
    this.timeoutMillis = timeoutMillis;
  }

  public static TokenProvider forServiceToken(String token) {
    return new TokenProvider(Kind.SERVICE_TOKEN, token, null, null, null, null, null, 0);
  }

  public static TokenProvider forClientCredentials(String tokenUrl, String clientId,
      String clientSecret, String scope, ReportTransport transport, int timeoutMillis) {
    return new TokenProvider(Kind.CLIENT_CREDENTIALS, null, tokenUrl, clientId, clientSecret,
        scope, transport, timeoutMillis);
  }

  public Kind getKind() {
    return this.kind;
  }

  /**
   * Returns the value for the {@code Authorization} header, fetching and
   * caching a fresh access token when the client-credentials form is used.
   */
  public synchronized String getAuthorizationHeader() throws IOException {
    if (this.kind == Kind.SERVICE_TOKEN) {
      return "Bearer " + this.serviceToken;
    }

    long now = System.currentTimeMillis();
    if (this.cachedToken == null || now >= this.cachedExpiryMillis) {
      this.fetchToken(now);
    }
    return "Bearer " + this.cachedToken;
  }

  /** Drops the cached token (e.g. after the server answered 401). */
  public synchronized void invalidate() {
    this.cachedToken = null;
    this.cachedExpiryMillis = 0L;
  }

  private void fetchToken(long now) throws IOException {
    StringBuilder form = new StringBuilder();
    form.append("grant_type=client_credentials");
    form.append("&client_id=").append(urlEncode(this.clientId));
    form.append("&client_secret=").append(urlEncode(this.clientSecret));
    if (this.scope != null && !this.scope.isEmpty()) {
      form.append("&scope=").append(urlEncode(this.scope));
    }

    Map<String, String> headers = new HashMap<String, String>();
    ReportTransport.Response response = this.transport.post(this.tokenUrl,
        "application/x-www-form-urlencoded", form.toString().getBytes("UTF-8"), headers,
        this.timeoutMillis);

    if (response.getStatus() < 200 || response.getStatus() >= 300) {
      throw new IOException("token exchange failed with HTTP " + response.getStatus()
          + (response.getBody().isEmpty() ? "" : ": " + truncate(response.getBody())));
    }

    String accessToken = extractJsonStringField(response.getBody(), "access_token");
    if (accessToken == null || accessToken.isEmpty()) {
      throw new IOException("token exchange response carries no access_token");
    }

    long expiresIn = extractJsonNumberField(response.getBody(), "expires_in");
    long ttlMillis = expiresIn > 0 ? expiresIn * 1000L : DEFAULT_TOKEN_TTL_MILLIS;
    this.cachedToken = accessToken;
    this.cachedExpiryMillis = now + Math.max(1000L, ttlMillis - EXPIRY_SKEW_MILLIS);
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("UTF-8 must be available", e);
    }
  }

  private static String truncate(String body) {
    return body.length() <= 256 ? body : body.substring(0, 256) + "...";
  }

  /**
   * Extracts a string field from a flat JSON object. Deliberately minimal:
   * token responses are machine-generated flat objects, and the plugin must
   * not pull in a JSON library just for this.
   */
  static String extractJsonStringField(String json, String field) {
    if (json == null) {
      return null;
    }
    int keyIndex = json.indexOf("\"" + field + "\"");
    if (keyIndex < 0) {
      return null;
    }
    int colon = json.indexOf(':', keyIndex + field.length() + 2);
    if (colon < 0) {
      return null;
    }
    int open = json.indexOf('"', colon + 1);
    if (open < 0) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = open + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '\\' && i + 1 < json.length()) {
        char next = json.charAt(i + 1);
        if (next == '"' || next == '\\') {
          sb.append(next);
          i++;
          continue;
        }
      }
      if (c == '"') {
        return sb.toString();
      }
      sb.append(c);
    }
    return null;
  }

  /** Extracts a numeric field from a flat JSON object; -1 when absent. */
  static long extractJsonNumberField(String json, String field) {
    if (json == null) {
      return -1L;
    }
    int keyIndex = json.indexOf("\"" + field + "\"");
    if (keyIndex < 0) {
      return -1L;
    }
    int colon = json.indexOf(':', keyIndex + field.length() + 2);
    if (colon < 0) {
      return -1L;
    }
    int start = colon + 1;
    while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
      start++;
    }
    int end = start;
    while (end < json.length()) {
      char c = json.charAt(end);
      if ((c < '0' || c > '9') && c != '-') {
        break;
      }
      end++;
    }
    if (end == start) {
      return -1L;
    }
    try {
      return Long.parseLong(json.substring(start, end));
    } catch (NumberFormatException e) {
      return -1L;
    }
  }
}
