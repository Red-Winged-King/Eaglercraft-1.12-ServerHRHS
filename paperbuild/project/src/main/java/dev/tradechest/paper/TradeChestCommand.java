package dev.tradechest.paper;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public final class TradeChestCommand implements CommandExecutor, TabCompleter {
    private final TradeChestPlugin plugin;
    public TradeChestCommand(TradeChestPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage(ChatColor.YELLOW + "/tradechest give <player> [amount], /tradechest reload, /tradechest status"); return true; }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender,args); case "reload" -> reload(sender); case "status" -> status(sender);
            default -> { sender.sendMessage(ChatColor.RED + "Unknown subcommand."); yield true; }
        };
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tradechest.give")) return deny(sender);
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /tradechest give <player> [amount]"); return true; }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage(ChatColor.RED + "That player is not online."); return true; }
        int amount=1;
        if (args.length>=3) try { amount=Integer.parseInt(args[2]); } catch(NumberFormatException ex) { sender.sendMessage(ChatColor.RED+"Amount must be a number from 1 to 2304."); return true; }
        if (amount<1 || amount>2304) { sender.sendMessage(ChatColor.RED+"Amount must be from 1 to 2304."); return true; }
        int remaining=amount;
        while(remaining>0) {
            int batch=Math.min(64,remaining);
            ItemStack stack=plugin.chests().createTradeChestItem(batch);
            var leftovers=target.getInventory().addItem(stack);
            leftovers.values().forEach(item->target.getWorld().dropItemNaturally(target.getLocation(),item));
            remaining-=batch;
        }
        sender.sendMessage(ChatColor.GREEN+"Gave "+amount+" Trade Chest"+(amount==1?"":"s")+" to "+target.getName()+".");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if(!sender.hasPermission("tradechest.reload")) return deny(sender);
        plugin.reloadPluginConfig(); sender.sendMessage(ChatColor.GREEN+"Trade Chest configuration reloaded."); return true;
    }

    private boolean status(CommandSender sender) {
        if(!sender.hasPermission("tradechest.status")) return deny(sender);
        sender.sendMessage(ChatColor.GOLD+"TradeChest "+plugin.getPluginMeta().getVersion()+ChatColor.GRAY+" | records="+plugin.chests().totalCount()+" loaded="+plugin.chests().loadedCount()+" interval="+plugin.settings().processingIntervalTicks()+"t maxTrades="+plugin.settings().maxTradesPerPass());
        return true;
    }
    private boolean deny(CommandSender sender){sender.sendMessage(ChatColor.RED+"You do not have permission to use that command.");return true;}

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if(args.length==1){List<String> c=new ArrayList<>(); if(sender.hasPermission("tradechest.give"))c.add("give"); if(sender.hasPermission("tradechest.reload"))c.add("reload"); if(sender.hasPermission("tradechest.status"))c.add("status"); String p=args[0].toLowerCase(Locale.ROOT); c.removeIf(v->!v.startsWith(p)); return c;}
        if(args.length==2&&args[0].equalsIgnoreCase("give")&&sender.hasPermission("tradechest.give")){String p=args[1].toLowerCase(Locale.ROOT); return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).filter(n->n.toLowerCase(Locale.ROOT).startsWith(p)).toList();}
        return List.of();
    }
}
