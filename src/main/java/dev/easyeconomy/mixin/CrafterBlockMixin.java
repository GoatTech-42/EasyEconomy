package dev.easyeconomy.mixin;

import dev.easyeconomy.currency.CurrencyItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The redstone craft re-matches the recipe and consumes the grid inside the
// block, independent of any open gui: clearing the menu's preview never
// stopped it. A grid holding a currency item must never fire, no matter how
// the item got there (old worlds, commands, other mods), so the craft
// itself is refused here as the final backstop behind the slot and
// canPlaceItem guards.
@Mixin(CrafterBlock.class)
public abstract class CrafterBlockMixin {
	@Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
	private void easyeconomy$noCurrencyCrafterCraft(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo ci) {
		if (level.getBlockEntity(pos) instanceof CrafterBlockEntity crafter) {
			for (int i = 0; i < crafter.getContainerSize(); i++) {
				if (CurrencyItems.isCurrency(crafter.getItem(i))) {
					ci.cancel();
					return;
				}
			}
		}
	}
}
