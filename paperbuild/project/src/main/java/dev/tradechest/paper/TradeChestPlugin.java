package dev.tradechest.paper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

public final class TradeChestPlugin extends JavaPlugin {
    private PluginConfig settings;
    private SqliteStorage storage;
    private TradeChestService chests;
    private TradeEngine tradeEngine;
    private HopperService hopperService;
    private UnlimitedTradeService unlimitedTrades;
    private BukkitTask tickTask;
    private long tick;
    private final Set<ChestKey> immediate = new HashSet<>();
    private final Set<ChestKey> persistNextTick = new HashSet<>();
    private NamespacedKey recipeKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        settings = PluginConfig.load(getConfig());
        storage = new SqliteStorage(new File(getDataFolder(), "tradechests.db"), getLogger());
        try { storage.open(); }
        catch (SQLException ex) { getLogger().log(Level.SEVERE, "Cannot open Trade Chest SQLite database; disabling plugin.", ex); getServer().getPluginManager().disablePlugin(this); return; }
        chests = new TradeChestService(this, storage);
        try { chests.load(); }
        catch (SQLException ex) { getLogger().log(Level.SEVERE, "Cannot load Trade Chest data; disabling plugin.", ex); getServer().getPluginManager().disablePlugin(this); return; }
        tradeEngine = new TradeEngine(this, chests);
        hopperService = new HopperService(this, chests);
        unlimitedTrades = new UnlimitedTradeService(this);
        unlimitedTrades.restoreAllLoaded();
        getServer().getPluginManager().registerEvents(new TradeChestListener(this, chests, hopperService, unlimitedTrades), this);
        TradeChestCommand command = new TradeChestCommand(this);
        if (getCommand("tradechest") != null) { getCommand("tradechest").setExecutor(command); getCommand("tradechest").setTabCompleter(command); }
        registerRecipe();
        tickTask = getServer().getScheduler().runTaskTimer(this, this::serverTick, 1L, 1L);
        getLogger().info("TradeChest enabled: " + chests.totalCount() + " persistent chest(s), Paper " + getServer().getMinecraftVersion() + ", Java " + Runtime.version().feature() + ".");
    }

    @Override public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (unlimitedTrades != null) unlimitedTrades.restoreAllLoaded();
        if (chests != null) {
            for (TradeChestRecord record : chests.records()) for (var viewer : java.util.List.copyOf(record.inventory().getViewers())) viewer.closeInventory();
            try { chests.saveAll(); } catch (SQLException ex) { getLogger().log(Level.SEVERE, "Could not save every Trade Chest during shutdown", ex); }
        }
        if (recipeKey != null) Bukkit.removeRecipe(recipeKey);
        if (storage != null) try { storage.close(); } catch (SQLException ex) { getLogger().log(Level.SEVERE, "Could not close Trade Chest database", ex); }
    }

    private void serverTick() {
        tick++;
        if (!persistNextTick.isEmpty()) {
            Set<ChestKey> saving = new HashSet<>(persistNextTick); persistNextTick.clear();
            for (ChestKey key : saving) { TradeChestRecord record = chests.get(key); if (record != null && chests.saveAndMirror(record)) requestImmediateProcessing(key); }
        }
        boolean scheduledPass = tick % settings.processingIntervalTicks() == 0;
        if (scheduledPass || !immediate.isEmpty()) {
            Set<ChestKey> immediateNow = new HashSet<>(immediate); immediate.clear();
            if (scheduledPass) for (TradeChestRecord record : chests.records()) processRecord(record);
            else for (ChestKey key : immediateNow) { TradeChestRecord record = chests.get(key); if (record != null) processRecord(record); }
        }
        if (tick % settings.hopperIntervalTicks() == 0) hopperService.tick();
        if (tick % 100L == 0) chests.reconcileLoadedRecords();
    }

    private void processRecord(TradeChestRecord record) {
        if (!chests.isLoaded(record)) return;
        int completed = tradeEngine.process(record);
        if (completed > 0) getLogger().finest("Trade Chest " + record.key().shortString() + " completed " + completed + " trade(s).");
    }

    private void registerRecipe() {
        recipeKey = new NamespacedKey(this, "trade_chest");
        Bukkit.removeRecipe(recipeKey);
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, chests.createTradeChestItem(1));
        recipe.addIngredient(Material.CHEST);
        recipe.addIngredient(Material.STRING);
        Bukkit.addRecipe(recipe);
    }

    public void reloadPluginConfig() { if (unlimitedTrades != null) unlimitedTrades.restoreAllLoaded(); reloadConfig(); settings = PluginConfig.load(getConfig()); }
    public void requestImmediateProcessing(ChestKey key) { immediate.add(key); if (tradeEngine != null) tradeEngine.invalidate(key); }
    public void scheduleRecordPersist(TradeChestRecord record) { persistNextTick.add(record.key()); }
    public long currentTick() { return tick; }
    public PluginConfig settings() { return settings; }
    public TradeChestService chests() { return chests; }
}
