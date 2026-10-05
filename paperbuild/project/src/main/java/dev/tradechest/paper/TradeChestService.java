package dev.tradechest.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;

public final class TradeChestService {
    private final TradeChestPlugin plugin;
    private final SqliteStorage storage;
    private final NamespacedKey itemMarker,itemVersion,blockMarker,blockChestId,blockOwner;
    private final Map<ChestKey,TradeChestRecord> records=new HashMap<>();
    public TradeChestService(TradeChestPlugin plugin,SqliteStorage storage){
        this.plugin=plugin;this.storage=storage;
        itemMarker=new NamespacedKey(plugin,"trade_chest_item");itemVersion=new NamespacedKey(plugin,"trade_chest_item_version");
        blockMarker=new NamespacedKey(plugin,"trade_chest_block");blockChestId=new NamespacedKey(plugin,"trade_chest_id");blockOwner=new NamespacedKey(plugin,"trade_chest_owner");
    }
    public void load()throws SQLException{
        records.clear();for(TradeChestRecord record:storage.loadAll())records.put(record.key(),record);
        for(TradeChestRecord record:new ArrayList<>(records.values())){
            World world=Bukkit.getWorld(record.key().worldId());if(world==null||!world.isChunkLoaded(record.key().chunkX(),record.key().chunkZ()))continue;
            Block block=world.getBlockAt(record.key().x(),record.key().y(),record.key().z());if(isMarkedTradeChest(block))syncOutputToBlock(record);
        }
    }
    public ItemStack createTradeChestItem(int amount){
        int safe=Math.max(1,Math.min(64,amount));ItemStack stack=new ItemStack(Material.BARREL,safe);ItemMeta meta=stack.getItemMeta();
        meta.displayName(Component.text("Trade Chest"));meta.lore(List.of(Component.text("Top 3 rows: INPUT"),Component.text("Bottom 3 rows: OUTPUT"),Component.text("Automatically trades with nearby villagers")));
        PersistentDataContainer pdc=meta.getPersistentDataContainer();pdc.set(itemMarker,PersistentDataType.BYTE,(byte)1);pdc.set(itemVersion,PersistentDataType.INTEGER,1);stack.setItemMeta(meta);return stack;
    }
    public boolean isTradeChestItem(ItemStack stack){if(stack==null||stack.getType()!=Material.BARREL||!stack.hasItemMeta())return false;Byte m=stack.getItemMeta().getPersistentDataContainer().get(itemMarker,PersistentDataType.BYTE);return m!=null&&m==(byte)1;}
    public boolean isMarkedTradeChest(Block block){if(block==null||block.getType()!=Material.BARREL||!(block.getState() instanceof Barrel barrel))return false;Byte m=barrel.getPersistentDataContainer().get(blockMarker,PersistentDataType.BYTE);return m!=null&&m==(byte)1;}
    public TradeChestRecord place(Block block,UUID ownerId)throws SQLException{
        if(block.getType()!=Material.BARREL)throw new IllegalArgumentException("Trade Chest must be placed as a barrel");
        ChestKey key=ChestKey.of(block);TradeChestRecord old=records.get(key);if(old!=null){plugin.getLogger().warning("Replacing stale Trade Chest database record at "+key.shortString()+"; recovering old contents.");recoverRecord(old,false);}
        UUID chestId=UUID.randomUUID();markBlock(block,chestId,ownerId);TradeChestRecord record=new TradeChestRecord(key,chestId,ownerId,new ItemStack[TradeChestRecord.SIZE]);storage.save(record);records.put(key,record);syncOutputToBlock(record);return record;
    }
    public TradeChestRecord get(Block block){return block==null?null:records.get(ChestKey.of(block));}
    public TradeChestRecord get(ChestKey key){return records.get(key);}
    public TradeChestRecord getOrRecoverMarked(Block block){
        if(!isMarkedTradeChest(block))return null;ChestKey key=ChestKey.of(block);TradeChestRecord record=records.get(key);if(record!=null)return record;if(!(block.getState() instanceof Barrel barrel))return null;
        UUID chestId=readUuid(barrel.getPersistentDataContainer(),blockChestId,UUID.randomUUID());UUID owner=readUuid(barrel.getPersistentDataContainer(),blockOwner,null);ItemStack[] contents=new ItemStack[TradeChestRecord.SIZE];ItemStack[] physical=barrel.getInventory().getContents();
        for(int i=0;i<Math.min(27,physical.length);i++)contents[TradeChestRecord.OUTPUT_START+i]=TradeChestRecord.cloneOrNull(physical[i]);
        record=new TradeChestRecord(key,chestId,owner,contents);
        try{storage.save(record);records.put(key,record);plugin.getLogger().warning("Recovered marked Trade Chest missing from SQLite at "+key.shortString()+". Physical output was preserved.");return record;}
        catch(SQLException ex){plugin.getLogger().log(Level.SEVERE,"Could not reconstruct Trade Chest at "+key.shortString(),ex);return null;}
    }
    public Collection<TradeChestRecord> records(){return Collections.unmodifiableCollection(records.values());}
    public int totalCount(){return records.size();}
    public int loadedCount(){int loaded=0;for(TradeChestRecord r:records.values()){World w=Bukkit.getWorld(r.key().worldId());if(w!=null&&w.isChunkLoaded(r.key().chunkX(),r.key().chunkZ()))loaded++;}return loaded;}
    public boolean isLoaded(TradeChestRecord record){World w=Bukkit.getWorld(record.key().worldId());return w!=null&&w.isChunkLoaded(record.key().chunkX(),record.key().chunkZ());}
    public Block getLoadedBlock(TradeChestRecord record){World w=Bukkit.getWorld(record.key().worldId());if(w==null||!w.isChunkLoaded(record.key().chunkX(),record.key().chunkZ()))return null;return w.getBlockAt(record.key().x(),record.key().y(),record.key().z());}
    public boolean saveAndMirror(TradeChestRecord record){try{storage.save(record);syncOutputToBlock(record);record.touch(plugin.currentTick());return true;}catch(SQLException ex){plugin.getLogger().log(Level.SEVERE,"Could not save Trade Chest "+record.key().shortString(),ex);return false;}}
    public boolean commit(TradeChestRecord record,ItemStack[] replacement){ItemStack[] before=record.snapshot();record.setContents(replacement);if(saveAndMirror(record)){plugin.requestImmediateProcessing(record.key());return true;}record.setContents(before);syncOutputToBlock(record);return false;}
    public void saveAll()throws SQLException{storage.saveAll(records.values());for(TradeChestRecord r:records.values())syncOutputToBlock(r);}
    public void syncOutputToBlock(TradeChestRecord record){
        Block block=getLoadedBlock(record);if(block==null||!isMarkedTradeChest(block)||!(block.getState() instanceof Barrel barrel))return;Inventory physical=barrel.getInventory();ItemStack[] output=new ItemStack[27];
        for(int i=0;i<27;i++)output[i]=TradeChestRecord.cloneOrNull(record.inventory().getItem(TradeChestRecord.OUTPUT_START+i));physical.setContents(output);
    }
    public void reconcileLoadedRecords(){for(TradeChestRecord r:new ArrayList<>(records.values())){Block block=getLoadedBlock(r);if(block==null)continue;if(!isMarkedTradeChest(block)){plugin.getLogger().warning("Trade Chest block disappeared at "+r.key().shortString()+"; recovering stored inventory as drops.");recoverRecord(r,false);continue;}UUID marked=readBlockChestId(block);if(marked!=null&&!marked.equals(r.chestId())){plugin.getLogger().warning("Trade Chest identity changed at "+r.key().shortString()+"; recovering old inventory.");recoverRecord(r,false);continue;}syncOutputToBlock(r);}}
    public void reconcileChunk(org.bukkit.Chunk chunk){
        UUID worldId=chunk.getWorld().getUID();int cx=chunk.getX(),cz=chunk.getZ();
        for(TradeChestRecord r:new ArrayList<>(records.values())){if(!r.key().worldId().equals(worldId)||r.key().chunkX()!=cx||r.key().chunkZ()!=cz)continue;Block block=chunk.getWorld().getBlockAt(r.key().x(),r.key().y(),r.key().z());
            if(!isMarkedTradeChest(block)){plugin.getLogger().warning("Trade Chest block missing after chunk load at "+r.key().shortString()+"; recovering stored inventory.");recoverRecord(r,false);continue;}
            UUID marked=readBlockChestId(block);if(marked!=null&&!marked.equals(r.chestId())){plugin.getLogger().warning("Trade Chest identity mismatch after chunk load at "+r.key().shortString()+"; recovering stored inventory.");recoverRecord(r,false);continue;}syncOutputToBlock(r);}
    }
    public boolean breakAndDrop(ChestKey key,boolean removeMarkedBlock){TradeChestRecord record=records.get(key);return record!=null&&recoverRecord(record,removeMarkedBlock);}
    private boolean recoverRecord(TradeChestRecord record,boolean removeMarkedBlock){
        World world=Bukkit.getWorld(record.key().worldId());if(world==null){plugin.getLogger().warning("Cannot recover Trade Chest yet because world is not loaded: "+record.key().shortString());return false;}
        try{storage.delete(record.key());}catch(SQLException ex){plugin.getLogger().log(Level.SEVERE,"Refusing to drop/remove Trade Chest because SQLite delete failed: "+record.key().shortString(),ex);return false;}
        records.remove(record.key());Location dropAt=record.key().center(world);if(removeMarkedBlock){Block block=world.getBlockAt(record.key().x(),record.key().y(),record.key().z());if(isMarkedTradeChest(block))block.setType(Material.AIR,false);}
        world.dropItemNaturally(dropAt,createTradeChestItem(1));for(ItemStack stack:record.snapshot())if(stack!=null&&!stack.isEmpty())world.dropItemNaturally(dropAt,stack.clone());return true;
    }
    public UUID readBlockChestId(Block block){if(!(block.getState() instanceof Barrel barrel))return null;return readUuid(barrel.getPersistentDataContainer(),blockChestId,null);}
    private void markBlock(Block block,UUID chestId,UUID ownerId){if(!(block.getState() instanceof Barrel barrel))throw new IllegalStateException("Expected barrel block state");PersistentDataContainer pdc=barrel.getPersistentDataContainer();pdc.set(blockMarker,PersistentDataType.BYTE,(byte)1);pdc.set(blockChestId,PersistentDataType.STRING,chestId.toString());if(ownerId!=null)pdc.set(blockOwner,PersistentDataType.STRING,ownerId.toString());barrel.customName(Component.text("Trade Chest"));barrel.update(true,false);}
    private static UUID readUuid(PersistentDataContainer pdc,NamespacedKey key,UUID fallback){String raw=pdc.get(key,PersistentDataType.STRING);if(raw==null)return fallback;try{return UUID.fromString(raw);}catch(IllegalArgumentException ignored){return fallback;}}
}
