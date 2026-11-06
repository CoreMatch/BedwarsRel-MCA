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
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded store for match reports whose delivery attempts were exhausted.
 *
 * <p>When the retry loop gives up (5xx/network failures only — 4xx
 * rejections are final), the game is written here as one JSON line carrying
 * the timestamp, the {@code game_id}, the last failure reason and the full
 * report payload, so the result does not silently disappear and can be
 * inspected or replayed manually. The store is bounded: only the most
 * recent {@code capacity} entries are kept.
 *
 * <p>All operations are best-effort; IO problems are logged and never
 * propagate into the game flow.
 */
public final class DeadLetterStore {

  static final int MAX_REASON_LENGTH = 500;

  private static final Charset UTF8 = Charset.forName("UTF-8");

  private final File file;
  private final int capacity;
  private final Logger logger;
  private final LinkedList<String> lines = new LinkedList<String>();

  public DeadLetterStore(File file, int capacity, Logger logger) {
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
        if (line.trim().isEmpty()) {
          continue;
        }
        this.lines.add(line);
        while (this.lines.size() > this.capacity) {
          this.lines.removeFirst();
        }
      }
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "MCA dead-letter file could not be read from "
          + this.file.getAbsolutePath(), e);
      this.lines.clear();
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

  /** Number of undelivered reports currently recorded. */
  public synchronized int count() {
    return this.lines.size();
  }

  /**
   * Records one exhausted report.
   *
   * @param gameId      stable idempotency key of the game
   * @param reason      last failure reason (truncated, kept single-line)
   * @param payloadJson the full report payload that could not be delivered
   */
  public synchronized void add(String gameId, String reason, String payloadJson) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"at\":").append(System.currentTimeMillis());
    sb.append(",\"game_id\":\"").append(MatchReportPayload.escape(gameId)).append('"');
    sb.append(",\"reason\":\"").append(MatchReportPayload.escape(truncate(reason))).append('"');
    sb.append(",\"payload\":").append(payloadJson == null ? "null" : payloadJson);
    sb.append('}');

    this.lines.add(sb.toString());
    boolean rewritten = false;
    if (this.lines.size() > this.capacity) {
      while (this.lines.size() > this.capacity) {
        this.lines.removeFirst();
      }
      rewritten = this.rewrite();
    }
    if (!rewritten) {
      this.append(sb.toString());
    }
  }

  /** Snapshot of the raw stored lines, oldest first (for tests/diagnostics). */
  public synchronized List<String> snapshot() {
    return new ArrayList<String>(this.lines);
  }

  /**
   * Removes up to {@code count} oldest entries from the store.
   *
   * @return the actual number of entries removed
   */
  public synchronized int removeFirst(int count) {
    if (count <= 0) {
      return 0;
    }
    int removed = 0;
    while (removed < count && !this.lines.isEmpty()) {
      this.lines.removeFirst();
      removed++;
    }
    if (removed > 0) {
      this.rewrite();
    }
    return removed;
  }

  public File getFile() {
    return this.file;
  }

  private void append(String line) {
    File parent = this.file.getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
      this.logger.warning("MCA dead-letter directory could not be created: "
          + parent.getAbsolutePath());
      return;
    }
    BufferedWriter writer = null;
    try {
      writer = new BufferedWriter(
          new OutputStreamWriter(new FileOutputStream(this.file, true), UTF8));
      writer.write(line);
      writer.newLine();
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "MCA dead-letter append failed; the exhausted report "
          + "may be lost", e);
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
      for (String line : this.lines) {
        writer.write(line);
        writer.newLine();
      }
      return true;
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "MCA dead-letter rewrite failed", e);
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

  private static String truncate(String reason) {
    if (reason == null) {
      return "";
    }
    String singleLine = reason.replace('\n', ' ').replace('\r', ' ');
    if (singleLine.length() <= MAX_REASON_LENGTH) {
      return singleLine;
    }
    return singleLine.substring(0, MAX_REASON_LENGTH) + "...";
  }
}
