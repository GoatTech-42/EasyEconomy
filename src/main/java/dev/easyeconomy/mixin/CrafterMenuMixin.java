package dev.easyeconomy.mixin;

import dev.easyeconomy.currency.CurrencyItems;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The result preview is cleared whenever the grid holds a currency item,
// same rule as the player crafting grids. Placement itself is refused in
// CrafterSlotMixin and CrafterBlockEntityMixin, and the redstone craft is
// refused in CrafterBlockMixin; this preview clearing stays as the display
// backstop for grids that already hold a coin (old worlds, commands).
@Mixin(CrafterMenu.class)
public abstract class CrafterMenuMixin {
	@Shadow
	@Final
	private CraftingContainer container;

	@Shadow
	@Final
	private ResultContainer resultContainer;

	@Inject(method = "refreshRecipeResult", at = @At("TAIL"))
	private void easyeconomy$noCurrencyCrafterResult(CallbackInfo ci) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (CurrencyItems.isCurrency(container.getItem(i))) {
				resultContainer.setItem(0, ItemStack.EMPTY);
				return;
			}
		}
	}
}
