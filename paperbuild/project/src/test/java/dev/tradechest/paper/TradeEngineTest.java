package dev.tradechest.paper;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeEngineTest {
    @Test void twentyOneStringProducesOneEmerald(){ItemStack[] inv=empty();inv[0]=new ItemStack(Material.STRING,21);assertTrue(TradeEngine.tryApply(inv,trade(21,1)));assertNull(inv[0]);assertEquals(Material.EMERALD,inv[27].getType());assertEquals(1,inv[27].getAmount());}
    @Test void discountedTwentyWithTwentyFiveLeavesFive(){ItemStack[] inv=empty();inv[0]=new ItemStack(Material.STRING,25);assertTrue(TradeEngine.tryApply(inv,trade(20,1)));assertEquals(5,inv[0].getAmount());assertEquals(1,inv[27].getAmount());}
    @Test void fortyAtTwentyProducesTwo(){ItemStack[] inv=empty();inv[0]=new ItemStack(Material.STRING,40);var t=trade(20,1);assertTrue(TradeEngine.tryApply(inv,t));assertTrue(TradeEngine.tryApply(inv,t));assertNull(inv[0]);assertEquals(2,inv[27].getAmount());}
    @Test void fullOutputConsumesNothing(){ItemStack[] inv=empty();inv[0]=new ItemStack(Material.STRING,21);for(int i=27;i<54;i++)inv[i]=new ItemStack(Material.DIAMOND,64);ItemStack[] before=InventoryOps.deepCopy(inv,54);assertFalse(TradeEngine.tryApply(inv,trade(21,1)));assertInventoryEquals(before,inv);}
    @Test void partiallyFittingResultDoesNotTrade(){ItemStack[] inv=empty();inv[0]=new ItemStack(Material.STRING,21);for(int i=27;i<54;i++)inv[i]=new ItemStack(Material.DIAMOND,64);inv[27]=new ItemStack(Material.EMERALD,63);ItemStack[] before=InventoryOps.deepCopy(inv,54);assertFalse(TradeEngine.tryApply(inv,new PricingService.EffectiveTrade(new ItemStack(Material.STRING,21),null,new ItemStack(Material.EMERALD,2))));assertInventoryEquals(before,inv);}
    @Test void twoIngredientsAreAtomic(){ItemStack[] inv=empty();inv[0]=new ItemStack(Material.STRING,20);inv[1]=new ItemStack(Material.COAL,5);var t=new PricingService.EffectiveTrade(new ItemStack(Material.STRING,20),new ItemStack(Material.COAL,5),new ItemStack(Material.EMERALD,1));assertTrue(TradeEngine.tryApply(inv,t));assertNull(inv[0]);assertNull(inv[1]);assertEquals(1,inv[27].getAmount());}
    @Test void missingSecondIngredientChangesNothing(){ItemStack[] inv=empty();inv[0]=new ItemStack(Material.STRING,20);var t=new PricingService.EffectiveTrade(new ItemStack(Material.STRING,20),new ItemStack(Material.COAL,5),new ItemStack(Material.EMERALD,1));ItemStack[] before=InventoryOps.deepCopy(inv,54);assertFalse(TradeEngine.tryApply(inv,t));assertInventoryEquals(before,inv);}
    private static PricingService.EffectiveTrade trade(int string,int emerald){return new PricingService.EffectiveTrade(new ItemStack(Material.STRING,string),null,new ItemStack(Material.EMERALD,emerald));}
    private static ItemStack[] empty(){return new ItemStack[54];}
    private static void assertInventoryEquals(ItemStack[] expected,ItemStack[] actual){for(int i=0;i<expected.length;i++)assertEquals(expected[i],actual[i],"slot "+i);}
}
