package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.Test;

public class MatchReportConfigTest {

  @Test
  public void missingSectionDegradesToInoperativeDefaults() {
    MatchReportConfig config = MatchReportConfig.load(null);

    assertTrue(config.isEnabled());
    assertEquals("", config.getApiUrl());
    assertFalse(config.hasCredentials());
    assertFalse("no endpoint and no credentials must not be operational",
        config.isOperational());
  }

  @Test
  public void emptySectionHasNoCredentials() {
    MemoryConfiguration section = new MemoryConfiguration();
    MatchReportConfig config = MatchReportConfig.load(section);

    assertFalse(config.hasServiceToken());
    assertFalse(config.hasClientCredentials());
    assertFalse(config.isOperational());
  }

  @Test
  public void legacyAccessTokenIsUsedAsServiceToken() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("api-url", "http://mca.local/api/matches");
    section.set("access-token", "legacy-secret");

    MatchReportConfig config = MatchReportConfig.load(section);

    assertTrue(config.hasServiceToken());
    assertEquals("legacy-secret", config.getServiceToken());
    assertTrue(config.isOperational());
  }

  @Test
  public void legacyPlaceholderTokenIsTreatedAsMissing() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("api-url", "http://mca.local/api/matches");
    section.set("access-token", "your-access-token-here");

    MatchReportConfig config = MatchReportConfig.load(section);

    assertFalse(config.hasServiceToken());
    assertFalse(config.isOperational());
  }

  @Test
  public void serviceTokenWinsOverClientCredentials() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("api-url", "http://mca.local/api/matches");
    section.set("credentials.service-token", "static-token");
    section.set("credentials.token-url", "http://auth.local/oauth/token");
    section.set("credentials.client-id", "bedwars");
    section.set("credentials.client-secret", "secret");

    MatchReportConfig config = MatchReportConfig.load(section);

    assertTrue(config.hasServiceToken());
    assertTrue(config.hasClientCredentials());
    assertEquals("static-token", config.getServiceToken());
  }

  @Test
  public void incompleteClientCredentialsDoNotCount() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("credentials.token-url", "http://auth.local/oauth/token");
    section.set("credentials.client-id", "bedwars");
    // client-secret missing

    MatchReportConfig config = MatchReportConfig.load(section);

    assertFalse(config.hasClientCredentials());
    assertFalse(config.hasCredentials());
  }

  @Test
  public void retryAndTimeoutDefaultsApply() {
    MatchReportConfig config = MatchReportConfig.load(new MemoryConfiguration());

    assertEquals(MatchReportConfig.DEFAULT_TIMEOUT_MILLIS, config.getTimeoutMillis());
    assertEquals(MatchReportConfig.DEFAULT_MAX_ATTEMPTS, config.getMaxAttempts());
    assertEquals(MatchReportConfig.DEFAULT_RETRY_DELAY_MILLIS, config.getRetryDelayMillis());
    assertEquals(MatchReportConfig.DEFAULT_QUEUE_CAPACITY, config.getQueueCapacity());
    assertEquals(MatchReportConfig.DEFAULT_LEDGER_CAPACITY, config.getLedgerCapacity());
    assertEquals(MatchReportConfig.DEFAULT_DEAD_LETTER_CAPACITY,
        config.getDeadLetterCapacity());
    assertEquals("match:report", config.getScope());
  }

  @Test
  public void deadLetterCapacityIsLoadedFromSection() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("dead-letter-capacity", 17);

    assertEquals(17, MatchReportConfig.load(section).getDeadLetterCapacity());
  }

  @Test
  public void valuesAreReadFromSection() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("enabled", false);
    section.set("api-url", " http://mca.local/api/matches ");
    section.set("timeout-millis", 1234);
    section.set("retry.max-attempts", 5);
    section.set("retry.delay-millis", 500);
    section.set("retry.queue-capacity", 8);
    section.set("ledger-capacity", 42);

    MatchReportConfig config = MatchReportConfig.load(section);

    assertFalse(config.isEnabled());
    assertEquals("http://mca.local/api/matches", config.getApiUrl());
    assertEquals(1234, config.getTimeoutMillis());
    assertEquals(5, config.getMaxAttempts());
    assertEquals(500L, config.getRetryDelayMillis());
    assertEquals(8, config.getQueueCapacity());
    assertEquals(42, config.getLedgerCapacity());
  }

  @Test
  public void invalidValuesAreClamped() {
    MatchReportConfig config = new MatchReportConfig(true, "http://x", 0, 0, -5, 0, 0, 0,
        "t", "", "", "", "");

    assertTrue(config.getTimeoutMillis() >= 100);
    assertEquals(1, config.getMaxAttempts());
    assertEquals(0L, config.getRetryDelayMillis());
    assertEquals(1, config.getQueueCapacity());
    assertEquals(1, config.getLedgerCapacity());
    assertEquals(1, config.getDeadLetterCapacity());
  }

  @Test
  public void describeNeverLeaksTheServiceToken() {
    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches",
        5000, 3, 2000L, 64, 2000, 200,
        "super-secret-token-value", "", "", "", "match:report");

    String rendered = config.describe();

    assertFalse("the raw service token must never be logged",
        rendered.contains("super-secret-token-value"));
    assertTrue(rendered.contains("credentials=service-token(set)"));
  }

  @Test
  public void describeNeverLeaksTheClientSecret() {
    MatchReportConfig config = new MatchReportConfig(true, "http://mca.local/api/matches",
        5000, 3, 2000L, 64, 2000, 200,
        "", "http://auth.local/oauth/token", "bedwars-client", "hunter2-secret-value",
        "match:report");

    String rendered = config.describe();

    assertFalse("the raw client secret must never be logged",
        rendered.contains("hunter2-secret-value"));
    assertTrue(rendered.contains("credentials=client_credentials("));
    assertTrue(rendered.contains("token-url=http://auth.local/oauth/token"));
    assertTrue(rendered.contains("client-id=bedwars-client"));
    assertTrue(rendered.contains("client-secret=set"));
  }

  @Test
  public void describeCarriesTheSelfCheckFields() {
    MatchReportConfig config = new MatchReportConfig(false, "", 5000, 3, 2000L, 64, 2000, 200,
        "", "", "", "", "match:report");

    String rendered = config.describe();

    assertTrue(rendered.contains("enabled=false"));
    assertTrue(rendered.contains("endpoint=<unset>"));
    assertTrue(rendered.contains("credentials=missing"));
    assertTrue(rendered.contains("attempts=3"));
    assertTrue(rendered.contains("queue-capacity=64"));
    assertTrue(rendered.contains("ledger-capacity=2000"));
    assertTrue(rendered.contains("dead-letter-capacity=200"));
  }
}
