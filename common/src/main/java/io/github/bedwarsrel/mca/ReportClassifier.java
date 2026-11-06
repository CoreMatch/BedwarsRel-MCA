package io.github.bedwarsrel.mca;

import java.io.IOException;

/**
 * Classifies match-report failures so the retry loop can act on them:
 *
 * <ul>
 *   <li>{@link Category#SUCCESS}: 2xx — the report was recorded.</li>
 *   <li>{@link Category#CLIENT_ERROR}: 4xx — the server rejected the report
 *       for a reason that retrying cannot fix (bad token, insufficient
 *       scope, unknown participant UUID, malformed body). Logged with the
 *       response body and never retried.</li>
 *   <li>{@link Category#RETRYABLE}: 5xx, unexpected status classes and any
 *       network-level {@link IOException} (timeout, connection refused).
 *       Retried with exponential backoff.</li>
 * </ul>
 */
public final class ReportClassifier {

  public enum Category {
    SUCCESS,
    CLIENT_ERROR,
    RETRYABLE
  }

  private ReportClassifier() {
  }

  public static Category classifyStatus(int httpStatus) {
    if (httpStatus >= 200 && httpStatus < 300) {
      return Category.SUCCESS;
    }
    if (httpStatus >= 400 && httpStatus < 500) {
      return Category.CLIENT_ERROR;
    }
    // 5xx as well as any unexpected status class (1xx/3xx) are treated as
    // transient: they are retried, but never indefinitely.
    return Category.RETRYABLE;
  }

  /** Network-level failures are always worth retrying. */
  public static Category classifyException(IOException exception) {
    return Category.RETRYABLE;
  }
}
