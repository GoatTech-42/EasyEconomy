package dev.easyeconomy.mixin;

import dev.easyeconomy.currency.CurrencyItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Coins are built on a vanilla item (paper by default), so recipe
// ingredients match them. No craft may ever consume one: whenever a grid
// holds a currency item the result slot is cleared. This static is the
// single recompute path for both the 2x2 inventory grid and the 3x3
// crafting table.
@Mixin(CraftingMenu.class)
public abstract class CraftingGridMixin {
	@Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
	private static void easyeconomy$noCurrencyCrafting(AbstractContainerMenu menu, ServerLevel level, Player player,
			CraftingContainer grid, ResultContainer result, RecipeHolder<CraftingRecipe> recipe, CallbackInfo ci) {
		for (int i = 0; i < grid.getContainerSize(); i++) {
			if (CurrencyItems.isCurrency(grid.getItem(i))) {
				result.setItem(0, ItemStack.EMPTY);
				return;
			}
		}
	}
}
