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
import dev.easyeconomy.pshop.tx.TxException;
import dev.easyeconomy.pshop.tx.TxOps;
import dev.easyeconomy.pshop.tx.TxStore;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Player shop escrow. Every list/buy/cancel runs as a journaled
// transaction (TxOps): a write failure aborts and unwinds, a crash
// mid-operation is recovered on the next start, and recovery never
// creates value (half-deliveries are dropped and reported, never
// repeated or completed).
public final class PlayerShopService implements TxStore {
	public enum ListResult { OK, NO_ITEM, IS_CURRENCY, BAD_PRICE, TOO_MANY, FAILED }

	public enum BuyResult { OK, NOT_FOUND, OWN_LISTING, INSUFFICIENT_FUNDS, SELLER_OVER_MAX, NO_ROOM, FAILED }

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final EconomyService economy;
	private final Path file;
	private final Path journalFile;
	private final EscrowStore store = new EscrowStore(this::writeListings);
	private MinecraftServer server;

	public PlayerShopService(EconomyService economy) {
		this.economy = economy;
		this.file = economy.dataDir().resolve("listings.json");
		this.journalFile = economy.dataDir().resolve("pshop-journal.txt");
	}

	public void setServer(MinecraftServer server) {
		this.server = server;
	}

	public synchronized List<Listing> all() {
		List<Listing> out = new ArrayList<>();
		for (String json : store.all()) {
			out.add(fromJson(JsonParser.parseString(json).getAsJsonObject()));
		}
		return out;
	}

	public synchronized List<Listing> of(UUID seller) {
		List<Listing> out = new ArrayList<>();
		for (Listing l : all()) {
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
		Listing listing = new Listing(UUID.randomUUID(), player.getUUID(),
				player.getName().getString(), held.copy(), price,
				System.currentTimeMillis(), ops());
		TxOps.Outcome outcome = TxOps.list(this, listing.id, listing.seller, listing.price, toJson(listing).toString());
		return switch (outcome) {
			case OK -> ListResult.OK;
			default -> ListResult.FAILED;
		};
	}

	public synchronized BuyResult buy(ServerPlayer buyer, UUID listingId) {
		String json = store.get(listingId);
		if (json == null) {
			return BuyResult.NOT_FOUND;
		}
		Listing listing = fromJson(JsonParser.parseString(json).getAsJsonObject());
		if (listing.seller.equals(buyer.getUUID())) {
			return BuyResult.OWN_LISTING;
		}
		TxOps.Outcome outcome = TxOps.buy(this, listing.id, listing.seller, buyer.getUUID(),
				listing.price, toJson(listing).toString());
		return switch (outcome) {
			case OK -> BuyResult.OK;
			case INSUFFICIENT -> BuyResult.INSUFFICIENT_FUNDS;
			case SELLER_FULL -> BuyResult.SELLER_OVER_MAX;
			case NO_ROOM -> BuyResult.NO_ROOM;
			default -> BuyResult.FAILED;
		};
	}

	public synchronized boolean cancel(ServerPlayer player, UUID listingId) {
		String json = store.get(listingId);
		if (json == null) {
			return false;
		}
		Listing listing = fromJson(JsonParser.parseString(json).getAsJsonObject());
		if (!listing.seller.equals(player.getUUID())) {
			return false;
		}
		return TxOps.cancel(this, listing.id, listing.seller, listing.price, toJson(listing).toString()) == TxOps.Outcome.OK;
	}

	public synchronized void load(RegistryAccess access) {
		List<String> valid = new ArrayList<>();
		try {
			if (Files.exists(file)) {
				JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
				int dropped = 0;
				for (JsonElement el : arr) {
					try {
						Listing l = fromJson(el.getAsJsonObject());
						// validate the item now so a bad entry never reaches a buyer
						l.stack(RegistryOps.create(NbtOps.INSTANCE, access));
						valid.add(el.getAsJsonObject().toString());
					} catch (Exception e) {
						dropped++;
					}
				}
				if (dropped > 0) {
					EasyEconomyMod.LOGGER.warn("dropped {} unreadable listings", dropped);
				}
			}
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load listings, starting empty", e);
		}
		store.loadAll(valid, json -> UUID.fromString(
				JsonParser.parseString(json).getAsJsonObject().get("id").getAsString()));
		EasyEconomyMod.LOGGER.info("player shop loaded: {} listings", store.size());
		// finish or unwind whatever was in flight when the server last stopped
		TxOps.recover(this);
	}

	// ---------- listing json ----------

	static JsonObject toJson(Listing l) {
		JsonObject o = new JsonObject();
		o.addProperty("id", l.id.toString());
		o.addProperty("seller", l.seller.toString());
		o.addProperty("sellerName", l.sellerName);
		o.addProperty("price", l.price);
		o.addProperty("created", l.created);
		o.addProperty("item", l.itemSnbt());
		return o;
	}

	static Listing fromJson(JsonObject o) {
		return Listing.restore(
				UUID.fromString(o.get("id").getAsString()),
				UUID.fromString(o.get("seller").getAsString()),
				o.get("sellerName").getAsString(),
				o.get("price").getAsLong(),
				o.get("created").getAsLong(),
				o.get("item").getAsString());
	}

	// EscrowStore flush callback: the listing jsons are already canonical.
	private void writeListings(List<String> listingJsons) throws TxException {
		try {
			JsonArray arr = new JsonArray();
			for (String json : listingJsons) {
				arr.add(JsonParser.parseString(json));
			}
			ConfigManager.writeAtomic(file, GSON.toJson(arr));
		} catch (Exception e) {
			throw new TxException("listing save failed", e);
		}
	}

	private RegistryOps<net.minecraft.nbt.Tag> ops() {
		return RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
	}

	// ---------- TxStore glue ----------

	@Override
	public synchronized boolean escrowHas(UUID id) {
		return store.has(id);
	}

	@Override
	public synchronized void escrowPutPersist(String listingJson) throws TxException {
		Listing l = fromJson(JsonParser.parseString(listingJson).getAsJsonObject());
		// the store rolls the insert back if the flush fails, so a returned
		// item can never coexist with a live listing
		store.putPersist(l.id, listingJson);
	}

	@Override
	public synchronized void escrowRemovePersist(UUID id, String listingJson) throws TxException {
		store.removePersist(id);
	}

	@Override
	public long balanceOf(UUID player) {
		return economy.core().balance(player);
	}

	@Override
	public boolean canFit(UUID playerId, String listingJson) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null) {
			return false;
		}
		try {
			ItemStack wanted = fromJson(JsonParser.parseString(listingJson).getAsJsonObject()).stack(ops());
			int max = wanted.getMaxStackSize();
			long room = 0;
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				ItemStack slot = player.getInventory().getItem(i);
				if (slot.isEmpty()) {
					room += max;
				} else if (ItemStack.isSameItemSameComponents(slot, wanted)) {
					room += max - slot.getCount();
				}
			}
			return room >= wanted.getCount();
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public boolean savePlayer(UUID player) {
		ServerPlayer p = server.getPlayerList().getPlayer(player);
		if (p == null) {
			return false;
		}
		return dev.easyeconomy.util.PlayerSaves.saveAllVerified(server, p);
	}

	public synchronized TxStore.MoneyMove moveMoney(UUID from, UUID to, long amount, String reason) {
		EconomyCore.Result pay = economy.core().withdraw(from, amount, reason);
		if (pay != EconomyCore.Result.OK) {
			return TxStore.MoneyMove.INSUFFICIENT;
		}
		EconomyCore.Result credit = economy.core().deposit(to, amount, reason);
		if (credit != EconomyCore.Result.OK) {
			// give the buyer their money back: they just paid, so the room exists
			economy.core().deposit(from, amount, reason + ":refund");
			economy.changed();
			return TxStore.MoneyMove.SELLER_FULL;
		}
		economy.changed();
		return TxStore.MoneyMove.OK;
	}

	@Override
	public void flushMoney() throws TxException {
		if (!economy.save()) {
			throw new TxException("account save failed", null);
		}
	}

	@Override
	public void journalWrite(String content) throws TxException {
		try {
			ConfigManager.writeAtomic(journalFile, content);
		} catch (Exception e) {
			throw new TxException("journal write failed", e);
		}
	}

	@Override
	public String journalRead() {
		try {
			return Files.exists(journalFile) ? Files.readString(journalFile, StandardCharsets.UTF_8) : null;
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to read pshop journal", e);
			return null;
		}
	}

	@Override
	public void journalClear() {
		try {
			Files.deleteIfExists(journalFile);
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.warn("failed to clear pshop journal", e);
		}
	}

	@Override
	public void takeHeldFromSeller(UUID seller) {
		ServerPlayer player = server.getPlayerList().getPlayer(seller);
		if (player != null) {
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		}
	}

	@Override
	public void giveItem(UUID playerId, String listingJson) {
		deliverItem(playerId, listingJson);
	}

	@Override
	public void deliverItem(UUID playerId, String listingJson) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null) {
			EasyEconomyMod.LOGGER.error("pshop delivery target offline, item lost: {}", playerId);
			return;
		}
		try {
			ItemStack stack = fromJson(JsonParser.parseString(listingJson).getAsJsonObject()).stack(ops());
			player.getInventory().add(stack);
			if (!stack.isEmpty()) {
				player.spawnAtLocation(player.level(), stack);
			}
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("pshop delivery failed, item lost", e);
		}
	}

	@Override
	public void warn(String msg) {
		EasyEconomyMod.LOGGER.warn(msg);
	}

	@Override
	public void error(String msg) {
		EasyEconomyMod.LOGGER.error(msg);
	}
}
