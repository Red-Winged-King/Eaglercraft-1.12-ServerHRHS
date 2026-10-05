package dev.tradechest.paper;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Paper-event implementation of unlimited normal player trades; no mixins/NMS/reflection. */
public final class UnlimitedTradeService {
    private static final int CLIENT_MAX_USES = 1_000_000_000;

    private final TradeChestPlugin plugin;
    private final NamespacedKey originalMaxUsesKey;
    private final Map<UUID, List<Integer>> activeOriginalMaxUses = new HashMap<>();

    public UnlimitedTradeService(TradeChestPlugin plugin) {
        this.plugin = plugin;
        this.originalMaxUsesKey = new NamespacedKey(plugin, "original_trade_max_uses");
    }

    public boolean applies(AbstractVillager villager) {
        if (!plugin.settings().playerUnlimitedVillagerStock()) return false;
        if (villager instanceof Villager) return true;
        return plugin.settings().affectWanderingTraders() && villager instanceof WanderingTrader;
    }

    public void prepareForPlayer(AbstractVillager villager) {
        if (!applies(villager)) return;

        restoreStale(villager);
        UUID id = villager.getUniqueId();
        // A villager can only have one active trading player. If another interaction event
        // arrives while the same session is already prepared, never overwrite the saved
        // original max-use list with our temporary values.
        if (activeOriginalMaxUses.containsKey(id)) return;

        List<MerchantRecipe> recipes = villager.getRecipes();
        if (recipes.isEmpty()) return;

        List<Integer> originals = new ArrayList<>(recipes.size());
        List<MerchantRecipe> replacements = new ArrayList<>(recipes.size());
        for (MerchantRecipe recipe : recipes) {
            originals.add(recipe.getMaxUses());
            MerchantRecipe copy = new MerchantRecipe(recipe);
            copy.setUses(0);
            copy.setMaxUses(CLIENT_MAX_USES);
            replacements.add(copy);
        }

        // Recovery metadata is written before the temporary recipe mutation. If the
        // plugin/server is interrupted mid-session, the original max-use values can
        // be restored on the next load.
        villager.getPersistentDataContainer().set(originalMaxUsesKey, PersistentDataType.STRING, encode(originals));
        activeOriginalMaxUses.put(id, originals);
        villager.setRecipes(replacements);
    }

    public void handlePlayerTrade(PlayerTradeEvent event) {
        AbstractVillager villager = event.getVillager();
        if (!applies(villager)) return;

        // Paper handles payment, result, criteria/statistics, villager XP, leveling,
        // reputation and sounds. This flag prevents only stock use from increasing.
        event.setIncreaseTradeUses(false);

        // Keep the recipe sent back through Paper effectively unlimited for the open
        // merchant session so Java/Geyser clients do not see Sold Out.
        MerchantRecipe trade = new MerchantRecipe(event.getTrade());
        trade.setUses(0);
        trade.setMaxUses(CLIENT_MAX_USES);
        event.setTrade(trade);
    }

    public void restore(AbstractVillager villager) {
        UUID id = villager.getUniqueId();
        List<Integer> originals = activeOriginalMaxUses.remove(id);
        if (originals == null) {
            originals = decode(villager.getPersistentDataContainer().get(originalMaxUsesKey, PersistentDataType.STRING));
        }
        if (originals != null) restoreWith(villager, originals);
        villager.getPersistentDataContainer().remove(originalMaxUsesKey);
    }

    public void restoreStale(AbstractVillager villager) {
        if (activeOriginalMaxUses.containsKey(villager.getUniqueId())) return;
        String encoded = villager.getPersistentDataContainer().get(originalMaxUsesKey, PersistentDataType.STRING);
        List<Integer> originals = decode(encoded);
        if (originals != null) {
            restoreWith(villager, originals);
            villager.getPersistentDataContainer().remove(originalMaxUsesKey);
            plugin.getLogger().fine("Restored stale unlimited-trading session for villager " + villager.getUniqueId());
        }
    }

    public void restoreAllLoaded() {
        // Always restore recovery metadata, even if wandering-trader support was
        // disabled after a previous configuration used it.
        for (var world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) restoreStale(villager);
            for (WanderingTrader trader : world.getEntitiesByClass(WanderingTrader.class)) restoreStale(trader);
        }
        for (UUID id : new ArrayList<>(activeOriginalMaxUses.keySet())) {
            if (plugin.getServer().getEntity(id) instanceof AbstractVillager villager) restore(villager);
        }
        activeOriginalMaxUses.clear();
    }

    private void restoreWith(AbstractVillager villager, List<Integer> originals) {
        try {
            List<MerchantRecipe> current = villager.getRecipes();
            List<MerchantRecipe> restored = new ArrayList<>(current.size());
            for (int i = 0; i < current.size(); i++) {
                MerchantRecipe copy = new MerchantRecipe(current.get(i));
                copy.setUses(0);
                if (i < originals.size() && copy.getMaxUses() == CLIENT_MAX_USES) {
                    copy.setMaxUses(Math.max(1, originals.get(i)));
                }
                // Never leak a previous player's temporary reputation/effect pricing
                // after their merchant session closes or after recovery.
                copy.setSpecialPrice(0);
                restored.add(copy);
            }
            villager.setRecipes(restored);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not restore villager trade max-use values", ex);
        }
    }

    private static String encode(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Integer> decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String[] parts = raw.split(",");
            List<Integer> values = new ArrayList<>(parts.length);
            for (String part : parts) values.add(Integer.parseInt(part));
            return values;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
