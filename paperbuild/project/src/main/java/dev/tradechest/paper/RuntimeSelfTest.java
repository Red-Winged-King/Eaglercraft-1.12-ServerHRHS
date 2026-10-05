package dev.tradechest.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic tests that must run inside a real Paper server so Bukkit's item
 * registries are initialized. CI invokes this after the clean server reaches Done.
 */
public final class RuntimeSelfTest {
    private RuntimeSelfTest() {}

    public static Result run(TradeChestPlugin plugin) {
        List<String> passed = new ArrayList<>();
        try {
            // 1. 21 String -> 1 Emerald.
            ItemStack[] oneTrade = empty();
            oneTrade[0] = new ItemStack(Material.STRING, 21);
            check(TradeEngine.tryApply(oneTrade, trade(21, 1)), "21 string trade executes");
            check(InventoryOps.isEmpty(oneTrade[0]), "21 string consumed");
            check(oneTrade[27] != null && oneTrade[27].getType() == Material.EMERALD && oneTrade[27].getAmount() == 1,
                    "one emerald produced");
            passed.add("21 String -> 1 Emerald");

            // 2. Discounted effective price 20 with 25 String leaves 5.
            ItemStack[] discounted = empty();
            discounted[0] = new ItemStack(Material.STRING, 25);
            check(TradeEngine.tryApply(discounted, trade(20, 1)), "discounted trade executes");
            check(discounted[0] != null && discounted[0].getAmount() == 5, "discounted trade leaves five string");
            passed.add("discounted 20/25 leaves 5");

            // 3. 40 String at price 20 -> 2 Emeralds.
            ItemStack[] twice = empty();
            twice[0] = new ItemStack(Material.STRING, 40);
            PricingService.EffectiveTrade twenty = trade(20, 1);
            check(TradeEngine.tryApply(twice, twenty), "first repeated trade");
            check(TradeEngine.tryApply(twice, twenty), "second repeated trade");
            check(InventoryOps.isEmpty(twice[0]) && twice[27] != null && twice[27].getAmount() == 2,
                    "two emeralds produced");
            passed.add("40 String at 20 -> 2 Emeralds");

            // 4/5/12. Full or partially fitting OUTPUT never consumes INPUT.
            ItemStack[] full = empty();
            full[0] = new ItemStack(Material.STRING, 21);
            for (int i = 27; i < 54; i++) full[i] = new ItemStack(Material.DIAMOND, 64);
            ItemStack[] fullBefore = InventoryOps.deepCopy(full, 54);
            check(!TradeEngine.tryApply(full, trade(21, 1)), "full output blocks trade");
            check(equalContents(fullBefore, full), "full output transaction is atomic");

            ItemStack[] partial = empty();
            partial[0] = new ItemStack(Material.STRING, 21);
            for (int i = 27; i < 54; i++) partial[i] = new ItemStack(Material.DIAMOND, 64);
            partial[27] = new ItemStack(Material.EMERALD, 63);
            ItemStack[] partialBefore = InventoryOps.deepCopy(partial, 54);
            check(!TradeEngine.tryApply(partial,
                    new PricingService.EffectiveTrade(new ItemStack(Material.STRING, 21), null,
                            new ItemStack(Material.EMERALD, 2))), "partial result fit blocks trade");
            check(equalContents(partialBefore, partial), "partial result transaction is atomic");
            passed.add("full/partial OUTPUT atomicity");

            // 6/7. INPUT insertion is bounded to slots 0..26 and never overflows to OUTPUT.
            ItemStack[] bounded = empty();
            for (int i = 0; i < 27; i++) bounded[i] = new ItemStack(Material.COBBLESTONE, 64);
            bounded[27] = new ItemStack(Material.EMERALD, 3);
            check(!InventoryOps.insertFully(bounded, 0, 27, new ItemStack(Material.STRING, 1)),
                    "full input refuses insertion");
            check(bounded[27].getType() == Material.EMERALD && bounded[27].getAmount() == 3,
                    "output not used as input overflow");
            passed.add("INPUT boundary/no OUTPUT overflow");

            // 16. Two ingredients are all-or-nothing.
            ItemStack[] twoCosts = empty();
            twoCosts[0] = new ItemStack(Material.STRING, 20);
            twoCosts[1] = new ItemStack(Material.COAL, 5);
            PricingService.EffectiveTrade twoIngredient = new PricingService.EffectiveTrade(
                    new ItemStack(Material.STRING, 20), new ItemStack(Material.COAL, 5),
                    new ItemStack(Material.EMERALD, 1));
            check(TradeEngine.tryApply(twoCosts, twoIngredient), "two ingredient trade executes");
            check(InventoryOps.isEmpty(twoCosts[0]) && InventoryOps.isEmpty(twoCosts[1]), "both ingredients consumed");

            ItemStack[] missingSecond = empty();
            missingSecond[0] = new ItemStack(Material.STRING, 20);
            ItemStack[] missingBefore = InventoryOps.deepCopy(missingSecond, 54);
            check(!TradeEngine.tryApply(missingSecond, twoIngredient), "missing second ingredient refuses trade");
            check(equalContents(missingBefore, missingSecond), "missing second ingredient changes nothing");
            passed.add("two-ingredient atomicity");

            // Paper's adjusted-ingredient API itself must produce the expected discounted count.
            MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.EMERALD, 1), 12);
            recipe.addIngredient(new ItemStack(Material.STRING, 21));
            recipe.setSpecialPrice(-1);
            ItemStack adjusted = recipe.getAdjustedIngredient1();
            check(adjusted != null && adjusted.getAmount() == 20, "Paper MerchantRecipe adjusted ingredient is 20");
            passed.add("Paper adjusted ingredient pricing");

            // Identity cannot be reproduced by renaming a barrel.
            ItemStack custom = plugin.chests().createTradeChestItem(1);
            check(plugin.chests().isTradeChestItem(custom), "real item has Trade Chest marker");
            ItemStack renamed = new ItemStack(Material.BARREL);
            ItemMeta renamedMeta = renamed.getItemMeta();
            renamedMeta.displayName(Component.text("Trade Chest"));
            renamed.setItemMeta(renamedMeta);
            check(!plugin.chests().isTradeChestItem(renamed), "renamed ordinary barrel is not a Trade Chest");
            passed.add("PDC identity/ordinary barrel isolation");

            // Exact Bukkit ItemStack binary persistence keeps components/custom data.
            ItemStack exact = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta exactMeta = exact.getItemMeta();
            exactMeta.displayName(Component.text("Persistent Test"));
            exactMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "selftest_data"),
                    PersistentDataType.STRING, "exact");
            exact.setItemMeta(exactMeta);
            ItemStack[] persistent = empty();
            persistent[0] = exact;
            persistent[27] = custom;
            byte[] encoded = ItemStack.serializeItemsAsBytes(persistent);
            ItemStack[] decoded = ItemStack.deserializeItemsFromBytes(encoded);
            check(decoded.length == 54 && exact.equals(decoded[0]) && custom.equals(decoded[27]),
                    "binary ItemStack round trip preserves exact data");
            passed.add("54-slot exact ItemStack serialization");

            return new Result(true, List.copyOf(passed), null);
        } catch (Throwable failure) {
            return new Result(false, List.copyOf(passed), failure);
        }
    }

    private static PricingService.EffectiveTrade trade(int stringCost, int emeralds) {
        return new PricingService.EffectiveTrade(new ItemStack(Material.STRING, stringCost), null,
                new ItemStack(Material.EMERALD, emeralds));
    }

    private static ItemStack[] empty() {
        return new ItemStack[54];
    }

    private static boolean equalContents(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            ItemStack left = a[i], right = b[i];
            if (left == null ? right != null : !left.equals(right)) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record Result(boolean passed, List<String> checks, Throwable failure) {}
}
