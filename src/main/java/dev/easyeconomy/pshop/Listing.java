package dev.easyeconomy.pshop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

// One player-shop listing. The listed item leaves the seller's inventory
// and lives only here until it is bought or cancelled, so it can never be
// sold twice.
public final class Listing {
	public final UUID id;
	public final UUID seller;
	public final String sellerName;
	public final long price;
	public final long created;
	private final String itemSnbt;

	public Listing(UUID id, UUID seller, String sellerName, ItemStack stack, long price, long created, RegistryOps<net.minecraft.nbt.Tag> ops) {
		this.id = id;
		this.seller = seller;
		this.sellerName = sellerName;
		this.price = price;
		this.created = created;
		this.itemSnbt = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow(IllegalStateException::new).toString();
	}

	private Listing(UUID id, UUID seller, String sellerName, long price, long created, String itemSnbt) {
		this.id = id;
		this.seller = seller;
		this.sellerName = sellerName;
		this.price = price;
		this.created = created;
		this.itemSnbt = itemSnbt;
	}

	public ItemStack stack(RegistryOps<net.minecraft.nbt.Tag> ops) {
		try {
			CompoundTag tag = TagParser.parseCompoundFully(itemSnbt);
			return ItemStack.CODEC.parse(ops, tag).getOrThrow(IllegalStateException::new);
		} catch (Exception e) {
			throw new IllegalStateException("bad listing item", e);
		}
	}

	public String itemSnbt() {
		return itemSnbt;
	}

	public static Listing restore(UUID id, UUID seller, String sellerName, long price, long created, String itemSnbt) {
		return new Listing(id, seller, sellerName, price, created, itemSnbt);
	}
}
