package dev.tradechest.paper;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.List;
import java.util.UUID;

public final class PricingService {
    private PricingService() {}

    public static EffectiveTrade effectiveTrade(Villager villager, MerchantRecipe liveRecipe, UUID ownerId) {
        MerchantRecipe recipe = new MerchantRecipe(liveRecipe);
        List<ItemStack> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
        if (!recipe.shouldIgnoreDiscounts() && ownerId != null) {
            int reputation = aggregateReputation(villager.getReputation(ownerId));
            int specialPrice = -((int) Math.floor(reputation * recipe.getPriceMultiplier()));
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null && owner.isOnline()) {
                PotionEffect hero = owner.getPotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
                if (hero != null) {
                    ItemStack base = ingredients.getFirst();
                    double modifier = 0.3D + 0.0625D * hero.getAmplifier();
                    int reduction = (int) Math.floor(modifier * base.getAmount());
                    specialPrice -= Math.max(reduction, 1);
                }
            }
            recipe.setSpecialPrice(specialPrice);
        }
        ItemStack first = recipe.getAdjustedIngredient1();
        if (InventoryOps.isEmpty(first)) return null;
        ItemStack second = ingredients.size() > 1 ? ingredients.get(1).clone() : null;
        return new EffectiveTrade(first.clone(), InventoryOps.isEmpty(second) ? null : second, recipe.getResult().clone());
    }

    static int aggregateReputation(Reputation reputation) {
        if (reputation == null) return 0;
        return reputation.getReputation(ReputationType.MAJOR_NEGATIVE) * -5
                + reputation.getReputation(ReputationType.MINOR_NEGATIVE) * -1
                + reputation.getReputation(ReputationType.MINOR_POSITIVE)
                + reputation.getReputation(ReputationType.MAJOR_POSITIVE) * 5
                + reputation.getReputation(ReputationType.TRADING);
    }

    public record EffectiveTrade(ItemStack costA, ItemStack costB, ItemStack result) {}
}
