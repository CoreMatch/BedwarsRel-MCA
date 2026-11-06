package io.github.bedwarsrel.commands;

import io.github.bedwarsrel.BedwarsRel;
import io.github.bedwarsrel.mca.MatchReportService;
import io.github.bedwarsrel.mca.MatchReportService.ReplayResult;
import io.github.bedwarsrel.utils.ChatWriter;
import java.util.ArrayList;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Administrative sub-command for the MCA match-reporting extension.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /mca deadletter} — print the number of undelivered records</li>
 *   <li>{@code /mca replay [n]} — re-enqueue up to {@code n} recently stored
 *       dead-letter records (default 10, capped at 50)</li>
 * </ul>
 *
 * <p>The command is also reachable as {@code /bw mca …} through the
 * standard BedwarsRel sub-command system. Only server operators (OP) or
 * the console may execute it.
 */
public class McaCommand extends BaseCommand {

  private static final int DEFAULT_REPLAY_COUNT = 10;
  private static final int MAX_REPLAY_COUNT = 50;

  public McaCommand(BedwarsRel plugin) {
    super(plugin);
  }

  /**
   * Shared handler so the same logic can be invoked from both the
   * {@code /bw mca …} BaseCommand path and a dedicated {@code /mca …}
   * top-level Bukkit command.
   */
  public static boolean handle(CommandSender sender, ArrayList<String> args) {
    if (!sender.hasPermission("bw.mca") && !sender.isOp()) {
      sender.sendMessage(ChatWriter.pluginMessage(ChatColor.RED
          + "You don't have permission to execute this command!"));
      return false;
    }

    MatchReportService service = MatchReportService.getInstance();
    String sub = args.isEmpty() ? "" : args.get(0);

    if (sub.equalsIgnoreCase("deadletter")) {
      if (service == null) {
        sender.sendMessage(ChatWriter.pluginMessage(ChatColor.YELLOW
            + "MCA match reporting is not operational."));
        return true;
      }
      int n = service.deadLetterCount();
      if (n == 0) {
        sender.sendMessage(ChatWriter.pluginMessage(
            ChatColor.GREEN + "MCA: no dead-letter records."));
      } else {
        sender.sendMessage(ChatWriter.pluginMessage(
            ChatColor.YELLOW + "MCA: " + n + " dead-letter record(s)."));
      }
      return true;
    }

    if (sub.equalsIgnoreCase("replay")) {
      if (service == null) {
        sender.sendMessage(ChatWriter.pluginMessage(ChatColor.YELLOW
            + "MCA match reporting is not operational — cannot replay."));
        return true;
      }
      int n = DEFAULT_REPLAY_COUNT;
      if (args.size() > 1) {
        try {
          n = Integer.parseInt(args.get(1));
        } catch (NumberFormatException e) {
          sender.sendMessage(ChatWriter.pluginMessage(ChatColor.RED
              + "Invalid number: " + args.get(1)));
          return false;
        }
      }
      if (n <= 0) {
        sender.sendMessage(ChatWriter.pluginMessage(ChatColor.RED
            + "Replay count must be positive."));
        return false;
      }

      ReplayResult result = service.replayDeadLetters(n);
      if (result.total == 0) {
        sender.sendMessage(ChatWriter.pluginMessage(
            ChatColor.GREEN + "MCA: no dead-letter records."));
        return true;
      }
      String msg = "MCA: Replayed " + result.replayed + " / " + result.total
          + " dead-letter record(s).";
      if (result.queueFull) {
        msg += " queue full, stopped at " + result.replayed;
      }
      sender.sendMessage(ChatWriter.pluginMessage(
          result.replayed == result.total && !result.queueFull
              ? ChatColor.GREEN + msg
              : ChatColor.YELLOW + msg));
      return true;
    }

    sender.sendMessage(ChatWriter.pluginMessage(ChatColor.RED
        + "Usage: /mca deadletter | /mca replay [n]"));
    return false;
  }

  @Override
  public boolean execute(CommandSender sender, ArrayList<String> args) {
    return handle(sender, args);
  }

  @Override
  public String[] getArguments() {
    return new String[]{"deadletter | replay [n]"};
  }

  @Override
  public String getCommand() {
    return "mca";
  }

  @Override
  public String getDescription() {
    return "MCA dead-letter inspection and replay";
  }

  @Override
  public String getName() {
    return "MCA";
  }

  @Override
  public String getPermission() {
    return "setup"; // same admin tier as /bw reload
  }

  @Override
  public boolean hasPermission(CommandSender sender) {
    // Allow both console and OP players, unlike BaseCommand which is
    // players-only
    if (sender instanceof Player) {
      if (!sender.hasPermission("bw.mca") && !sender.isOp()) {
        sender.sendMessage(ChatWriter.pluginMessage(ChatColor.RED
            + "You don't have permission to execute this command!"));
        return false;
      }
    }
    return true;
  }
}
