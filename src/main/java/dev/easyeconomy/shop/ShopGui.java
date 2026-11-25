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
		// +1 in the item view reserves the back slot, so a full page of
		// items never hides it and every displayed slot is clickable. the
		// root view also reserves one slot, for the balance readout
		super(player, rowsFor(category == null ? shop.config().categories.size() + 1 : category.items.size() + 1));
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
		// the balance sits in the title next to the pack's coin motif, so it
		// is always visible and not only in a hover tooltip. fitTitle keeps
		// the worst case inside the bar; the motif carries the unit.
		return styledTitle(fitTitle(category == null ? "Shop" : category.name,
				"\u00b7 " + dev.easyeconomy.economy.EconomyCore.compact(balanceForTitle())));
	}

	@Override
	protected boolean hasBalanceTitle() {
		return true;
	}

	@Override
	protected long balanceForTitle() {
		return economy.core().balance(player.getUUID());
	}

	@Override
	protected void reopenSelf() {
		new ShopGui(player, shop, economy, category).open();
	}

	private void fill() {
		if (category == null) {
			int slot = 0;
			for (ShopConfig.Category cat : shop.config().categories) {
				// the last slot is reserved for the balance readout; categories
				// past capacity are simply not shown (same as before)
				if (slot >= container.getContainerSize() - 1) {
					break;
				}
				Item icon = itemOr(cat.icon, Items.CHEST);
				set(slot++, MenuGui.display(icon, cat.name, List.of(cat.items.size() + " items.", "Click to open.")));
			}
			set(container.getContainerSize() - 1,
					display(net.minecraft.world.item.Items.GOLD_NUGGET, "balance", "Balance",
							List.of("You have " + economy.format(economy.core().balance(player.getUUID())) + ".")));
		} else {
			int shown = Math.min(category.items.size(), container.getContainerSize() - 1);
			for (int i = 0; i < shown; i++) {
				ShopConfig.Entry entry = category.items.get(i);
				set(i, entryButton(entry));
			}
			set(container.getContainerSize() - 1,
					display(Items.ARROW, "back", "Back", List.of("Back to categories.")));
		}
	}

	private net.minecraft.world.item.ItemStack entryButton(ShopConfig.Entry entry) {
		Item item = shop.itemOf(entry);
		List<String> lore = new ArrayList<>();
		if (entry.buyPrice > 0) {
			lore.add("Buy: " + economy.format(entry.buyPrice) + " each.");
			int remaining = shop.limitRemaining(player.getUUID(), entry);
			if (remaining >= 0) {
				lore.add("Limit left: " + remaining + ".");
			}
			lore.add("Click to buy 1, shift-click for 16.");
		} else {
			lore.add("Not for sale.");
		}
		if (entry.sellPrice > 0) {
			lore.add("Sells to server for " + economy.format(entry.sellPrice) + ".");
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
			// clicks on the player's own inventory rows carry slot ids past
			// the gui container; never let those resolve to a category. the
			// last slot is the balance readout, which never opens a category
			List<ShopConfig.Category> cats = shop.config().categories;
			if (slot >= 0 && slot < cats.size() && slot < container.getContainerSize() - 1) {
				new ShopGui(player, shop, economy, cats.get(slot)).open();
			}
			return;
		}
		if (slot == container.getContainerSize() - 1) {
			openRoot(player, shop, economy);
			return;
		}
		if (slot < 0 || slot >= category.items.size() || slot >= container.getContainerSize() - 1) {
			return;
		}
		ShopConfig.Entry entry = category.items.get(slot);
		int count = input == net.minecraft.world.inventory.ContainerInput.QUICK_MOVE ? 16 : 1;
		ShopService.BuyResult result = shop.buy(player, player.level(), entry, count);
		switch (result) {
			case OK -> player.sendSystemMessage(Component.literal("Bought " + count + " x " + displayName(entry, shop.itemOf(entry)) + " for " + economy.format(entry.buyPrice * count)));
			case OK_PARTIAL -> player.sendSystemMessage(Component.literal("Bought " + count + " x " + displayName(entry, shop.itemOf(entry)) + ", but some of it could not be delivered; an admin has been notified."));
			case OK_SAVE_UNVERIFIED -> player.sendSystemMessage(Component.literal("Bought " + count + " x " + displayName(entry, shop.itemOf(entry)) + ", but your inventory could not be saved safely; a crash may lose it - tell an admin if it vanishes."));
			case OK_PARTIAL_SAVE_UNVERIFIED -> player.sendSystemMessage(Component.literal("Bought " + count + " x " + displayName(entry, shop.itemOf(entry)) + ", but some of it was lost in delivery and your inventory could not be saved safely; a crash may lose the rest - an admin has been notified."));
			case NO_PRICE -> player.sendSystemMessage(Component.literal("Not for sale."));
			case INSUFFICIENT_FUNDS -> player.sendSystemMessage(Component.literal("Not enough " + dev.easyeconomy.config.ConfigManager.get().currencyPlural + "."));
			case LIMIT_REACHED -> player.sendSystemMessage(Component.literal("Buy limit reached for this item."));
			case SAVE_FAILED -> player.sendSystemMessage(Component.literal("Could not save your balance; nothing bought."));
			case NO_ROOM -> player.sendSystemMessage(Component.literal("Your inventory is full."));
			default -> player.sendSystemMessage(Component.literal("Can't buy that."));
		}
		if (result == ShopService.BuyResult.OK || result == ShopService.BuyResult.OK_PARTIAL
				|| result == ShopService.BuyResult.OK_SAVE_UNVERIFIED
				|| result == ShopService.BuyResult.OK_PARTIAL_SAVE_UNVERIFIED) {
			// reopen so both the limit counts and the title balance refresh
			reopenSelf();
		}
	}
}
