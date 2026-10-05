package dev.tradechest.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TradeEngine {
    private static final long CANDIDATE_CACHE_TICKS = 20L;
    private final TradeChestPlugin plugin;
    private final TradeChestService chestService;
    private final Map<ChestKey, CandidateCache> candidates = new HashMap<>();

    public TradeEngine(TradeChestPlugin plugin, TradeChestService chestService) {
        this.plugin = plugin;
        this.chestService = chestService;
    }

    public int process(TradeChestRecord record) {
        if (!record.hasInput() || !chestService.isLoaded(record)) return 0;
        World world = Bukkit.getWorld(record.key().worldId());
        if (world == null) return 0;
        Location center = record.key().center(world);
        List<Villager> villagers = eligibleVillagers(record, world, center);
        if (villagers.isEmpty()) return 0;
        PluginConfig config = plugin.settings();
        ItemStack[] work = record.snapshot();
        int completed = 0;
        while (completed < config.maxTradesPerPass()) {
            boolean madeOne = false;
            for (Villager villager : villagers) {
                if (!isEligible(villager, center, config.tradeRadius())) continue;
                List<MerchantRecipe> recipes = villager.getRecipes();
                for (int recipeIndex = 0; recipeIndex < recipes.size(); recipeIndex++) {
                    MerchantRecipe recipe = recipes.get(recipeIndex);
                    if (config.onlyTradesThatOutputEmerald() && recipe.getResult().getType() != Material.EMERALD) continue;
                    if (!config.infiniteVillagerStock() && recipe.getUses() >= recipe.getMaxUses()) continue;
                    PricingService.EffectiveTrade trade = PricingService.effectiveTrade(villager, recipe, record.ownerId());
                    if (trade == null) continue;
                    if (tryApply(work, trade)) {
                        if (!config.infiniteVillagerStock()) {
                            MerchantRecipe used = new MerchantRecipe(recipe);
                            used.setUses(Math.min(used.getMaxUses(), used.getUses() + 1));
                            villager.setRecipe(recipeIndex, used);
                        }
                        completed++;
                        madeOne = true;
                        break;
                    }
                }
                if (madeOne || completed >= config.maxTradesPerPass()) break;
            }
            if (!madeOne) break;
        }
        if (completed > 0 && !chestService.commit(record, work)) return 0;
        return completed;
    }

    static boolean tryApply(ItemStack[] original, PricingService.EffectiveTrade trade) {
        ItemStack[] work = InventoryOps.deepCopy(original, TradeChestRecord.SIZE);
        if (!InventoryOps.consumeFully(work, 0, TradeChestRecord.INPUT_END_EXCLUSIVE, trade.costA())) return false;
        if (!InventoryOps.isEmpty(trade.costB()) && !InventoryOps.consumeFully(work, 0, TradeChestRecord.INPUT_END_EXCLUSIVE, trade.costB())) return false;
        if (!InventoryOps.insertFully(work, TradeChestRecord.OUTPUT_START, TradeChestRecord.SIZE, trade.result())) return false;
        System.arraycopy(work, 0, original, 0, original.length);
        return true;
    }

    private List<Villager> eligibleVillagers(TradeChestRecord record, World world, Location center) {
        long tick = plugin.currentTick();
        CandidateCache cached = candidates.get(record.key());
        if (cached != null && tick - cached.tick <= CANDIDATE_CACHE_TICKS) {
            List<Villager> resolved = new ArrayList<>(cached.ids.size());
            for (UUID id : cached.ids) {
                if (Bukkit.getEntity(id) instanceof Villager villager && isEligible(villager, center, plugin.settings().tradeRadius())) resolved.add(villager);
            }
            if (!resolved.isEmpty()) return resolved;
        }
        int r = plugin.settings().tradeRadius();
        List<Villager> found = new ArrayList<>(world.getNearbyEntitiesByType(Villager.class, center, r, r, r, villager -> isEligible(villager, center, r)));
        found.sort(Comparator.comparingDouble((Villager v) -> v.getLocation().distanceSquared(center)).thenComparing(Villager::getUniqueId));
        List<UUID> ids = found.stream().map(Villager::getUniqueId).toList();
        candidates.put(record.key(), new CandidateCache(tick, ids));
        return found;
    }

    private static boolean isEligible(Villager villager, Location center, int radius) {
        if (villager == null || !villager.isValid() || villager.isDead() || !villager.isAdult()) return false;
        Villager.Profession profession = villager.getProfession();
        if (profession == Villager.Profession.NONE || profession == Villager.Profession.NITWIT) return false;
        if (!villager.getWorld().equals(center.getWorld())) return false;
        double dx = Math.abs(villager.getX() - center.getX());
        double dy = Math.abs(villager.getY() - center.getY());
        double dz = Math.abs(villager.getZ() - center.getZ());
        return dx <= radius && dy <= radius && dz <= radius;
    }

    public void invalidate(ChestKey key) { candidates.remove(key); }
    private record CandidateCache(long tick, List<UUID> ids) {}
}
