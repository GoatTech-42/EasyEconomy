package dev.easyeconomy.mixin;

import dev.easyeconomy.currency.CurrencyItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Hoppers and droppers feed the crafter through canPlaceItem; currency
// items are refused there so automated crafting can never consume coins.
@Mixin(CrafterBlockEntity.class)
public abstract class CrafterBlockEntityMixin {
	@Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
	private void easyeconomy$noCurrencyInCrafter(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (CurrencyItems.isCurrency(stack)) {
			cir.setReturnValue(false);
		}
	}
}
