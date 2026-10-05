package dev.tradechest.paper;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class TradeChestHolder implements InventoryHolder {
    private final ChestKey key;
    private Inventory inventory;

    public TradeChestHolder(ChestKey key) {
        this.key = key;
    }

    public ChestKey key() {
        return key;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
