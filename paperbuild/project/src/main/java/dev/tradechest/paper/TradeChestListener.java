package dev.tradechest.paper;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Chunk;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;

public final class TradeChestListener implements Listener {
    private final TradeChestPlugin plugin;
    private final TradeChestService chestService;
    private final HopperService hopperService;
    private final UnlimitedTradeService unlimitedTrades;
    public TradeChestListener(TradeChestPlugin plugin,TradeChestService chestService,HopperService hopperService,UnlimitedTradeService unlimitedTrades){this.plugin=plugin;this.chestService=chestService;this.hopperService=hopperService;this.unlimitedTrades=unlimitedTrades;}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent event){if(!chestService.isTradeChestItem(event.getItemInHand()))return;try{chestService.place(event.getBlockPlaced(),event.getPlayer().getUniqueId());plugin.requestImmediateProcessing(ChestKey.of(event.getBlockPlaced()));}catch(SQLException|RuntimeException ex){event.setCancelled(true);plugin.getLogger().log(Level.SEVERE,"Could not place Trade Chest",ex);}}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onInteract(PlayerInteractEvent event){
        if(event.getClickedBlock()==null)return;
        Block block=event.getClickedBlock();
        if(!chestService.isMarkedTradeChest(block))return;
        // Cancel both hands so the physical barrel mirror can never be opened.
        event.setCancelled(true);
        if(event.getHand()!=EquipmentSlot.HAND)return;
        TradeChestRecord record=chestService.getOrRecoverMarked(block);
        if(record==null)return;
        event.getPlayer().openInventory(record.inventory());
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event){Block block=event.getBlock();if(!chestService.isMarkedTradeChest(block))return;event.setCancelled(true);event.setDropItems(false);TradeChestRecord record=chestService.getOrRecoverMarked(block);if(record!=null&&!chestService.breakAndDrop(record.key(),true))event.getPlayer().sendMessage("Trade Chest could not be safely removed; check the server log.");}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onBlockExplosion(BlockExplodeEvent event){handleExplosionBlocks(event.blockList());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onEntityExplosion(EntityExplodeEvent event){handleExplosionBlocks(event.blockList());}
    private void handleExplosionBlocks(List<Block> blocks){for(Block block:new ArrayList<>(blocks)){if(!chestService.isMarkedTradeChest(block))continue;blocks.remove(block);TradeChestRecord record=chestService.getOrRecoverMarked(block);if(record!=null)chestService.breakAndDrop(record.key(),true);}}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onPistonExtend(BlockPistonExtendEvent event){if(event.getBlocks().stream().anyMatch(chestService::isMarkedTradeChest))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onPistonRetract(BlockPistonRetractEvent event){if(event.getBlocks().stream().anyMatch(chestService::isMarkedTradeChest))event.setCancelled(true);}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onInventoryMove(InventoryMoveItemEvent event){if(hopperService.isSyntheticEvent())return;if(isPhysicalTradeChestInventory(event.getSource())||isPhysicalTradeChestInventory(event.getDestination()))event.setCancelled(true);}
    private boolean isPhysicalTradeChestInventory(Inventory inventory){return inventory.getHolder() instanceof Barrel barrel&&chestService.isMarkedTradeChest(barrel.getBlock());}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onInventoryClick(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof TradeChestHolder holder))return;
        TradeChestRecord record=chestService.get(holder.key());if(record==null){event.setCancelled(true);return;}
        InventoryAction action=event.getAction();
        if(action==InventoryAction.COLLECT_TO_CURSOR||action==InventoryAction.CLONE_STACK||action==InventoryAction.UNKNOWN){event.setCancelled(true);return;}
        int raw=event.getRawSlot(),topSize=event.getView().getTopInventory().getSize();
        if(raw>=0&&raw<topSize){
            if(raw>=TradeChestRecord.OUTPUT_START){
                boolean removalOnly=action==InventoryAction.PICKUP_ALL||action==InventoryAction.PICKUP_HALF||action==InventoryAction.PICKUP_ONE||action==InventoryAction.PICKUP_SOME||action==InventoryAction.MOVE_TO_OTHER_INVENTORY;
                if(!removalOnly){event.setCancelled(true);return;}
            }
            plugin.scheduleRecordPersist(record);return;
        }
        if(event.isShiftClick()&&raw>=topSize&&event.getCurrentItem()!=null&&!event.getCurrentItem().isEmpty()){
            event.setCancelled(true);ItemStack moving=event.getCurrentItem().clone();ItemStack[] replacement=record.snapshot();int moved=InventoryOps.insertUpTo(replacement,0,TradeChestRecord.INPUT_END_EXCLUSIVE,moving);if(moved<=0)return;if(!chestService.commit(record,replacement))return;ItemStack remaining=event.getCurrentItem().clone();remaining.setAmount(remaining.getAmount()-moved);event.setCurrentItem(remaining.getAmount()<=0?null:remaining);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onInventoryDrag(InventoryDragEvent event){if(!(event.getView().getTopInventory().getHolder() instanceof TradeChestHolder holder))return;for(int raw:event.getRawSlots())if(raw>=TradeChestRecord.OUTPUT_START&&raw<TradeChestRecord.SIZE){event.setCancelled(true);return;}TradeChestRecord record=chestService.get(holder.key());if(record!=null&&event.getRawSlots().stream().anyMatch(i->i>=0&&i<TradeChestRecord.INPUT_END_EXCLUSIVE))plugin.scheduleRecordPersist(record);}

    @EventHandler(priority=EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event){if(event.getInventory().getHolder() instanceof TradeChestHolder holder){TradeChestRecord record=chestService.get(holder.key());if(record!=null)chestService.saveAndMirror(record);}if(event.getInventory() instanceof org.bukkit.inventory.MerchantInventory merchantInventory&&merchantInventory.getMerchant() instanceof AbstractVillager villager)plugin.getServer().getScheduler().runTask(plugin,()->unlimitedTrades.restore(villager));}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onVillagerInteract(PlayerInteractEntityEvent event){if(event.getHand()!=EquipmentSlot.HAND)return;if(event.getRightClicked() instanceof AbstractVillager villager&&unlimitedTrades.applies(villager))unlimitedTrades.prepareForPlayer(villager);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onPlayerTrade(PlayerTradeEvent event){unlimitedTrades.handlePlayerTrade(event);}
    @EventHandler(priority=EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event){Chunk chunk=event.getChunk();chestService.reconcileChunk(chunk);for(var entity:chunk.getEntities())if(entity instanceof AbstractVillager villager)unlimitedTrades.restoreStale(villager);}
}
