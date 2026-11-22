package dev.easyeconomy.currency;

import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.config.EeConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.Optional;

// Physical currency: a vanilla base item plus components. The custom_data
// tag is the only identity check - display names can be forged with an
// anvil, components can't from a vanilla client.
public final class CurrencyItems {
	private static final String TAG_KEY = "easyeconomy";
	private static final String TAG_DENOM = "d";

	public static Item baseItem() {
		EeConfig config = ConfigManager.get();
		Identifier id = Identifier.tryParse(config.currencyBaseItem);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR) {
			return Items.PAPER;
		}
		return item;
	}

	public static ItemStack coin(long denomination, int count) {
		EeConfig config = ConfigManager.get();
		ItemStack stack = new ItemStack(baseItem(), count);
		CompoundTag inner = new CompoundTag();
		inner.putLong(TAG_DENOM, denomination);
		CompoundTag tag = new CompoundTag();
		tag.put(TAG_KEY, inner);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		String name = denomination == 1
				? config.currencySingular
				: denomination + " " + config.currencyPlural;
		stack.set(DataComponents.ITEM_NAME, Component.literal(name));
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("easyeconomy", "coin_" + denomination));
		return stack;
	}

	// Value of one item in the stack, or -1 when this is not a currency item.
	public static long denominationOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return -1;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return -1;
		}
		Optional<CompoundTag> inner = data.copyTag().getCompound(TAG_KEY);
		if (inner.isEmpty()) {
			return -1;
		}
		long d = inner.get().getLong(TAG_DENOM).orElse(-1L);
		return d > 0 ? d : -1;
	}

	public static List<Long> denominations() {
		return CurrencyMath.validate(ConfigManager.get().denominations);
	}
}
