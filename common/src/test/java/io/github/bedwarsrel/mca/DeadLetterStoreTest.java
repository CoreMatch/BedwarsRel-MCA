package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DeadLetterStoreTest {

  private static final Logger LOGGER = Logger.getLogger("DeadLetterStoreTest");

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void exhaustedReportsAreWrittenAndSurviveReload() {
    File file = new File(folder.getRoot(), "dead.log");
    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);

    store.add("game-a", "HTTP 502 body={\"error\":\"oauth_hrpauth_down\"}",
        "{\"match_type\":\"1v1\"}");

    assertEquals(1, store.count());

    DeadLetterStore reloaded = new DeadLetterStore(file, 10, LOGGER);
    assertEquals(1, reloaded.count());
    String line = reloaded.snapshot().get(0);
    assertTrue(line.contains("\"game_id\":\"game-a\""));
    assertTrue(line.contains("HTTP 502"));
    assertTrue("the full payload must be recoverable",
        line.contains("\"payload\":{\"match_type\":\"1v1\"}"));
    assertTrue(line.contains("\"at\":"));
  }

  @Test
  public void capacityIsBoundedKeepingNewestEntries() {
    File file = new File(folder.getRoot(), "dead.log");
    DeadLetterStore store = new DeadLetterStore(file, 2, LOGGER);

    store.add("game-1", "r1", "{}");
    store.add("game-2", "r2", "{}");
    store.add("game-3", "r3", "{}"); // evicts game-1

    assertEquals(2, store.count());
    String all = store.snapshot().toString();
    assertFalse(all.contains("game-1"));
    assertTrue(all.contains("game-2"));
    assertTrue(all.contains("game-3"));

    DeadLetterStore reloaded = new DeadLetterStore(file, 2, LOGGER);
    assertEquals(2, reloaded.count());
    assertFalse(reloaded.snapshot().toString().contains("game-1"));
  }

  @Test
  public void reasonIsTruncatedAndKeptSingleLine() {
    File file = new File(folder.getRoot(), "dead.log");
    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);

    StringBuilder longReason = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      longReason.append("fail\n");
    }
    store.add("game-x", longReason.toString(), "{}");

    List<String> lines = new DeadLetterStore(file, 10, LOGGER).snapshot();
    assertEquals("entry must remain a single line", 1, lines.size());
    assertTrue(lines.get(0).contains("..."));
    assertTrue(lines.get(0).length() < longReason.length());
  }

  @Test
  public void reasonSpecialCharactersAreEscaped() {
    File file = new File(folder.getRoot(), "dead.log");
    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);

    store.add("game-y", "HTTP 400 body={\"error\":\"mca_report_inconsistent\"}", "{}");

    String line = new DeadLetterStore(file, 10, LOGGER).snapshot().get(0);
    assertTrue(line.contains("\\\"error\\\":\\\"mca_report_inconsistent\\\""));
  }

  @Test
  public void missingFileStartsEmpty() {
    File file = new File(folder.getRoot(), "does-not-exist.log");
    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);
    assertEquals(0, store.count());
  }
}
