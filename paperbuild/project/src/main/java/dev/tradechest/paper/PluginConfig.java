package dev.tradechest.paper;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginConfig(
        int tradeRadius,
        int processingIntervalTicks,
        int maxTradesPerPass,
        boolean infiniteVillagerStock,
        boolean playerUnlimitedVillagerStock,
        boolean onlyTradesThatOutputEmerald,
        boolean affectWanderingTraders,
        int hopperIntervalTicks
) {
    public static PluginConfig load(FileConfiguration config) {
        int radius = clamp(config.getInt("trade-radius", 10), 1, 64);
        int process = clamp(config.getInt("processing-interval-ticks", 5), 1, 200);
        int maxTrades = clamp(config.getInt("max-trades-per-pass", 64), 1, 4096);
        int hopper = clamp(config.getInt("hopper-interval-ticks", 8), 1, 200);
        return new PluginConfig(
                radius,
                process,
                maxTrades,
                config.getBoolean("infinite-villager-stock", true),
                config.getBoolean("player-unlimited-villager-stock", true),
                config.getBoolean("only-trades-that-output-emerald", true),
                config.getBoolean("affect-wandering-traders", false),
                hopper
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
