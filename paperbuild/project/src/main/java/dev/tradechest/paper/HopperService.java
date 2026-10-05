package dev.tradechest.paper;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.EnumSet;
import java.util.Set;

public final class HopperService {
    private static final Set<BlockFace> INSERT_FACES = EnumSet.of(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    private final TradeChestPlugin plugin;
    private final TradeChestService chestService;
    private boolean syntheticEvent;

    public HopperService(TradeChestPlugin plugin, TradeChestService chestService) {
        this.plugin = plugin;
        this.chestService = chestService;
    }
    public boolean isSyntheticEvent() { return syntheticEvent; }

    public void tick() {
        for (TradeChestRecord record : chestService.records()) {
            Block chestBlock = chestService.getLoadedBlock(record);
            if (chestBlock == null || !chestService.isMarkedTradeChest(chestBlock)) continue;
            for (BlockFace face : INSERT_FACES) tryInsertFromHopper(record, chestBlock, chestBlock.getRelative(face));
            tryExtractToHopper(record, chestBlock, chestBlock.getRelative(BlockFace.DOWN));
        }
    }

    private void tryInsertFromHopper(TradeChestRecord record, Block chestBlock, Block hopperBlock) {
        if (!isEnabledHopper(hopperBlock)) return;
        org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) hopperBlock.getBlockData();
        if (!hopperBlock.getRelative(data.getFacing()).equals(chestBlock)) return;
        if (!(hopperBlock.getState() instanceof org.bukkit.block.Hopper hopperState)) return;
        Inventory source = hopperState.getInventory();
        int sourceSlot = InventoryOps.firstNonEmpty(source);
        if (sourceSlot < 0) return;
        ItemStack sourceStack = source.getItem(sourceSlot);
        if (InventoryOps.isEmpty(sourceStack)) return;
        ItemStack one = sourceStack.clone();
        one.setAmount(1);
        ItemStack[] replacement = record.snapshot();
        if (!InventoryOps.insertFully(replacement, 0, TradeChestRecord.INPUT_END_EXCLUSIVE, one)) return;
        if (!callMoveEvent(source, one, physicalInventory(chestBlock), true)) return;
        if (!chestService.commit(record, replacement)) return;
        removeOne(source, sourceSlot);
    }

    private void tryExtractToHopper(TradeChestRecord record, Block chestBlock, Block hopperBlock) {
        if (!isEnabledHopper(hopperBlock)) return;
        if (!(hopperBlock.getState() instanceof org.bukkit.block.Hopper hopperState)) return;
        Inventory destination = hopperState.getInventory();
        ItemStack[] replacement = record.snapshot();
        int outputSlot = -1;
        ItemStack one = null;
        for (int i = TradeChestRecord.OUTPUT_START; i < TradeChestRecord.SIZE; i++) {
            ItemStack stack = replacement[i];
            if (!InventoryOps.isEmpty(stack)) {
                outputSlot = i;
                one = stack.clone();
                one.setAmount(1);
                break;
            }
        }
        if (outputSlot < 0 || one == null || !InventoryOps.canFitCompletely(destination, one)) return;
        if (!callMoveEvent(physicalInventory(chestBlock), one, destination, false)) return;
        ItemStack existing = replacement[outputSlot];
        if (existing.getAmount() <= 1) replacement[outputSlot] = null;
        else existing.setAmount(existing.getAmount() - 1);
        if (!chestService.commit(record, replacement)) return;
        if (!InventoryOps.addCompletely(destination, one)) {
            ItemStack[] rollback = record.snapshot();
            InventoryOps.insertFully(rollback, TradeChestRecord.OUTPUT_START, TradeChestRecord.SIZE, one);
            chestService.commit(record, rollback);
            plugin.getLogger().warning("Destination hopper changed during transfer; Trade Chest output was restored.");
        }
    }

    private Inventory physicalInventory(Block chestBlock) {
        if (chestBlock.getState() instanceof org.bukkit.block.Barrel barrel) return barrel.getInventory();
        throw new IllegalStateException("Marked Trade Chest is not a barrel");
    }

    private boolean callMoveEvent(Inventory source, ItemStack item, Inventory destination, boolean sourceInitiated) {
        InventoryMoveItemEvent event = new InventoryMoveItemEvent(source, item.clone(), destination, sourceInitiated);
        syntheticEvent = true;
        try { plugin.getServer().getPluginManager().callEvent(event); }
        finally { syntheticEvent = false; }
        return !event.isCancelled() && event.getItem().isSimilar(item) && event.getItem().getAmount() == item.getAmount();
    }

    private static void removeOne(Inventory inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        if (InventoryOps.isEmpty(stack)) return;
        if (stack.getAmount() <= 1) inventory.setItem(slot, null);
        else stack.setAmount(stack.getAmount() - 1);
    }

    private static boolean isEnabledHopper(Block block) {
        return block.getType() == Material.HOPPER
                && block.getBlockData() instanceof org.bukkit.block.data.type.Hopper hopper
                && hopper.isEnabled();
    }
}
