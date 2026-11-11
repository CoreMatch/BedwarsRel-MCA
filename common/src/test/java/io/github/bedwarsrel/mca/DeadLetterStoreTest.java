package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
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

  @Test
  public void emptyLinesAreSkippedOnLoad() throws Exception {
    File file = new File(folder.getRoot(), "dead.log");
    Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
    w.write("{\"at\":1,\"game_id\":\"g1\",\"reason\":\"r1\",\"payload\":{}}\n");
    w.write("\n");
    w.write("   \n");
    w.write("{\"at\":2,\"game_id\":\"g2\",\"reason\":\"r2\",\"payload\":{}}\n");
    w.close();

    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);
    assertEquals(2, store.count());
    List<String> snap = store.snapshot();
    assertTrue(snap.get(0).contains("g1"));
    assertTrue(snap.get(1).contains("g2"));
  }

  @Test
  public void nonJsonLinesAreLoadedAsIsByStore() throws Exception {
    // DeadLetterStore.load() does NOT parse JSON — it reads every
    // non-empty line verbatim. Malformed JSON lines survive the load
    // step; parsing happens later during replay and is handled there.
    File file = new File(folder.getRoot(), "dead.log");
    Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
    w.write("this is not valid json at all\n");
    w.write("{\"at\":1,\"game_id\":\"good\",\"reason\":\"ok\",\"payload\":{}}\n");
    w.write("{\"at\":2,\"game_id\":\"truncated\",\"reason\":\"x\",\"payload\":\n");
    w.close();

    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);
    assertEquals(3, store.count());
    List<String> snap = store.snapshot();
    assertEquals("this is not valid json at all", snap.get(0));
    assertTrue(snap.get(1).contains("good"));
    assertTrue(snap.get(2).contains("truncated"));
  }

  @Test
  public void capacityEvictsOldestInInsertionOrder() {
    File file = new File(folder.getRoot(), "dead-order.log");
    DeadLetterStore store = new DeadLetterStore(file, 3, LOGGER);

    store.add("game-1", "first", "{}");
    store.add("game-2", "second", "{}");
    store.add("game-3", "third", "{}");
    store.add("game-4", "fourth", "{}"); // evicts game-1
    store.add("game-5", "fifth", "{}");  // evicts game-2

    assertEquals(3, store.count());
    List<String> snap = store.snapshot();
    // oldest remaining (game-3) first, newest (game-5) last
    assertTrue(snap.get(0).contains("game-3"));
    assertTrue(snap.get(1).contains("game-4"));
    assertTrue(snap.get(2).contains("game-5"));

    // persistence also respects the order
    DeadLetterStore reloaded = new DeadLetterStore(file, 3, LOGGER);
    List<String> reloadedSnap = reloaded.snapshot();
    assertEquals(3, reloaded.count());
    assertTrue(reloadedSnap.get(0).contains("game-3"));
    assertTrue(reloadedSnap.get(2).contains("game-5"));
  }

  @Test
  public void removeFirstRemovesOldestEntries() {
    File file = new File(folder.getRoot(), "dead-remove.log");
    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);

    store.add("a", "r", "{}");
    store.add("b", "r", "{}");
    store.add("c", "r", "{}");
    store.add("d", "r", "{}");

    assertEquals(2, store.removeFirst(2));
    assertEquals(2, store.count());
    List<String> snap = store.snapshot();
    assertTrue(snap.get(0).contains("c"));
    assertTrue(snap.get(1).contains("d"));

    // persisted after rewrite
    DeadLetterStore reloaded = new DeadLetterStore(file, 10, LOGGER);
    assertEquals(2, reloaded.count());
    assertTrue(reloaded.snapshot().get(0).contains("c"));
  }

  @Test
  public void removeFirstWithZeroOrNegativeReturnsZero() {
    File file = new File(folder.getRoot(), "dead-zero.log");
    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);
    store.add("g", "r", "{}");
    assertEquals(0, store.removeFirst(0));
    assertEquals(0, store.removeFirst(-5));
    assertEquals(1, store.count());
  }

  @Test
  public void capacityOneEvictsImmediately() {
    File file = new File(folder.getRoot(), "dead-cap1.log");
    DeadLetterStore store = new DeadLetterStore(file, 1, LOGGER);

    store.add("only", "r", "{}");
    store.add("replaces", "r", "{}");

    assertEquals(1, store.count());
    assertFalse(store.snapshot().get(0).contains("only"));
    assertTrue(store.snapshot().get(0).contains("replaces"));
  }

  @Test
  public void payloadNullIsStoredAsNullLiteral() {
    File file = new File(folder.getRoot(), "dead-null.log");
    DeadLetterStore store = new DeadLetterStore(file, 10, LOGGER);
    store.add("game-null", "reason", null);

    List<String> snap = store.snapshot();
    assertEquals(1, snap.size());
    assertTrue("null payload must be stored as JSON null literal",
        snap.get(0).contains("\"payload\":null"));
  }
}
