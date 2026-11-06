package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.logging.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReportLedgerTest {

  private static final Logger LOGGER = Logger.getLogger("ReportLedgerTest");

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void markedIdsAreRemembered() {
    File file = new File(folder.getRoot(), "ledger.log");
    ReportLedger ledger = new ReportLedger(file, 100, LOGGER);

    assertFalse(ledger.isReported("game-a"));
    ledger.markReported("game-a");
    assertTrue(ledger.isReported("game-a"));
    assertFalse(ledger.isReported("game-b"));
  }

  @Test
  public void idsSurviveReload() {
    File file = new File(folder.getRoot(), "ledger.log");
    ReportLedger first = new ReportLedger(file, 100, LOGGER);
    first.markReported("game-a");
    first.markReported("game-b");

    // simulates a server restart loading the same ledger file
    ReportLedger second = new ReportLedger(file, 100, LOGGER);
    assertTrue(second.isReported("game-a"));
    assertTrue(second.isReported("game-b"));
    assertEquals(2, second.size());
  }

  @Test
  public void duplicateMarksDoNotDuplicateEntries() {
    File file = new File(folder.getRoot(), "ledger.log");
    ReportLedger ledger = new ReportLedger(file, 100, LOGGER);
    ledger.markReported("game-a");
    ledger.markReported("game-a");

    ReportLedger reloaded = new ReportLedger(file, 100, LOGGER);
    assertEquals(1, reloaded.size());
  }

  @Test
  public void capacityIsBoundedKeepingNewestIds() {
    File file = new File(folder.getRoot(), "ledger.log");
    ReportLedger ledger = new ReportLedger(file, 3, LOGGER);
    ledger.markReported("game-1");
    ledger.markReported("game-2");
    ledger.markReported("game-3");
    ledger.markReported("game-4"); // evicts game-1

    assertEquals(3, ledger.size());
    assertFalse(ledger.isReported("game-1"));
    assertTrue(ledger.isReported("game-4"));

    ReportLedger reloaded = new ReportLedger(file, 3, LOGGER);
    assertEquals(3, reloaded.size());
    assertFalse(reloaded.isReported("game-1"));
    assertTrue(reloaded.isReported("game-4"));
  }

  @Test
  public void missingFileStartsEmpty() {
    File file = new File(folder.getRoot(), "does-not-exist.log");
    ReportLedger ledger = new ReportLedger(file, 10, LOGGER);
    assertEquals(0, ledger.size());
    assertFalse(ledger.isReported("anything"));
  }

  @Test
  public void nullAndEmptyIdsAreIgnored() {
    File file = new File(folder.getRoot(), "ledger.log");
    ReportLedger ledger = new ReportLedger(file, 10, LOGGER);
    ledger.markReported(null);
    ledger.markReported("");
    assertEquals(0, ledger.size());
    assertFalse(ledger.isReported(null));
  }
}
