package io.github.bedwarsrel.mca;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.github.bedwarsrel.BedwarsRel;
import io.github.bedwarsrel.game.Game;
import io.github.bedwarsrel.utils.ChatWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Room-assignment integration of the MCA room layer (HA-Contract 1.10.0,
 * knowledge/0004).
 *
 * <p>When a player lands on this sub-server (bungee mode), the service
 * queries {@code GET /api/matchmaking/assignment/{uuid}} off the main
 * thread, reusing the {@code match-reporting} credential block
 * ({@link MatchReportConfig} / {@link TokenProvider}). Outcomes:
 * <ul>
 *   <li>valid assignment with a room: the player is placed into that
 *       BedwarsRel game on the main thread via
 *       {@code GameManager::getGame(room)} + {@code Game::playerJoins}
 *       — the MCA room value is a BedwarsRel GAME NAME, not a region
 *       name;</li>
 *   <li>{@code assigned:false}, unknown room, failed join, query failure
 *       or timeout: the player is sent back to the proxy lobby
 *       ({@code bungeecord.hubserver}) with one readable message — never
 *       blocked, never kicked silently.</li>
 * </ul>
 *
 * <p>A successful assignment-driven join remembers the pair
 * (game name → {@code group_id}) until game over, where
 * {@link MatchReportService} consumes it and echoes
 * {@code matchmaking_group_id} back to MCA so the settlement releases the
 * room reservation (contract 1.10.0 round trip). Reports without a group
 * id remain valid; the MCA-side TTL release is the backstop.
 */
public final class RoomAssignmentService {

  /** Outcome of one assignment lookup (worker-thread value object). */
  enum Outcome {
    /** Valid assignment: join the room carried by the decision. */
    JOIN_ROOM,
    /** MCA answered {@code assigned:false} (plain or with a reason). */
    NO_ASSIGNMENT,
    /** Query failed / timed out / unparsable — degrade to no assignment. */
    UNAVAILABLE
  }

  /** Immutable result of one assignment lookup. */
  static final class Decision {

    final Outcome outcome;
    final String room;
    final long groupId;
    final String detail;

    private Decision(Outcome outcome, String room, long groupId, String detail) {
      this.outcome = outcome;
      this.room = room;
      this.groupId = groupId;
      this.detail = detail;
    }

    static Decision joinRoom(String room, long groupId) {
      return new Decision(Outcome.JOIN_ROOM, room, groupId, "");
    }

    static Decision noAssignment(String reason) {
      return new Decision(Outcome.NO_ASSIGNMENT, null, 0L, reason == null ? "" : reason);
    }

    static Decision unavailable(String detail) {
      return new Decision(Outcome.UNAVAILABLE, null, 0L, detail);
    }
  }

  static final String MSG_UNAVAILABLE =
      "MatchCoreArena: could not verify your match assignment - returning you to the lobby.";
  static final String MSG_NO_ASSIGNMENT =
      "MatchCoreArena: no active match assignment for you - returning you to the lobby.";
  static final String MSG_ROOM_UNAVAILABLE =
      "MatchCoreArena: your assigned room is not available - returning you to the lobby.";
  static final String REASON_NO_ROOM_AVAILABLE = "no_room_available";
  static final String MATCHES_PATH = "/api/matches";
  static final String ASSIGNMENT_PATH = "/api/matchmaking/assignment/";

  private static RoomAssignmentService instance = null;

  private final MatchReportConfig config;
  private final String assignmentUrlPrefix;
  private final TokenProvider tokenProvider;
  private final ReportTransport transport;
  private final Logger logger;
  private final ExecutorService executor;
  private final ConcurrentHashMap<String, Long> groupIdByGame =
      new ConcurrentHashMap<String, Long>();

  RoomAssignmentService(MatchReportConfig config, String assignmentUrlPrefix,
      TokenProvider tokenProvider, ReportTransport transport, Logger logger) {
    this.config = config;
    this.assignmentUrlPrefix = assignmentUrlPrefix;
    this.tokenProvider = tokenProvider;
    this.transport = transport;
    this.logger = logger;
    this.executor = new ThreadPoolExecutor(2, 8, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(64),
        new ThreadFactory() {
          private final AtomicInteger counter = new AtomicInteger();

          @Override
          public Thread newThread(Runnable runnable) {
            Thread thread =
                new Thread(runnable, "BedwarsRel-MCA-Assignment-" + this.counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          }
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Initializes the singleton from the shared {@code match-reporting}
   * configuration. Safe to call again (e.g. on reload): the previous worker
   * pool is shut down first.
   */
  public static synchronized void init(MatchReportConfig config, Logger logger) {
    shutdown();

    if (!config.isEnabled()) {
      logger.info("MCA room assignment self-check: enabled=false — assignment routing is "
          + "disabled, players keep the legacy join behavior");
      return;
    }
    if (config.getApiUrl().isEmpty()) {
      logger.warning("MCA room assignment self-check: NOT OPERATIONAL (api-url missing)");
      return;
    }
    if (!config.hasCredentials()) {
      logger.warning("MCA room assignment self-check: NOT OPERATIONAL (no credentials; set "
          + "match-reporting.credentials.service-token or the client_credentials triple)");
      return;
    }
    String prefix = deriveAssignmentUrl(config);
    if (prefix == null) {
      logger.warning("MCA room assignment self-check: NOT OPERATIONAL (assignment endpoint not "
          + "derivable from api-url; set match-reporting.assignment-url explicitly)");
      return;
    }

    instance = new RoomAssignmentService(config, prefix,
        MatchReportService.buildTokenProvider(config), new UrlConnectionTransport(), logger);
    logger.info("MCA room assignment self-check: OPERATIONAL — endpoint=" + prefix
        + "<uuid>, credentials=" + config.describeCredentials()
        + ", timeout=" + config.getTimeoutMillis() + "ms");
  }

  public static synchronized void shutdown() {
    if (instance != null) {
      instance.executor.shutdownNow();
      instance = null;
    }
  }

  /** Whether assignment routing is active (bungee join hook checks this). */
  public static synchronized boolean isOperational() {
    return instance != null;
  }

  /** Singleton accessor (returns {@code null} when not operational). */
  public static synchronized RoomAssignmentService getInstance() {
    return instance;
  }

  /**
   * Consumes the matchmaking group id remembered for a game (game over →
   * report round trip). Returns {@code null} when nothing was remembered
   * (game not entered through an assignment, or service not operational).
   */
  public static Long consumeGroupIdForGame(String gameName) {
    RoomAssignmentService service;
    synchronized (RoomAssignmentService.class) {
      service = instance;
    }
    if (service == null || gameName == null) {
      return null;
    }
    return service.groupIdByGame.remove(gameName);
  }

  /**
   * Derives the assignment endpoint prefix (player UUID is appended at query
   * time). An explicit {@code assignment-url} wins; otherwise the standard
   * report endpoint {@code .../api/matches} is rewritten to
   * {@code .../api/matchmaking/assignment/}. Returns {@code null} when
   * neither yields a URL.
   */
  static String deriveAssignmentUrl(MatchReportConfig config) {
    String explicit = config.getAssignmentUrl();
    if (!explicit.isEmpty()) {
      return explicit.endsWith("/") ? explicit : explicit + "/";
    }
    String apiUrl = config.getApiUrl();
    if (apiUrl.endsWith(MATCHES_PATH)) {
      return apiUrl.substring(0, apiUrl.length() - MATCHES_PATH.length()) + ASSIGNMENT_PATH;
    }
    return null;
  }

  /**
   * Main-thread entry point from the bungee join hook: enqueues one
   * assignment lookup for the player. Never blocks the login; when the worker
   * pool is saturated the player is returned to the hub immediately.
   */
  public void handlePlayerJoin(final Player player) {
    final UUID uuid = player.getUniqueId();
    try {
      this.executor.execute(new Runnable() {
        @Override
        public void run() {
          final Decision decision = RoomAssignmentService.this.queryAssignment(uuid.toString());
          new BukkitRunnable() {
            @Override
            public void run() {
              RoomAssignmentService.this.applyDecision(uuid, decision);
            }
          }.runTask(BedwarsRel.getInstance());
        }
      });
    } catch (RejectedExecutionException e) {
      this.logger.warning("MCA room assignment skipped: worker pool saturated (player "
          + player.getName() + ") — returning the player to the lobby");
      this.sendToHubWithMessage(player, MSG_UNAVAILABLE);
    }
  }

  /**
   * Performs one assignment lookup on a worker thread. Never throws; every
   * failure mode degrades to a non-join decision (spec: degrade to "no
   * assignment", never block login).
   */
  Decision queryAssignment(String uuid) {
    String authorization;
    try {
      authorization = this.tokenProvider.getAuthorizationHeader();
    } catch (IOException e) {
      return Decision.unavailable("credential exchange failed: " + e.getMessage());
    }

    ReportTransport.Response response;
    try {
      Map<String, String> headers = new HashMap<String, String>();
      headers.put("Authorization", authorization);
      response = this.transport.get(this.assignmentUrlPrefix + uuid, headers,
          this.config.getTimeoutMillis());
    } catch (IOException e) {
      return Decision.unavailable("MCA unreachable: " + e.getMessage());
    }

    if (response.getStatus() != 200) {
      return Decision.unavailable("MCA answered HTTP " + response.getStatus()
          + (response.getBody().isEmpty() ? "" : " body=" + truncate(response.getBody())));
    }

    MatchReportPayload.JsonObject envelope;
    try {
      envelope = MatchReportPayload.JsonParser.parseObject(response.getBody());
    } catch (MatchReportPayload.JsonParseException e) {
      return Decision.unavailable("MCA response could not be parsed: " + e.getMessage());
    } catch (RuntimeException e) {
      return Decision.unavailable("MCA response could not be parsed: " + e.getMessage());
    }
    if (envelope == null) {
      return Decision.unavailable("MCA response is not a JSON object");
    }
    MatchReportPayload.JsonValue successValue = envelope.fields.get("success");
    if (successValue == null || !successValue.booleanValue) {
      return Decision.unavailable("MCA response envelope success != true");
    }
    MatchReportPayload.JsonValue dataValue = envelope.fields.get("data");
    MatchReportPayload.JsonObject data = dataValue == null ? null : dataValue.asObject();
    if (data == null) {
      return Decision.unavailable("MCA response lacks the data object");
    }
    MatchReportPayload.JsonValue assignedValue = data.fields.get("assigned");
    boolean assigned = assignedValue != null && assignedValue.booleanValue;
    if (!assigned) {
      return Decision.noAssignment(data.getString("reason"));
    }
    String room = data.getString("room");
    if (room == null || room.trim().isEmpty()) {
      return Decision.unavailable("MCA reported assigned:true without a room");
    }
    return Decision.joinRoom(room.trim(), data.getLong("group_id", 0L));
  }

  /** Main-thread application of a lookup result. */
  void applyDecision(UUID uuid, Decision decision) {
    Player player = BedwarsRel.getInstance().getServer().getPlayer(uuid);
    if (player == null || !player.isOnline()) {
      // left the server while the query was in flight
      return;
    }

    if (decision.outcome == Outcome.NO_ASSIGNMENT) {
      if (REASON_NO_ROOM_AVAILABLE.equals(decision.detail)) {
        this.logger.info("MCA room assignment for player " + player.getName()
            + ": MCA answered no_room_available — returning the player to the lobby");
        this.sendToHubWithMessage(player, MSG_ROOM_UNAVAILABLE);
      } else {
        this.sendToHubWithMessage(player, MSG_NO_ASSIGNMENT);
      }
      return;
    }

    if (decision.outcome == Outcome.UNAVAILABLE) {
      this.logger.warning("MCA room assignment query failed (player " + player.getName()
          + "): " + decision.detail + " — returning the player to the lobby");
      this.sendToHubWithMessage(player, MSG_UNAVAILABLE);
      return;
    }

    Game game = BedwarsRel.getInstance().getGameManager().getGame(decision.room);
    if (game == null) {
      this.logger.warning("MCA room assignment for player " + player.getName()
          + " named unknown room '" + decision.room + "' — returning the player to the lobby");
      this.sendToHubWithMessage(player, MSG_ROOM_UNAVAILABLE);
      return;
    }

    boolean joined;
    try {
      joined = game.playerJoins(player);
    } catch (RuntimeException e) {
      this.logger.log(Level.WARNING, "MCA room assignment: join of room '" + decision.room
          + "' threw for player " + player.getName(), e);
      joined = false;
    }

    if (joined) {
      if (decision.groupId > 0L) {
        this.groupIdByGame.put(game.getName(), Long.valueOf(decision.groupId));
      }
      this.logger.info("MCA room assignment: player " + player.getName() + " joined room '"
          + game.getName() + "' (group_id=" + decision.groupId + ")");
      return;
    }

    // playerJoins already messaged/kicked where its own branches can; cover
    // the remaining cases (stopped game, event cancelled without kick) with
    // a delayed check that only acts when the player is still here.
    this.scheduleJoinFailureFallback(player, decision.room);
  }

  private void scheduleJoinFailureFallback(final Player player, final String room) {
    final UUID uuid = player.getUniqueId();
    new BukkitRunnable() {
      @Override
      public void run() {
        Player current = BedwarsRel.getInstance().getServer().getPlayer(uuid);
        if (current == null || !current.isOnline()) {
          return;
        }
        RoomAssignmentService.this.logger.warning("MCA room assignment: room '" + room
            + "' rejected player " + current.getName()
            + " — returning the player to the lobby");
        RoomAssignmentService.this.sendToHubWithMessage(current, MSG_ROOM_UNAVAILABLE);
      }
    }.runTaskLater(BedwarsRel.getInstance(), 40L);
  }

  /**
   * Sends the player to the proxy hub and delivers one readable message
   * afterwards (the delayed bungee-message pattern of
   * {@code BungeeGameCycle::onPlayerJoins} is the precedent). Without a
   * configured hub the message is delivered directly and nothing is kicked.
   */
  void sendToHubWithMessage(final Player player, final String message) {
    final String hub = BedwarsRel.getInstance().getBungeeHub();
    if (hub == null || hub.isEmpty()) {
      player.sendMessage(ChatWriter.pluginMessage(ChatColor.RED + message));
      this.logger.warning("MCA room assignment: cannot return player " + player.getName()
          + " to the hub — bungeecord.hubserver is not configured");
      return;
    }

    final String playerName = player.getName();
    new BukkitRunnable() {
      @Override
      public void run() {
        Player current = BedwarsRel.getInstance().getServer().getPlayer(playerName);
        if (current == null || !current.isOnline()) {
          return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(hub);
        current.sendPluginMessage(BedwarsRel.getInstance(), "BungeeCord", out.toByteArray());
      }
    }.runTaskLater(BedwarsRel.getInstance(), 5L);

    new BukkitRunnable() {
      @Override
      public void run() {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Message");
        out.writeUTF(playerName);
        out.writeUTF(ChatWriter.pluginMessage(ChatColor.RED + message));
        Player current = BedwarsRel.getInstance().getServer().getPlayer(playerName);
        if (current != null && current.isOnline()) {
          current.sendPluginMessage(BedwarsRel.getInstance(), "BungeeCord", out.toByteArray());
        }
      }
    }.runTaskLater(BedwarsRel.getInstance(), 60L);
  }

  /** Test/inspection hook: group ids currently remembered per game name. */
  Long peekGroupIdForGame(String gameName) {
    return this.groupIdByGame.get(gameName);
  }

  /** Test hook: remember a group id for a game without a real join. */
  void rememberGroupIdForGame(String gameName, long groupId) {
    this.groupIdByGame.put(gameName, Long.valueOf(groupId));
  }

  private static String truncate(String body) {
    return body.length() <= 256 ? body : body.substring(0, 256) + "...";
  }
}
