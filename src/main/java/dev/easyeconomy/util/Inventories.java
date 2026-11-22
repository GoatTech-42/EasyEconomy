package dev.easyeconomy.util;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

// Capacity checks that mirror what Inventory.add can really do, verified
// against the 26.3 bytecode: merging reaches the selected slot, a matching
// offhand stack, and matching partial stacks in the 36 main slots; fresh
// stacks only ever start in the 36 main slots (getFreeSlot), never in
// equipment or the offhand. Anything that delivers items must check here
// first, because a delivery that overflows into a dropped entity cannot be
// recalled safely if the save afterwards cannot be verified.
public final class Inventories {
	private Inventories() {
	}

	// True when every stack, in order, fits where Inventory.add would put it.
	public static boolean canAbsorb(Inventory inv, List<ItemStack> stacks) {
		List<ItemStack> main = inv.getNonEquipmentItems();
		List<ItemStack> sim = new ArrayList<>(main.size() + 1);
		for (ItemStack slot : main) {
			sim.add(slot.copy());
		}
		// merge-only: add can top up a matching offhand stack but can never
		// start a fresh stack there
		ItemStack offhand = inv.getItem(Inventory.SLOT_OFFHAND).copy();
		for (ItemStack want : stacks) {
			ItemStack left = want.copy();
			int max = inv.getMaxStackSize(left);
			if (!left.isDamaged()) {
				for (ItemStack slot : sim) {
					if (left.isEmpty()) {
						break;
					}
					mergeInto(slot, left, max);
				}
				mergeInto(offhand, left, max);
			}
			if (!left.isEmpty()) {
				for (int i = 0; i < sim.size() && !left.isEmpty(); i++) {
					if (sim.get(i).isEmpty()) {
						int move = Math.min(max, left.getCount());
						sim.set(i, left.copyWithCount(move));
						left.shrink(move);
					}
				}
			}
			if (!left.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static void mergeInto(ItemStack slot, ItemStack left, int max) {
		if (slot.isEmpty() || left.isEmpty() || !ItemStack.isSameItemSameComponents(slot, left)) {
			return;
		}
		int move = Math.min(max - slot.getCount(), left.getCount());
		if (move > 0) {
			slot.grow(move);
			left.shrink(move);
		}
	}
}
