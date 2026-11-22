package dev.easyeconomy.mixin;

import dev.easyeconomy.currency.CurrencyItems;
import net.minecraft.world.inventory.CrafterSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Manual clicks in the crafter gui ask the slot, not the block entity's
// canPlaceItem, so the slot itself refuses currency: coins can never enter
// the grid by hand or by shift-move (both go through Slot.mayPlace), and
// CrafterBlockEntityMixin keeps refusing them from hoppers and droppers.
@Mixin(CrafterSlot.class)
public abstract class CrafterSlotMixin {
	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void easyeconomy$noCurrencyCrafterSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (CurrencyItems.isCurrency(stack)) {
			cir.setReturnValue(false);
		}
	}
}
