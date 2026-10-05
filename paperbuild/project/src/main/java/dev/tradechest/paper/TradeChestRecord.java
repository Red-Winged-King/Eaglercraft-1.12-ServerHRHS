package dev.tradechest.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class TradeChestRecord {
    public static final int SIZE = 54;
    public static final int INPUT_END_EXCLUSIVE = 27;
    public static final int OUTPUT_START = 27;

    private final ChestKey key;
    private final UUID chestId;
    private UUID ownerId;
    private final TradeChestHolder holder;
    private final Inventory inventory;
    private long lastMutationTick;

    public TradeChestRecord(ChestKey key, UUID chestId, UUID ownerId, ItemStack[] contents) {
        this.key = key;
        this.chestId = chestId;
        this.ownerId = ownerId;
        this.holder = new TradeChestHolder(key);
        this.inventory = Bukkit.createInventory(holder, SIZE,
                Component.text("Trade Chest | INPUT top 3 | OUTPUT bottom 3"));
        holder.attach(inventory);
        setContents(contents);
    }

    public ChestKey key() { return key; }
    public UUID chestId() { return chestId; }
    public UUID ownerId() { return ownerId; }
    public void ownerId(UUID ownerId) { this.ownerId = ownerId; }
    public Inventory inventory() { return inventory; }

    public ItemStack[] snapshot() {
        ItemStack[] src = inventory.getContents();
        ItemStack[] copy = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) copy[i] = cloneOrNull(src[i]);
        return copy;
    }

    public void setContents(ItemStack[] contents) {
        ItemStack[] normalized = new ItemStack[SIZE];
        if (contents != null) {
            for (int i = 0; i < Math.min(SIZE, contents.length); i++) normalized[i] = cloneOrNull(contents[i]);
        }
        inventory.setContents(normalized);
    }

    public void touch(long tick) { this.lastMutationTick = tick; }
    public long lastMutationTick() { return lastMutationTick; }

    public boolean hasInput() {
        for (int i = 0; i < INPUT_END_EXCLUSIVE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.isEmpty()) return true;
        }
        return false;
    }

    public static ItemStack cloneOrNull(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.clone();
    }

    @Override
    public String toString() {
        return "TradeChestRecord{" + key.shortString() + ", chestId=" + chestId + ", owner=" + ownerId + '}';
    }
}
