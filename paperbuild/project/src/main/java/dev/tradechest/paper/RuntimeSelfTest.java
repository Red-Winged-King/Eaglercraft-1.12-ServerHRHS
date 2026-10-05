package dev.tradechest.paper;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Diagnostics executed inside a real Paper server. The CI workflow runs both
 * the immediate integration suite and a real stop/start persistence cycle.
 */
public final class RuntimeSelfTest {
    private static final UUID RESTART_OWNER = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f001");
    private RuntimeSelfTest() {}

    public static Result run(TradeChestPlugin plugin) {
        List<String> passed = new ArrayList<>();
        TestContext ctx = null;
        try {
            runPureRuntimeChecks(plugin, passed);
            ctx = new TestContext(plugin);
            runLiveIntegrationChecks(plugin, ctx, passed);
            return new Result(true, List.copyOf(passed), null);
        } catch (Throwable failure) {
            return new Result(false, List.copyOf(passed), failure);
        } finally {
            if (ctx != null) ctx.cleanup();
        }
    }

    public static Result prepareRestart(TradeChestPlugin plugin) {
        List<String> passed = new ArrayList<>();
        try {
            World world = requireWorld();
            Block block = restartBlock(world);
            cleanupExistingAt(plugin, block);
            clearBlockAndDrops(block);

            block.setType(Material.BARREL, false);
            TradeChestRecord record = plugin.chests().place(block, RESTART_OWNER);

            ItemStack input = named(new ItemStack(Material.STRING, 37), "restart-input");
            ItemStack output = named(new ItemStack(Material.EMERALD, 4), "restart-output");
            ItemStack componentRich = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = componentRich.getItemMeta();
            meta.displayName(Component.text("Restart Persistent Sword"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "restart_component"),
                    PersistentDataType.STRING, "preserve-me");
            componentRich.setItemMeta(meta);

            ItemStack[] contents = record.snapshot();
            contents[0] = input;
            contents[4] = componentRich;
            contents[27] = output;
            check(plugin.chests().commit(record, contents), "restart fixture persisted");
            check(plugin.chests().isMarkedTradeChest(block), "restart block marked");
            check(block.getState() instanceof Barrel barrel
                    && barrel.getInventory().getItem(0) != null
                    && barrel.getInventory().getItem(0).isSimilar(output),
                    "physical mirror contains OUTPUT only");

            passed.add("restart fixture written to SQLite/world PDC");
            passed.add("restart fixture physical OUTPUT mirror");
            return new Result(true, List.copyOf(passed), null);
        } catch (Throwable failure) {
            return new Result(false, List.copyOf(passed), failure);
        }
    }

    public static Result verifyRestart(TradeChestPlugin plugin) {
        List<String> passed = new ArrayList<>();
        try {
            World world = requireWorld();
            Block block = restartBlock(world);
            check(plugin.chests().isMarkedTradeChest(block), "marked block survived restart");

            TradeChestRecord record = plugin.chests().get(block);
            check(record != null, "SQLite record loaded after restart");
            check(RESTART_OWNER.equals(record.ownerId()), "owner UUID survived restart");
            check(record.inventory().getSize() == 54, "54-slot inventory rebuilt");

            ItemStack input = record.inventory().getItem(0);
            ItemStack componentRich = record.inventory().getItem(4);
            ItemStack output = record.inventory().getItem(27);
            check(input != null && input.getType() == Material.STRING && input.getAmount() == 37
                    && "restart-input".equals(plainName(input)), "INPUT survived restart exactly");
            check(output != null && output.getType() == Material.EMERALD && output.getAmount() == 4
                    && "restart-output".equals(plainName(output)), "OUTPUT survived restart exactly");
            check(componentRich != null && componentRich.getType() == Material.DIAMOND_SWORD,
                    "component-rich item survived restart");
            String custom = componentRich.getItemMeta().getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "restart_component"), PersistentDataType.STRING);
            check("preserve-me".equals(custom), "custom item PDC survived restart");

            check(block.getState() instanceof Barrel barrel, "restart block is barrel");
            Inventory physical = ((Barrel) block.getState()).getInventory();
            check(physical.getItem(0) != null && physical.getItem(0).isSimilar(output),
                    "OUTPUT mirror restored after restart");
            check(physical.getContents().length == 27, "physical barrel remains vanilla-sized");

            passed.add("real server restart SQLite/PDC persistence");
            passed.add("owner and exact ItemStack data persisted");
            passed.add("OUTPUT mirror restored after restart");

            // Clean fixture after verification so CI and admins do not accumulate test data.
            clearNearbyItems(block.getLocation());
            check(plugin.chests().breakAndDrop(record.key(), true), "restart fixture removable");
            clearNearbyItems(block.getLocation());
            check(plugin.chests().get(record.key()) == null && block.getType() == Material.AIR,
                    "restart fixture removed cleanly");
            passed.add("restart fixture cleanup");

            return new Result(true, List.copyOf(passed), null);
        } catch (Throwable failure) {
            return new Result(false, List.copyOf(passed), failure);
        }
    }

    private static void runPureRuntimeChecks(TradeChestPlugin plugin, List<String> passed) throws Exception {
        ItemStack[] oneTrade = empty();
        oneTrade[0] = new ItemStack(Material.STRING, 21);
        check(TradeEngine.tryApply(oneTrade, trade(21, 1)), "21 string trade executes");
        check(InventoryOps.isEmpty(oneTrade[0]), "21 string consumed");
        check(oneTrade[27] != null && oneTrade[27].getType() == Material.EMERALD && oneTrade[27].getAmount() == 1,
                "one emerald produced");
        passed.add("21 String -> 1 Emerald");

        ItemStack[] discounted = empty();
        discounted[0] = new ItemStack(Material.STRING, 25);
        check(TradeEngine.tryApply(discounted, trade(20, 1)), "discounted trade executes");
        check(discounted[0] != null && discounted[0].getAmount() == 5, "discounted trade leaves five string");
        passed.add("discounted 20/25 leaves 5");

        ItemStack[] twice = empty();
        twice[0] = new ItemStack(Material.STRING, 40);
        PricingService.EffectiveTrade twenty = trade(20, 1);
        check(TradeEngine.tryApply(twice, twenty), "first repeated trade");
        check(TradeEngine.tryApply(twice, twenty), "second repeated trade");
        check(InventoryOps.isEmpty(twice[0]) && twice[27] != null && twice[27].getAmount() == 2,
                "two emeralds produced");
        passed.add("40 String at 20 -> 2 Emeralds");

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

        ItemStack[] bounded = empty();
        for (int i = 0; i < 27; i++) bounded[i] = new ItemStack(Material.COBBLESTONE, 64);
        bounded[27] = new ItemStack(Material.EMERALD, 3);
        check(!InventoryOps.insertFully(bounded, 0, 27, new ItemStack(Material.STRING, 1)),
                "full input refuses insertion");
        check(bounded[27].getType() == Material.EMERALD && bounded[27].getAmount() == 3,
                "output not used as input overflow");
        passed.add("INPUT boundary/no OUTPUT overflow");

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

        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.EMERALD, 1), 12);
        recipe.addIngredient(new ItemStack(Material.STRING, 21));
        recipe.setSpecialPrice(-1);
        ItemStack adjusted = recipe.getAdjustedIngredient1();
        check(adjusted != null && adjusted.getAmount() == 20, "Paper MerchantRecipe adjusted ingredient is 20");
        passed.add("Paper adjusted ingredient pricing");

        ItemStack custom = plugin.chests().createTradeChestItem(1);
        check(plugin.chests().isTradeChestItem(custom), "real item has Trade Chest marker");
        ItemStack renamed = new ItemStack(Material.BARREL);
        ItemMeta renamedMeta = renamed.getItemMeta();
        renamedMeta.displayName(Component.text("Trade Chest"));
        renamed.setItemMeta(renamedMeta);
        check(!plugin.chests().isTradeChestItem(renamed), "renamed ordinary barrel is not a Trade Chest");
        passed.add("PDC identity/ordinary barrel isolation");

        NamespacedKey recipeKey = new NamespacedKey(plugin, "trade_chest");
        check(Bukkit.getRecipe(recipeKey) instanceof ShapelessRecipe shapeless
                        && plugin.chests().isTradeChestItem(shapeless.getResult()),
                "registered crafting recipe yields marked item");
        passed.add("registered Chest + String recipe");

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

        File temp = new File(plugin.getDataFolder(), "selftest-sqlite.db");
        deleteSqliteFiles(temp);
        SqliteStorage storage = new SqliteStorage(temp, plugin.getLogger());
        UUID world = UUID.randomUUID();
        ChestKey key = new ChestKey(world, 1, 2, 3);
        TradeChestRecord saved = new TradeChestRecord(key, UUID.randomUUID(), UUID.randomUUID(), persistent);
        storage.open();
        storage.save(saved);
        storage.close();
        SqliteStorage reopened = new SqliteStorage(temp, plugin.getLogger());
        reopened.open();
        List<TradeChestRecord> loaded = reopened.loadAll();
        reopened.close();
        check(loaded.size() == 1 && exact.equals(loaded.getFirst().inventory().getItem(0))
                        && custom.equals(loaded.getFirst().inventory().getItem(27)),
                "SQLite close/reopen preserves exact 54-slot inventory");
        deleteSqliteFiles(temp);
        passed.add("SQLite close/reopen persistence");
    }

    private static void runLiveIntegrationChecks(TradeChestPlugin plugin, TestContext ctx, List<String> passed) throws Exception {
        World world = ctx.world;
        Block chestBlock = ctx.chestBlock;
        chestBlock.setType(Material.BARREL, false);
        TradeChestRecord record = plugin.chests().place(chestBlock, ctx.owner);
        ctx.record = record;

        check(plugin.chests().isMarkedTradeChest(chestBlock), "placed block has PDC identity");
        check(record.inventory().getSize() == 54, "live inventory has 54 slots");
        check(chestBlock.getState() instanceof Barrel && ((Barrel) chestBlock.getState()).getInventory().getSize() == 27,
                "physical representation remains vanilla barrel");
        passed.add("real block placement/PDC/54-slot virtual inventory");

        Villager villager = world.spawn(chestBlock.getLocation().add(2.5, 0.0, 0.5), Villager.class);
        ctx.entities.add(villager);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setProfession(Villager.Profession.FISHERMAN);

        MerchantRecipe stringTrade = new MerchantRecipe(new ItemStack(Material.EMERALD, 1),
                1, 1, true, 2, 0.05f);
        stringTrade.addIngredient(new ItemStack(Material.STRING, 21));
        villager.setRecipes(List.of(stringTrade));

        Reputation rep = new Reputation();
        rep.setReputation(ReputationType.TRADING, 20);
        villager.setReputation(ctx.owner, rep);

        ItemStack[] contents = record.snapshot();
        contents[0] = new ItemStack(Material.STRING, 25);
        check(plugin.chests().commit(record, contents), "live input saved");

        TradeEngine engine = new TradeEngine(plugin, plugin.chests());
        int completed = engine.process(record);
        check(completed == 1, "one discounted live villager trade completed");
        check(record.inventory().getItem(0) != null && record.inventory().getItem(0).getAmount() == 5,
                "live owner reputation discount consumed 20 of 25 string");
        check(record.inventory().getItem(27) != null
                        && record.inventory().getItem(27).getType() == Material.EMERALD
                        && record.inventory().getItem(27).getAmount() == 1,
                "live trade result entered OUTPUT");
        check(villager.getRecipes().getFirst().getUses() == 1,
                "automatic infinite-stock trading did not mutate already-exhausted villager stock");
        passed.add("real villager scan/trade/offline-owner discount/infinite stock");

        // Full output against a real placed record must leave input unchanged.
        contents = empty();
        contents[0] = new ItemStack(Material.STRING, 20);
        for (int i = 27; i < 54; i++) contents[i] = new ItemStack(Material.DIAMOND, 64);
        check(plugin.chests().commit(record, contents), "full-output fixture saved");
        int blocked = engine.process(record);
        check(blocked == 0 && record.inventory().getItem(0).getAmount() == 20,
                "real full OUTPUT consumes nothing");
        passed.add("real full-OUTPUT rollback");

        // Out-of-range villagers must not trade.
        villager.remove();
        Villager far = world.spawn(chestBlock.getLocation().add(plugin.settings().tradeRadius() + 3.5, 0, 0.5), Villager.class);
        ctx.entities.add(far);
        far.setAI(false);
        far.setProfession(Villager.Profession.FISHERMAN);
        MerchantRecipe farTrade = new MerchantRecipe(new ItemStack(Material.EMERALD, 1), 12);
        farTrade.addIngredient(new ItemStack(Material.STRING, 20));
        far.setRecipes(List.of(farTrade));
        contents = empty();
        contents[0] = new ItemStack(Material.STRING, 20);
        check(plugin.chests().commit(record, contents), "range fixture saved");
        check(new TradeEngine(plugin, plugin.chests()).process(record) == 0
                        && record.inventory().getItem(0).getAmount() == 20,
                "out-of-range villager ignored");
        passed.add("trade-radius enforcement");
        far.remove();

        // Real hopper -> INPUT.
        Block sideHopperBlock = chestBlock.getRelative(BlockFace.WEST);
        ctx.blocks.add(sideHopperBlock);
        sideHopperBlock.setType(Material.HOPPER, false);
        org.bukkit.block.data.type.Hopper sideData =
                (org.bukkit.block.data.type.Hopper) sideHopperBlock.getBlockData();
        sideData.setFacing(BlockFace.EAST);
        sideData.setEnabled(true);
        sideHopperBlock.setBlockData(sideData, false);
        org.bukkit.block.Hopper sideHopper = (org.bukkit.block.Hopper) sideHopperBlock.getState();
        sideHopper.getInventory().setItem(0, new ItemStack(Material.STRING, 3));

        record.setContents(empty());
        check(plugin.chests().saveAndMirror(record), "hopper fixture reset");
        HopperService hoppers = new HopperService(plugin, plugin.chests());
        hoppers.tick();
        check(record.inventory().getItem(0) != null && record.inventory().getItem(0).getAmount() == 1,
                "side hopper inserted into INPUT");
        check(sideHopper.getInventory().getItem(0) != null && sideHopper.getInventory().getItem(0).getAmount() == 2,
                "source hopper decremented exactly once");
        check(record.inventory().getItem(27) == null, "hopper insertion did not touch OUTPUT");

        // Locked hopper must not move.
        sideData = (org.bukkit.block.data.type.Hopper) sideHopperBlock.getBlockData();
        sideData.setEnabled(false);
        sideHopperBlock.setBlockData(sideData, false);
        hoppers.tick();
        check(record.inventory().getItem(0).getAmount() == 1
                        && sideHopper.getInventory().getItem(0).getAmount() == 2,
                "disabled/redstone-locked hopper does not move");
        passed.add("real hopper INPUT + lock behavior");

        // Real OUTPUT -> below hopper only.
        sideHopper.getInventory().clear();
        sideData = (org.bukkit.block.data.type.Hopper) sideHopperBlock.getBlockData();
        sideData.setEnabled(true);
        sideHopperBlock.setBlockData(sideData, false);

        Block belowHopperBlock = chestBlock.getRelative(BlockFace.DOWN);
        ctx.blocks.add(belowHopperBlock);
        belowHopperBlock.setType(Material.HOPPER, false);
        org.bukkit.block.data.type.Hopper belowData =
                (org.bukkit.block.data.type.Hopper) belowHopperBlock.getBlockData();
        belowData.setEnabled(true);
        belowHopperBlock.setBlockData(belowData, false);
        org.bukkit.block.Hopper belowHopper = (org.bukkit.block.Hopper) belowHopperBlock.getState();

        contents = empty();
        contents[0] = new ItemStack(Material.STRING, 7);
        contents[27] = new ItemStack(Material.EMERALD, 2);
        check(plugin.chests().commit(record, contents), "output-hopper fixture saved");
        hoppers.tick();
        check(record.inventory().getItem(0).getAmount() == 7, "below hopper never extracts INPUT");
        check(record.inventory().getItem(27) != null && record.inventory().getItem(27).getAmount() == 1,
                "below hopper extracted exactly one OUTPUT");
        check(belowHopper.getInventory().containsAtLeast(new ItemStack(Material.EMERALD, 1), 1),
                "below hopper received OUTPUT");
        passed.add("real hopper OUTPUT-only extraction");

        // Unlimited normal-player trading preparation/restoration on a real villager.
        Villager stockVillager = world.spawn(chestBlock.getLocation().add(3.5, 0, 0.5), Villager.class);
        ctx.entities.add(stockVillager);
        stockVillager.setAI(false);
        stockVillager.setProfession(Villager.Profession.FISHERMAN);
        MerchantRecipe soldOut = new MerchantRecipe(new ItemStack(Material.EMERALD, 1), 1, 1, true);
        soldOut.addIngredient(new ItemStack(Material.STRING, 21));
        stockVillager.setRecipes(List.of(soldOut));
        UnlimitedTradeService unlimited = new UnlimitedTradeService(plugin);
        unlimited.prepareForPlayer(stockVillager);
        MerchantRecipe prepared = stockVillager.getRecipes().getFirst();
        check(prepared.getUses() == 0 && prepared.getMaxUses() >= 1_000_000,
                "sold-out recipe exposed as unlimited to player");
        unlimited.prepareForPlayer(stockVillager); // must not overwrite recovery state
        unlimited.restore(stockVillager);
        MerchantRecipe restored = stockVillager.getRecipes().getFirst();
        check(restored.getUses() == 0 && restored.getMaxUses() == 1,
                "original max uses restored while cooldown remains removed");
        passed.add("real villager player-stock prepare/restore");

        // Safe break: one custom chest + all 54 virtual contents, without mirror duplication.
        clearNearbyItems(chestBlock.getLocation());
        belowHopper.getInventory().clear();
        sideHopper.getInventory().clear();
        contents = empty();
        contents[0] = new ItemStack(Material.STRING, 6);
        contents[27] = new ItemStack(Material.EMERALD, 3);
        check(plugin.chests().commit(record, contents), "break fixture saved");
        check(plugin.chests().breakAndDrop(record.key(), true), "safe break completed");
        ctx.record = null;
        check(chestBlock.getType() == Material.AIR && plugin.chests().get(record.key()) == null,
                "safe break removed block and SQLite record");

        int customChestDrops = 0, stringDrops = 0, emeraldDrops = 0;
        for (Entity entity : world.getNearbyEntities(chestBlock.getLocation().add(0.5, 0.5, 0.5), 3, 3, 3)) {
            if (!(entity instanceof Item item)) continue;
            ItemStack stack = item.getItemStack();
            if (plugin.chests().isTradeChestItem(stack)) customChestDrops += stack.getAmount();
            else if (stack.getType() == Material.STRING) stringDrops += stack.getAmount();
            else if (stack.getType() == Material.EMERALD) emeraldDrops += stack.getAmount();
        }
        check(customChestDrops == 1 && stringDrops == 6 && emeraldDrops == 3,
                "safe break drops each virtual item exactly once");
        passed.add("real break/drop recovery without mirror duplication");
    }

    private static PricingService.EffectiveTrade trade(int stringCost, int emeralds) {
        return new PricingService.EffectiveTrade(new ItemStack(Material.STRING, stringCost), null,
                new ItemStack(Material.EMERALD, emeralds));
    }

    private static ItemStack[] empty() {
        return new ItemStack[54];
    }

    private static ItemStack named(ItemStack stack, String name) {
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name));
        stack.setItemMeta(meta);
        return stack;
    }

    private static String plainName(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || stack.getItemMeta().displayName() == null) return "";
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(stack.getItemMeta().displayName());
    }

    private static World requireWorld() {
        World world = Bukkit.getWorlds().stream().findFirst().orElseThrow();
        world.getChunkAt(restartBlock(world));
        return world;
    }

    private static Block restartBlock(World world) {
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() + 48;
        int z = spawn.getBlockZ() + 48;
        int y = Math.max(world.getMinHeight() + 8,
                Math.min(world.getMaxHeight() - 8, world.getHighestBlockYAt(x, z) + 3));
        return world.getBlockAt(x, y, z);
    }

    private static void cleanupExistingAt(TradeChestPlugin plugin, Block block) {
        TradeChestRecord existing = plugin.chests().get(block);
        if (existing != null) {
            clearNearbyItems(block.getLocation());
            plugin.chests().breakAndDrop(existing.key(), true);
            clearNearbyItems(block.getLocation());
        } else if (plugin.chests().isMarkedTradeChest(block)) {
            TradeChestRecord recovered = plugin.chests().getOrRecoverMarked(block);
            if (recovered != null) {
                plugin.chests().breakAndDrop(recovered.key(), true);
                clearNearbyItems(block.getLocation());
            }
        }
    }

    private static void clearBlockAndDrops(Block block) {
        clearNearbyItems(block.getLocation());
        block.setType(Material.AIR, false);
    }

    private static void clearNearbyItems(Location location) {
        World world = location.getWorld();
        if (world == null) return;
        for (Entity entity : world.getNearbyEntities(location.clone().add(0.5, 0.5, 0.5), 4, 4, 4)) {
            if (entity instanceof Item) entity.remove();
        }
    }

    private static void deleteSqliteFiles(File file) throws Exception {
        Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(new File(file.getPath() + "-wal").toPath());
        Files.deleteIfExists(new File(file.getPath() + "-shm").toPath());
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

    private static final class TestContext {
        private final TradeChestPlugin plugin;
        private final World world;
        private final Block chestBlock;
        private final UUID owner = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        private final List<Entity> entities = new ArrayList<>();
        private final List<Block> blocks = new ArrayList<>();
        private TradeChestRecord record;

        private TestContext(TradeChestPlugin plugin) {
            this.plugin = plugin;
            this.world = requireWorld();
            Location spawn = world.getSpawnLocation();
            int x = spawn.getBlockX() + 32;
            int z = spawn.getBlockZ() + 32;
            int y = Math.max(world.getMinHeight() + 8,
                    Math.min(world.getMaxHeight() - 8, world.getHighestBlockYAt(x, z) + 4));
            this.chestBlock = world.getBlockAt(x, y, z);
            world.getChunkAt(chestBlock);
            clearNearbyItems(chestBlock.getLocation());
            cleanupExistingAt(plugin, chestBlock);
            for (BlockFace face : List.of(BlockFace.WEST, BlockFace.DOWN)) {
                Block b = chestBlock.getRelative(face);
                b.setType(Material.AIR, false);
                blocks.add(b);
            }
            chestBlock.setType(Material.AIR, false);
        }

        private void cleanup() {
            for (Entity entity : entities) if (entity != null && entity.isValid()) entity.remove();
            if (record != null && plugin.chests().get(record.key()) != null) {
                clearNearbyItems(chestBlock.getLocation());
                plugin.chests().breakAndDrop(record.key(), true);
            }
            clearNearbyItems(chestBlock.getLocation());
            for (Block block : blocks) block.setType(Material.AIR, false);
            if (plugin.chests().get(chestBlock) == null) chestBlock.setType(Material.AIR, false);
        }
    }
}
