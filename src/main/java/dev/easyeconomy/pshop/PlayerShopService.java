package dev.easyeconomy.pshop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.economy.EconomyCore;
import dev.easyeconomy.economy.EconomyService;
import dev.easyeconomy.currency.CurrencyItems;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlayerShopService {
	public enum ListResult { OK, NO_ITEM, IS_CURRENCY, BAD_PRICE, TOO_MANY }

	public enum BuyResult { OK, NOT_FOUND, OWN_LISTING, INSUFFICIENT_FUNDS, SELLER_OVER_MAX }

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final EconomyService economy;
	private final Path file;
	private final Map<UUID, Listing> listings = new LinkedHashMap<>();

	public PlayerShopService(EconomyService economy) {
		this.economy = economy;
		this.file = economy.dataDir().resolve("listings.json");
	}

	public synchronized List<Listing> all() {
		return new ArrayList<>(listings.values());
	}

	public synchronized List<Listing> of(UUID seller) {
		List<Listing> out = new ArrayList<>();
		for (Listing l : listings.values()) {
			if (l.seller.equals(seller)) {
				out.add(l);
			}
		}
		return out;
	}

	public synchronized ListResult list(ServerPlayer player, long price) {
		if (price < 1 || price > ConfigManager.get().maxBalance) {
			return ListResult.BAD_PRICE;
		}
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) {
			return ListResult.NO_ITEM;
		}
		if (CurrencyItems.denominationOf(held) > 0) {
			return ListResult.IS_CURRENCY;
		}
		if (of(player.getUUID()).size() >= ConfigManager.get().maxListingsPerPlayer) {
			return ListResult.TOO_MANY;
		}
		RegistryAccess access = player.level().registryAccess();
		Listing listing = new Listing(UUID.randomUUID(), player.getUUID(),
				player.getName().getString(), held.copy(), price,
				System.currentTimeMillis(), RegistryOps.create(NbtOps.INSTANCE, access));
		listings.put(listing.id, listing);
		save(access);
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		return ListResult.OK;
	}

	public synchronized BuyResult buy(ServerPlayer buyer, ServerLevel level, UUID listingId) {
		Listing listing = listings.get(listingId);
		if (listing == null) {
			return BuyResult.NOT_FOUND;
		}
		if (listing.seller.equals(buyer.getUUID())) {
			return BuyResult.OWN_LISTING;
		}
		long sellerBalance = economy.core().balance(listing.seller);
		if (listing.price > ConfigManager.get().maxBalance - sellerBalance) {
			return BuyResult.SELLER_OVER_MAX;
		}
		EconomyCore.Result pay = economy.core().withdraw(buyer.getUUID(), listing.price, "pshop-buy:" + listing.id);
		if (pay == EconomyCore.Result.INSUFFICIENT_FUNDS) {
			return BuyResult.INSUFFICIENT_FUNDS;
		}
		if (pay != EconomyCore.Result.OK) {
			return BuyResult.INSUFFICIENT_FUNDS;
		}
		EconomyCore.Result credit = economy.core().deposit(listing.seller, listing.price, "pshop-sell:" + listing.id);
		if (credit != EconomyCore.Result.OK) {
			// capacity was checked above; treat as impossible, but stay safe
			economy.core().deposit(buyer.getUUID(), listing.price, "pshop-refund:" + listing.id);
			return BuyResult.SELLER_OVER_MAX;
		}
		economy.changed();
		listings.remove(listingId);
		save(level.registryAccess());
		ItemStack stack = listing.stack(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()));
		buyer.getInventory().add(stack);
		if (!stack.isEmpty()) {
			buyer.spawnAtLocation(level, stack);
		}
		return BuyResult.OK;
	}

	public synchronized boolean cancel(ServerPlayer player, ServerLevel level, UUID listingId) {
		Listing listing = listings.get(listingId);
		if (listing == null || !listing.seller.equals(player.getUUID())) {
			return false;
		}
		listings.remove(listingId);
		save(level.registryAccess());
		ItemStack stack = listing.stack(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()));
		player.getInventory().add(stack);
		if (!stack.isEmpty()) {
			player.spawnAtLocation(level, stack);
		}
		return true;
	}

	public synchronized void load(RegistryAccess access) {
		listings.clear();
		try {
			if (!Files.exists(file)) {
				return;
			}
			JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
			int dropped = 0;
			for (JsonElement el : arr) {
				JsonObject o = el.getAsJsonObject();
				try {
					Listing l = Listing.restore(
							UUID.fromString(o.get("id").getAsString()),
							UUID.fromString(o.get("seller").getAsString()),
							o.get("sellerName").getAsString(),
							o.get("price").getAsLong(),
							o.get("created").getAsLong(),
							o.get("item").getAsString());
					// validate the item now so a bad entry never reaches a buyer
					l.stack(RegistryOps.create(NbtOps.INSTANCE, access));
					listings.put(l.id, l);
				} catch (Exception e) {
					dropped++;
				}
			}
			if (dropped > 0) {
				EasyEconomyMod.LOGGER.warn("dropped {} unreadable listings", dropped);
			}
			EasyEconomyMod.LOGGER.info("player shop loaded: {} listings", listings.size());
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load listings, starting empty", e);
		}
	}

	private synchronized void save(RegistryAccess access) {
		try {
			JsonArray arr = new JsonArray();
			for (Listing l : listings.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("id", l.id.toString());
				o.addProperty("seller", l.seller.toString());
				o.addProperty("sellerName", l.sellerName);
				o.addProperty("price", l.price);
				o.addProperty("created", l.created);
				o.addProperty("item", l.itemSnbt());
				arr.add(o);
			}
			ConfigManager.writeAtomic(file, GSON.toJson(arr));
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to save listings", e);
		}
	}
}
