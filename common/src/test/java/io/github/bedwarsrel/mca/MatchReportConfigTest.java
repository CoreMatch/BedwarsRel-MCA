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
    assertEquals("match:report", config.getScope());
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
    MatchReportConfig config = new MatchReportConfig(true, "http://x", 0, 0, -5, 0, 0,
        "t", "", "", "", "");

    assertTrue(config.getTimeoutMillis() >= 100);
    assertEquals(1, config.getMaxAttempts());
    assertEquals(0L, config.getRetryDelayMillis());
    assertEquals(1, config.getQueueCapacity());
    assertEquals(1, config.getLedgerCapacity());
  }
}
