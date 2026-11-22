package dev.easyeconomy.shop;

import dev.easyeconomy.economy.EconomyService;
import dev.easyeconomy.gui.MenuGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// /shop: category menu, then one page per category. Left click buys 1,
// shift-click buys 16. Buy limits show in the lore.
public final class ShopGui extends MenuGui {
	private final ShopService shop;
	private final EconomyService economy;
	private final ShopConfig.Category category;

	private ShopGui(ServerPlayer player, ShopService shop, EconomyService economy, ShopConfig.Category category) {
		super(player, rowsFor(category == null ? shop.config().categories.size() : category.items.size()));
		this.shop = shop;
		this.economy = economy;
		this.category = category;
		fill();
	}

	public static void openRoot(ServerPlayer player, ShopService shop, EconomyService economy) {
		new ShopGui(player, shop, economy, null).open();
	}

	private static int rowsFor(int entries) {
		return Math.min(6, 1 + (Math.max(1, entries) + 8) / 9);
	}

	@Override
	protected Component title() {
		return Component.literal(category == null ? "shop" : "shop - " + category.name);
	}

	private void fill() {
		if (category == null) {
			int slot = 0;
			for (ShopConfig.Category cat : shop.config().categories) {
				Item icon = itemOr(cat.icon, Items.CHEST);
				set(slot++, MenuGui.display(icon, cat.name, List.of(cat.items.size() + " items", "click to open")));
			}
		} else {
			for (int i = 0; i < category.items.size(); i++) {
				ShopConfig.Entry entry = category.items.get(i);
				set(i, entryButton(entry));
			}
			set(container.getContainerSize() - 1,
					MenuGui.display(Items.ARROW, "back", List.of("back to categories")));
		}
	}

	private net.minecraft.world.item.ItemStack entryButton(ShopConfig.Entry entry) {
		Item item = shop.itemOf(entry);
		List<String> lore = new ArrayList<>();
		if (entry.buyPrice > 0) {
			lore.add("buy: " + economy.format(entry.buyPrice) + " each");
			int remaining = shop.limitRemaining(player.getUUID(), entry);
			if (remaining >= 0) {
				lore.add("limit left: " + remaining);
			}
			lore.add("click to buy 1, shift-click for 16");
		} else {
			lore.add("not for sale");
		}
		if (entry.sellPrice > 0) {
			lore.add("sells to server for " + economy.format(entry.sellPrice));
		}
		return MenuGui.display(item, displayName(entry, item), lore);
	}

	private String displayName(ShopConfig.Entry entry, Item item) {
		Component name = item.getName(new net.minecraft.world.item.ItemStack(item));
		return name.getString();
	}

	private Item itemOr(String id, Item fallback) {
		Item item = shop.itemById(id);
		return item == null ? fallback : item;
	}

	@Override
	protected void onClick(int slot, int button, ContainerInput input) {
		if (category == null) {
			List<ShopConfig.Category> cats = shop.config().categories;
			if (slot >= 0 && slot < cats.size()) {
				new ShopGui(player, shop, economy, cats.get(slot)).open();
			}
			return;
		}
		if (slot == container.getContainerSize() - 1) {
			openRoot(player, shop, economy);
			return;
		}
		if (slot < 0 || slot >= category.items.size()) {
			return;
		}
		ShopConfig.Entry entry = category.items.get(slot);
		int count = input == net.minecraft.world.inventory.ContainerInput.QUICK_MOVE ? 16 : 1;
		ShopService.BuyResult result = shop.buy(player, player.level(), entry, count);
		switch (result) {
			case OK -> player.sendSystemMessage(Component.literal("bought " + count + " x " + displayName(entry, shop.itemOf(entry)) + " for " + economy.format(entry.buyPrice * count)));
			case NO_PRICE -> player.sendSystemMessage(Component.literal("not for sale"));
			case INSUFFICIENT_FUNDS -> player.sendSystemMessage(Component.literal("not enough " + dev.easyeconomy.config.ConfigManager.get().currencyPlural));
			case LIMIT_REACHED -> player.sendSystemMessage(Component.literal("buy limit reached for this item"));
			default -> player.sendSystemMessage(Component.literal("can't buy that"));
		}
		if (result == ShopService.BuyResult.OK) {
			fill(); // refresh limit counts
		}
	}
}
