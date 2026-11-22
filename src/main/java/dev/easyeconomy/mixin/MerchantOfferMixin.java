package dev.easyeconomy.mixin;

import dev.easyeconomy.currency.CurrencyItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Villager and wandering trader offers match payment by item type, so a
// coin passes as paper. satisfiedBy gates every trade (manual slots and
// auto-move alike); take is guarded too so no path can consume a coin.
@Mixin(MerchantOffer.class)
public abstract class MerchantOfferMixin {
	@Inject(method = "satisfiedBy", at = @At("HEAD"), cancellable = true)
	private void easyeconomy$coinsAreNotPayment(ItemStack first, ItemStack second, CallbackInfoReturnable<Boolean> cir) {
		if (CurrencyItems.isCurrency(first) || CurrencyItems.isCurrency(second)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "take", at = @At("HEAD"), cancellable = true)
	private void easyeconomy$neverTakeCoins(ItemStack first, ItemStack second, CallbackInfoReturnable<Boolean> cir) {
		if (CurrencyItems.isCurrency(first) || CurrencyItems.isCurrency(second)) {
			cir.setReturnValue(false);
		}
	}
}
