package io.github.bedwarsrel.mca;

import java.io.IOException;
import java.util.Map;

/**
 * Minimal HTTP POST abstraction used by the report pipeline, so the retry
 * logic and the token exchange can be unit-tested without a network.
 */
public interface ReportTransport {

  /** HTTP response: status code plus a bounded response body. */
  final class Response {

    private final int status;
    private final String body;

    public Response(int status, String body) {
      this.status = status;
      this.body = body == null ? "" : body;
    }

    public int getStatus() {
      return this.status;
    }

    public String getBody() {
      return this.body;
    }
  }

  /**
   * Performs one HTTP POST.
   *
   * @param url           absolute endpoint URL
   * @param contentType   value for the Content-Type header
   * @param body          request body bytes
   * @param headers       extra headers (e.g. Authorization), may be empty
   * @param timeoutMillis connect and read timeout
   * @throws IOException on any network-level failure (timeout, refused, DNS)
   */
  Response post(String url, String contentType, byte[] body, Map<String, String> headers,
      int timeoutMillis) throws IOException;
}
