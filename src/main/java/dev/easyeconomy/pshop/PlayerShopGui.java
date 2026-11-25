package dev.easyeconomy.pshop;

import dev.easyeconomy.economy.EconomyService;
import dev.easyeconomy.gui.MenuGui;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// /playershop: browse everyone's listings, buy with a click. The "my
// listings" view lets a seller cancel and take the item back.
public final class PlayerShopGui extends MenuGui {
	private static final int CONTENT = 45;
	private static final int PREV = 45;
	private static final int NEXT = 53;
	private static final int TOGGLE = 49;

	private final PlayerShopService service;
	private final EconomyService economy;
	private final boolean mineOnly;
	private final int page;
	private List<Listing> shown = List.of();

	private PlayerShopGui(ServerPlayer player, PlayerShopService service, EconomyService economy, boolean mineOnly, int page) {
		super(player, 6);
		this.service = service;
		this.economy = economy;
		this.mineOnly = mineOnly;
		this.page = page;
		fill();
	}

	public static void open(ServerPlayer player, PlayerShopService service, EconomyService economy) {
		new PlayerShopGui(player, service, economy, false, 0).open();
	}

	@Override
	protected Component title() {
		String base = mineOnly ? "My Listings" : "Player Shop";
		return styledTitle(base + " \u00b7 " + economy.formatCompact(economy.core().balance(player.getUUID())));
	}

	private void fill() {
		shown = mineOnly ? service.of(player.getUUID()) : service.all();
		int from = page * CONTENT;
		for (int i = 0; i < CONTENT; i++) {
			int index = from + i;
			if (index < shown.size()) {
				set(i, button(shown.get(index)));
			} else {
				set(i, ItemStack.EMPTY);
			}
		}
		set(PREV, display(Items.ARROW, "nav_prev", "Previous Page", List.of()));
		set(NEXT, display(Items.ARROW, "nav_next", "Next Page", List.of()));
		set(TOGGLE, display(mineOnly ? Items.CHEST : Items.ENDER_CHEST,
				mineOnly ? "listings_all" : "listings_mine",
				mineOnly ? "Back to All Listings" : "My Listings",
				List.of(mineOnly ? "" : "Click to manage your listings.")));
	}

	private ItemStack button(Listing listing) {
		ItemStack stack = listing.stack(RegistryOps.create(NbtOps.INSTANCE, player.level().registryAccess()));
		List<Component> lore = new ArrayList<>();
		if (stack.has(DataComponents.LORE)) {
			lore.addAll(stack.get(DataComponents.LORE).lines());
		}
		lore.add(Component.literal("Seller: " + listing.sellerName + "."));
		lore.add(Component.literal("Price: " + economy.format(listing.price) + "."));
		lore.add(Component.literal(mineOnly ? "Click to cancel and take back." : "Click to buy."));
		stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
		return stack;
	}

	@Override
	protected void onClick(int slot, int buttonId, ContainerInput input) {
		if (slot == PREV) {
			if (page > 0) {
				new PlayerShopGui(player, service, economy, mineOnly, page - 1).open();
			}
			return;
		}
		if (slot == NEXT) {
			if ((page + 1) * CONTENT < shown.size()) {
				new PlayerShopGui(player, service, economy, mineOnly, page + 1).open();
			}
			return;
		}
		if (slot == TOGGLE) {
			new PlayerShopGui(player, service, economy, !mineOnly, 0).open();
			return;
		}
		int index = page * CONTENT + slot;
		if (slot < 0 || slot >= CONTENT || index >= shown.size()) {
			return;
		}
		Listing listing = shown.get(index);
		if (mineOnly) {
			if (service.cancel(player, listing.id)) {
				player.sendSystemMessage(Component.literal("Listing cancelled, item returned."));
			}
			new PlayerShopGui(player, service, economy, true, page).open();
			return;
		}
		PlayerShopService.BuyResult result = service.buy(player, listing.id);
		switch (result) {
			case OK -> {
				player.sendSystemMessage(Component.literal("Bought from " + listing.sellerName + " for " + economy.format(listing.price) + "."));
				new PlayerShopGui(player, service, economy, false, page).open();
			}
			case NOT_FOUND -> {
				player.sendSystemMessage(Component.literal("That listing is gone."));
				new PlayerShopGui(player, service, economy, false, page).open();
			}
			case OWN_LISTING -> player.sendSystemMessage(Component.literal("That's your listing - cancel it in My Listings."));
			case INSUFFICIENT_FUNDS -> player.sendSystemMessage(Component.literal("Not enough " + dev.easyeconomy.config.ConfigManager.get().currencyPlural + "."));
			case SELLER_OVER_MAX -> player.sendSystemMessage(Component.literal("Seller can't hold that much right now."));
			case NO_ROOM -> player.sendSystemMessage(Component.literal("Your inventory is full."));
			case FAILED -> player.sendSystemMessage(Component.literal("Sale failed safely - nothing was charged."));
		}
	}
}
