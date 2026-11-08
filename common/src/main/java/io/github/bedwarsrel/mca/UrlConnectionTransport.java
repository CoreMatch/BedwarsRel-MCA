package io.github.bedwarsrel.mca;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Production {@link ReportTransport} based on {@link HttpURLConnection}
 * (no extra runtime dependency). Connect and read timeouts are always
 * enforced so a hung endpoint can never block the reporter thread.
 */
public final class UrlConnectionTransport implements ReportTransport {

  /** Response bodies are truncated beyond this size; they are only logged. */
  private static final int MAX_BODY_BYTES = 8192;

  @Override
  public Response post(String url, String contentType, byte[] body, Map<String, String> headers,
      int timeoutMillis) throws IOException {
    return execute("POST", url, contentType, body, headers, timeoutMillis);
  }

  @Override
  public Response get(String url, Map<String, String> headers, int timeoutMillis)
      throws IOException {
    return execute("GET", url, null, null, headers, timeoutMillis);
  }

  private static Response execute(String method, String url, String contentType, byte[] body,
      Map<String, String> headers, int timeoutMillis) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    try {
      connection.setRequestMethod(method);
      connection.setConnectTimeout(timeoutMillis);
      connection.setReadTimeout(timeoutMillis);
      if (body != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
      }
      connection.setRequestProperty("Accept", "application/json");
      if (headers != null) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
          connection.setRequestProperty(header.getKey(), header.getValue());
        }
      }

      if (body != null) {
        OutputStream output = connection.getOutputStream();
        try {
          output.write(body);
          output.flush();
        } finally {
          output.close();
        }
      }

      int status = connection.getResponseCode();
      return new Response(status, readBody(connection, status));
    } finally {
      connection.disconnect();
    }
  }

  private static String readBody(HttpURLConnection connection, int status) {
    InputStream stream = null;
    try {
      stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      if (stream == null) {
        return "";
      }
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[1024];
      int read;
      int total = 0;
      while ((read = stream.read(chunk)) != -1) {
        int allowed = Math.min(read, MAX_BODY_BYTES - total);
        if (allowed <= 0) {
          break;
        }
        buffer.write(chunk, 0, allowed);
        total += allowed;
      }
      return new String(buffer.toByteArray(), "UTF-8");
    } catch (IOException e) {
      return "";
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (IOException ignored) {
          // nothing to do
        }
      }
    }
  }
}
