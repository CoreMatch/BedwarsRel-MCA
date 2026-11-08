package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TokenProviderTest {

  /** Scripted transport returning canned responses in order. */
  private static final class FakeTransport implements ReportTransport {

    private final List<Response> responses = new ArrayList<Response>();
    private final List<String> requestedUrls = new ArrayList<String>();
    private final List<String> requestedBodies = new ArrayList<String>();

    private FakeTransport enqueue(int status, String body) {
      this.responses.add(new Response(status, body));
      return this;
    }

    @Override
    public Response post(String url, String contentType, byte[] body,
        Map<String, String> headers, int timeoutMillis) throws IOException {
      this.requestedUrls.add(url);
      this.requestedBodies.add(new String(body, "UTF-8"));
      if (this.responses.isEmpty()) {
        throw new IOException("no scripted response left");
      }
      return this.responses.remove(0);
    }

    @Override
    public Response get(String url, Map<String, String> headers, int timeoutMillis) {
      throw new UnsupportedOperationException("GET not scripted in TokenProviderTest");
    }
  }

  @Test
  public void serviceTokenIsUsedVerbatim() throws IOException {
    TokenProvider provider = TokenProvider.forServiceToken("static-token");
    assertEquals("Bearer static-token", provider.getAuthorizationHeader());
    assertEquals(TokenProvider.Kind.SERVICE_TOKEN, provider.getKind());
  }

  @Test
  public void clientCredentialsExchangeFetchesAndCachesToken() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueue(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    TokenProvider provider = TokenProvider.forClientCredentials(
        "http://auth.local/oauth/token", "bedwars", "secret", "match:report", transport, 1000);

    assertEquals("Bearer tok-1", provider.getAuthorizationHeader());
    // cached: no second exchange for the next call
    assertEquals("Bearer tok-1", provider.getAuthorizationHeader());
    assertEquals(1, transport.requestedUrls.size());

    String form = transport.requestedBodies.get(0);
    assertTrue(form.contains("grant_type=client_credentials"));
    assertTrue(form.contains("client_id=bedwars"));
    assertTrue(form.contains("client_secret=secret"));
    assertTrue(form.contains("scope=match%3Areport"));
  }

  @Test
  public void invalidateForcesReexchange() throws IOException {
    FakeTransport transport = new FakeTransport()
        .enqueue(200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}")
        .enqueue(200, "{\"access_token\":\"tok-2\",\"expires_in\":3600}");

    TokenProvider provider = TokenProvider.forClientCredentials(
        "http://auth.local/oauth/token", "id", "secret", "", transport, 1000);

    assertEquals("Bearer tok-1", provider.getAuthorizationHeader());
    provider.invalidate();
    assertEquals("Bearer tok-2", provider.getAuthorizationHeader());
    assertEquals(2, transport.requestedUrls.size());
  }

  @Test
  public void failedExchangeRaisesIOException() {
    FakeTransport transport = new FakeTransport()
        .enqueue(401, "{\"error\":\"invalid_client\"}");

    TokenProvider provider = TokenProvider.forClientCredentials(
        "http://auth.local/oauth/token", "id", "bad", "", transport, 1000);

    try {
      provider.getAuthorizationHeader();
      fail("expected IOException");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("401"));
    }
  }

  @Test
  public void responseWithoutAccessTokenIsRejected() {
    FakeTransport transport = new FakeTransport()
        .enqueue(200, "{\"token_type\":\"Bearer\"}");

    TokenProvider provider = TokenProvider.forClientCredentials(
        "http://auth.local/oauth/token", "id", "secret", "", transport, 1000);

    try {
      provider.getAuthorizationHeader();
      fail("expected IOException");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("access_token"));
    }
  }

  @Test
  public void jsonStringFieldExtraction() {
    String json = "{\"access_token\":\"abc-123\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    assertEquals("abc-123", TokenProvider.extractJsonStringField(json, "access_token"));
    assertEquals("Bearer", TokenProvider.extractJsonStringField(json, "token_type"));
    assertNull(TokenProvider.extractJsonStringField(json, "missing"));
    assertNull(TokenProvider.extractJsonStringField(null, "access_token"));
  }

  @Test
  public void jsonStringFieldHandlesEscapes() {
    assertEquals("a\"b\\c", TokenProvider.extractJsonStringField("{\"t\":\"a\\\"b\\\\c\"}", "t"));
  }

  @Test
  public void jsonNumberFieldExtraction() {
    String json = "{\"access_token\":\"x\", \"expires_in\": 7200}";
    assertEquals(7200L, TokenProvider.extractJsonNumberField(json, "expires_in"));
    assertEquals(-1L, TokenProvider.extractJsonNumberField(json, "missing"));
    assertEquals(-1L, TokenProvider.extractJsonNumberField("{\"expires_in\":\"no\"}", "expires_in"));
  }
}
