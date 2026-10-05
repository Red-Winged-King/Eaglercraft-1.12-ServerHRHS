package dev.tradechest.paper;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

public record ChestKey(UUID worldId, int x, int y, int z) {
    public static ChestKey of(Block block) {
        return new ChestKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public Location center(World world) {
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public String shortString() {
        return worldId + ":" + x + "," + y + "," + z;
    }
}
