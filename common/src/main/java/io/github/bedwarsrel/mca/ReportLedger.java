package io.github.bedwarsrel.mca;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent record of game ids that were already accepted by the
 * MatchCoreArena server.
 *
 * <p>The settlement endpoint has no server-side deduplication, so this
 * ledger is the plugin's idempotency guard: a game whose id is recorded
 * here is never reported again, even across server restarts. The ledger is
 * bounded — only the most recent {@code capacity} ids are kept.
 *
 * <p>All operations are best-effort: IO problems are logged and never
 * propagate into the game flow.
 */
public final class ReportLedger {

  private static final Charset UTF8 = Charset.forName("UTF-8");

  private final File file;
  private final int capacity;
  private final Logger logger;
  private final LinkedHashSet<String> ids = new LinkedHashSet<String>();

  public ReportLedger(File file, int capacity, Logger logger) {
    this.file = file;
    this.capacity = Math.max(1, capacity);
    this.logger = logger;
    this.load();
  }

  private void load() {
    if (!this.file.isFile()) {
      return;
    }
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(
          new InputStreamReader(new FileInputStream(this.file), UTF8));
      String line;
      while ((line = reader.readLine()) != null) {
        String id = line.trim();
        if (id.isEmpty()) {
          continue;
        }
        this.ids.add(id);
        while (this.ids.size() > this.capacity) {
          Iterator<String> it = this.ids.iterator();
          it.next();
          it.remove();
        }
      }
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "MCA report ledger could not be read from "
          + this.file.getAbsolutePath() + "; starting with an empty ledger", e);
      this.ids.clear();
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ignored) {
          // nothing to do
        }
      }
    }
  }

  public synchronized boolean isReported(String gameId) {
    return gameId != null && this.ids.contains(gameId);
  }

  /**
   * Records a successfully reported game id. Appends to the ledger file and
   * rewrites the file when the bounded size is exceeded.
   */
  public synchronized void markReported(String gameId) {
    if (gameId == null || gameId.isEmpty() || !this.ids.add(gameId)) {
      return;
    }

    boolean rewritten = false;
    if (this.ids.size() > this.capacity) {
      while (this.ids.size() > this.capacity) {
        Iterator<String> it = this.ids.iterator();
        it.next();
        it.remove();
      }
      rewritten = this.rewrite();
    }

    if (!rewritten) {
      this.append(gameId);
    }
  }

  public synchronized int size() {
    return this.ids.size();
  }

  private void append(String gameId) {
    File parent = this.file.getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
      this.logger.warning("MCA report ledger directory could not be created: "
          + parent.getAbsolutePath());
      return;
    }
    BufferedWriter writer = null;
    try {
      writer = new BufferedWriter(
          new OutputStreamWriter(new FileOutputStream(this.file, true), UTF8));
      writer.write(gameId);
      writer.newLine();
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "MCA report ledger append failed for game id "
          + gameId + "; duplicate settlement protection may be lost across restarts", e);
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException ignored) {
          // nothing to do
        }
      }
    }
  }

  private boolean rewrite() {
    File parent = this.file.getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
      return false;
    }
    BufferedWriter writer = null;
    try {
      writer = new BufferedWriter(
          new OutputStreamWriter(new FileOutputStream(this.file, false), UTF8));
      List<String> snapshot = new ArrayList<String>(this.ids);
      for (String id : snapshot) {
        writer.write(id);
        writer.newLine();
      }
      return true;
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "MCA report ledger rewrite failed", e);
      return false;
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException ignored) {
          // nothing to do
        }
      }
    }
  }
}
