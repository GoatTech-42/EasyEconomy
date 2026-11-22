package dev.easyeconomy.mixin;

import dev.easyeconomy.currency.CurrencyItems;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The cartography table consumes paper for map work; a coin in either
// input slot must produce no result. The result container is written
// directly: going through Slot.set would notify the menu and re-enter
// slotsChanged forever.
@Mixin(CartographyTableMenu.class)
public abstract class CartographyTableMixin {
	@Shadow
	@Final
	public Container container;

	@Shadow
	@Final
	private ResultContainer resultContainer;

	@Inject(method = "slotsChanged", at = @At("TAIL"))
	private void easyeconomy$noCurrencyMapWork(Container changed, CallbackInfo ci) {
		if (CurrencyItems.isCurrency(container.getItem(CartographyTableMenu.MAP_SLOT))
				|| CurrencyItems.isCurrency(container.getItem(CartographyTableMenu.ADDITIONAL_SLOT))) {
			resultContainer.setItem(0, ItemStack.EMPTY);
		}
	}
}
