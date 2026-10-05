package dev.tradechest.paper;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class InventoryOps {
    private InventoryOps() {}

    public static ItemStack[] deepCopy(ItemStack[] source, int size) {
        ItemStack[] result = new ItemStack[size];
        if (source == null) return result;
        for (int i = 0; i < Math.min(size, source.length); i++) {
            result[i] = TradeChestRecord.cloneOrNull(source[i]);
        }
        return result;
    }

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    public static boolean same(ItemStack a, ItemStack b) {
        return !isEmpty(a) && !isEmpty(b) && a.isSimilar(b);
    }

    public static int insertUpTo(ItemStack[] contents, int start, int end, ItemStack incoming) {
        if (isEmpty(incoming)) return 0;
        int remaining = incoming.getAmount();
        for (int i = start; i < end && remaining > 0; i++) {
            ItemStack current = contents[i];
            if (same(current, incoming)) {
                int max = Math.min(current.getMaxStackSize(), incoming.getMaxStackSize());
                int free = Math.max(0, max - current.getAmount());
                int moved = Math.min(free, remaining);
                if (moved > 0) {
                    current.setAmount(current.getAmount() + moved);
                    remaining -= moved;
                }
            }
        }
        for (int i = start; i < end && remaining > 0; i++) {
            if (isEmpty(contents[i])) {
                int moved = Math.min(incoming.getMaxStackSize(), remaining);
                ItemStack placed = incoming.clone();
                placed.setAmount(moved);
                contents[i] = placed;
                remaining -= moved;
            }
        }
        return incoming.getAmount() - remaining;
    }

    public static boolean insertFully(ItemStack[] contents, int start, int end, ItemStack incoming) {
        if (isEmpty(incoming)) return true;
        ItemStack[] work = deepCopy(contents, contents.length);
        int remaining = incoming.getAmount();
        for (int i = start; i < end && remaining > 0; i++) {
            ItemStack current = work[i];
            if (same(current, incoming)) {
                int max = Math.min(current.getMaxStackSize(), incoming.getMaxStackSize());
                int free = Math.max(0, max - current.getAmount());
                if (free > 0) {
                    int moved = Math.min(free, remaining);
                    current.setAmount(current.getAmount() + moved);
                    remaining -= moved;
                }
            }
        }
        for (int i = start; i < end && remaining > 0; i++) {
            if (isEmpty(work[i])) {
                int moved = Math.min(incoming.getMaxStackSize(), remaining);
                ItemStack placed = incoming.clone();
                placed.setAmount(moved);
                work[i] = placed;
                remaining -= moved;
            }
        }
        if (remaining != 0) return false;
        System.arraycopy(work, 0, contents, 0, contents.length);
        return true;
    }

    public static boolean consumeFully(ItemStack[] contents, int start, int end, ItemStack cost) {
        if (isEmpty(cost) || cost.getAmount() <= 0) return true;
        int available = 0;
        for (int i = start; i < end; i++) {
            ItemStack current = contents[i];
            if (matchesCost(current, cost)) {
                available += current.getAmount();
                if (available >= cost.getAmount()) break;
            }
        }
        if (available < cost.getAmount()) return false;
        int remaining = cost.getAmount();
        for (int i = start; i < end && remaining > 0; i++) {
            ItemStack current = contents[i];
            if (!matchesCost(current, cost)) continue;
            int take = Math.min(remaining, current.getAmount());
            int left = current.getAmount() - take;
            remaining -= take;
            if (left <= 0) contents[i] = null;
            else current.setAmount(left);
        }
        return true;
    }

    public static boolean matchesCost(ItemStack offered, ItemStack required) {
        return !isEmpty(offered) && !isEmpty(required) && offered.isSimilar(required);
    }

    public static int firstNonEmpty(Inventory inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (!isEmpty(inventory.getItem(i))) return i;
        }
        return -1;
    }

    public static boolean canFitCompletely(Inventory inventory, ItemStack incoming) {
        if (isEmpty(incoming)) return true;
        int room = 0;
        for (ItemStack current : inventory.getStorageContents()) {
            if (isEmpty(current)) room += incoming.getMaxStackSize();
            else if (same(current, incoming)) {
                room += Math.max(0, Math.min(current.getMaxStackSize(), incoming.getMaxStackSize()) - current.getAmount());
            }
            if (room >= incoming.getAmount()) return true;
        }
        return false;
    }

    public static boolean addCompletely(Inventory inventory, ItemStack incoming) {
        if (!canFitCompletely(inventory, incoming)) return false;
        return inventory.addItem(incoming.clone()).isEmpty();
    }
}
