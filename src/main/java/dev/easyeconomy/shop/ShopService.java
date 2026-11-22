package dev.easyeconomy.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.currency.CurrencyItems;
import dev.easyeconomy.economy.EconomyCore;
import dev.easyeconomy.economy.EconomyService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Server shop: players buy configured items from the server and sell items
// back. Per-player buy limits persist across restarts so relogging or a
// reboot can't reset them.
public final class ShopService {
	public enum BuyResult { SAVE_FAILED, OK, NO_PRICE, INSUFFICIENT_FUNDS, OVER_MAX, LIMIT_REACHED, BAD_ITEM }

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Type LIMITS_TYPE = new TypeToken<Map<String, Map<String, long[]>>>() {
	}.getType();

	private final EconomyService economy;
	private final Path file;
	private final Path limitsFile;
	private ShopConfig config = new ShopConfig();
	private final Map<String, Item> itemCache = new HashMap<>();
	// player -> entry key -> [windowStartMillis, countInWindow]
	private final Map<String, Map<String, long[]>> limits = new HashMap<>();
	private boolean dirty;
	private long ticksSinceSave;

	public ShopService(EconomyService economy) {
		this.economy = economy;
		this.file = FabricLoader.getInstance().getConfigDir().resolve("easyeconomy").resolve("shop.json");
		this.limitsFile = economy.dataDir().resolve("shop-limits.json");
	}

	public ShopConfig config() {
		return config;
	}

	public Item itemOf(ShopConfig.Entry entry) {
		return itemCache.computeIfAbsent(entry.item, id -> {
			Identifier parsed = Identifier.tryParse(id);
			Item item = parsed == null ? null : BuiltInRegistries.ITEM.getValue(parsed);
			return item == null ? Items.AIR : item;
		});
	}

	private static ShopConfig parse(JsonReader reader) {
		reader.setLenient(true);
		ShopConfig loaded = GSON.fromJson(reader, ShopConfig.class);
		return loaded != null && loaded.categories != null ? loaded : new ShopConfig();
	}

	public Item itemById(String id) {
		Identifier parsed = Identifier.tryParse(id);
		Item item = parsed == null ? null : BuiltInRegistries.ITEM.getValue(parsed);
		return item == null || item == Items.AIR ? null : item;
	}

	public synchronized void load() {
		itemCache.clear();
		try {
			Files.createDirectories(file.getParent());
			if (Files.exists(file)) {
				try (JsonReader reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
					config = parse(reader);
				}
			} else {
				Files.writeString(file, DEFAULT_SHOP, StandardCharsets.UTF_8);
				config = parse(new JsonReader(new java.io.StringReader(DEFAULT_SHOP)));
			}
			// validate entries once at load; bad items are dropped with a log
			int removed = 0;
			for (ShopConfig.Category cat : config.categories) {
				if (cat.items == null) {
					cat.items = new java.util.ArrayList<>();
				}
				for (int i = cat.items.size() - 1; i >= 0; i--) {
					ShopConfig.Entry e = cat.items.get(i);
					if (e == null || itemOf(e) == Items.AIR) {
						cat.items.remove(i);
						removed++;
					}
				}
			}
			if (removed > 0) {
				EasyEconomyMod.LOGGER.warn("dropped {} shop entries with unknown items", removed);
			}
			EasyEconomyMod.LOGGER.info("shop loaded: {} categories", config.categories.size());
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load shop.json, shop empty", e);
			config = new ShopConfig();
		}
		loadLimits();
	}

	public synchronized BuyResult buy(ServerPlayer player, ServerLevel level, ShopConfig.Entry entry, int count) {
		if (entry.buyPrice <= 0) {
			return BuyResult.NO_PRICE;
		}
		Item item = itemOf(entry);
		if (item == Items.AIR) {
			return BuyResult.BAD_ITEM;
		}
		long price;
		try {
			price = Math.multiplyExact(entry.buyPrice, count);
		} catch (ArithmeticException e) {
			return BuyResult.OVER_MAX;
		}
		if (!limitAllows(player.getUUID(), entry, count)) {
			return BuyResult.LIMIT_REACHED;
		}
		EconomyCore.Result pay = economy.core().withdraw(player.getUUID(), price, "shop-buy:" + entry.item + "x" + count);
		if (pay == EconomyCore.Result.INSUFFICIENT_FUNDS) {
			return BuyResult.INSUFFICIENT_FUNDS;
		}
		if (pay != EconomyCore.Result.OK) {
			return BuyResult.OVER_MAX;
		}
		// the payment must reach disk before the items do: a crash may then
		// lose items, but can never leave both the balance and the items
		if (!economy.save()) {
			economy.core().deposit(player.getUUID(), price, "shop-buy-unwind:" + entry.item);
			economy.save();
			EasyEconomyMod.LOGGER.error("shop buy aborted: balance save failed, payment reversed");
			return BuyResult.SAVE_FAILED;
		}
		// limit is only recorded once the money actually moved
		recordLimit(player.getUUID(), entry, count);
		long left = count;
		while (left > 0) {
			int n = (int) Math.min(item.getDefaultMaxStackSize(), left);
			left -= n;
			ItemStack stack = new ItemStack(item, n);
			player.getInventory().add(stack);
			if (!stack.isEmpty()) {
				player.spawnAtLocation(level, stack);
			}
		}
		// force the new items to disk so a crash cannot roll the inventory
		// back to before the purchase while the payment stays durable
		try {
			level.getServer().getPlayerList().saveAll();
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("shop buy: forced player save failed", e);
		}
		return BuyResult.OK;
	}

	// Sells the whole held stack at its configured sell price.
	public synchronized SellOutcome sell(ServerPlayer player, ItemStack held) {
		if (held.isEmpty()) {
			return new SellOutcome(SellResult.NO_ITEM, 0, 0);
		}
		if (CurrencyItems.denominationOf(held) > 0) {
			return new SellOutcome(SellResult.IS_CURRENCY, 0, 0);
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(held.getItem());
		if (id == null) {
			return new SellOutcome(SellResult.NO_PRICE, 0, 0);
		}
		ShopConfig.Entry match = null;
		for (ShopConfig.Category cat : config.categories) {
			for (ShopConfig.Entry e : cat.items) {
				if (e.sellPrice > 0 && id.toString().equals(e.item)) {
					match = e;
					break;
				}
			}
			if (match != null) {
				break;
			}
		}
		if (match == null) {
			return new SellOutcome(SellResult.NO_PRICE, 0, 0);
		}
		long value;
		try {
			value = Math.multiplyExact(match.sellPrice, held.getCount());
		} catch (ArithmeticException e) {
			return new SellOutcome(SellResult.OVER_MAX, 0, 0);
		}
		EconomyCore.Result result = economy.core().deposit(player.getUUID(), value, "shop-sell:" + id + "x" + held.getCount());
		if (result == EconomyCore.Result.OVER_MAX_BALANCE) {
			return new SellOutcome(SellResult.OVER_MAX, 0, 0);
		}
		if (result != EconomyCore.Result.OK) {
			return new SellOutcome(SellResult.FAILED, 0, 0);
		}
		economy.changed();
		return new SellOutcome(SellResult.OK, value, held.getCount());
	}

	public enum SellResult { OK, NO_ITEM, NO_PRICE, IS_CURRENCY, OVER_MAX, FAILED }

	public record SellOutcome(SellResult result, long value, int count) {
	}

	// Remaining buys in the current window, or -1 when unlimited.
	public synchronized int limitRemaining(UUID player, ShopConfig.Entry entry) {
		if (entry.buyLimit == null || entry.buyLimit.count <= 0 || entry.buyLimit.seconds <= 0) {
			return -1;
		}
		long[] window = currentWindow(player, entry);
		return Math.max(0, entry.buyLimit.count - (int) window[1]);
	}

	private boolean limitAllows(UUID player, ShopConfig.Entry entry, int count) {
		if (entry.buyLimit == null || entry.buyLimit.count <= 0 || entry.buyLimit.seconds <= 0) {
			return true;
		}
		long[] window = currentWindow(player, entry);
		return window[1] + count <= entry.buyLimit.count;
	}

	private void recordLimit(UUID player, ShopConfig.Entry entry, int count) {
		if (entry.buyLimit == null || entry.buyLimit.count <= 0 || entry.buyLimit.seconds <= 0) {
			return;
		}
		long[] window = currentWindow(player, entry);
		window[1] += count;
		dirty = true;
	}

	private long[] currentWindow(UUID player, ShopConfig.Entry entry) {
		Map<String, long[]> byKey = limits.computeIfAbsent(player.toString(), k -> new HashMap<>());
		long[] window = byKey.computeIfAbsent(entry.key(), k -> new long[]{0, 0});
		long now = System.currentTimeMillis();
		if (window[0] == 0 || now - window[0] >= entry.buyLimit.seconds * 1000L) {
			window[0] = now;
			window[1] = 0;
		}
		return window;
	}

	public void autosaveTick() {
		int interval = Math.max(1, ConfigManager.get().autosaveMinutes) * 20 * 60;
		if (++ticksSinceSave >= interval && dirty) {
			saveLimits();
		}
	}

	public synchronized void saveLimits() {
		try {
			ConfigManager.writeAtomic(limitsFile, GSON.toJson(limits));
			dirty = false;
			ticksSinceSave = 0;
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to save shop limits", e);
		}
	}

	private void loadLimits() {
		limits.clear();
		try {
			if (Files.exists(limitsFile)) {
				Map<String, Map<String, long[]>> raw = GSON.fromJson(
						Files.readString(limitsFile, StandardCharsets.UTF_8), LIMITS_TYPE);
				if (raw != null) {
					limits.putAll(raw);
				}
			}
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load shop limits, starting fresh", e);
		}
	}

	private static final String DEFAULT_SHOP = """
		{
		  // easyeconomy server shop. /shop opens it.
		  // buyPrice: what a player pays the server for one of the item.
		  // sellPrice: what the server pays a player for one (used by /sell).
		  // a price of 0 (or left out) disables that direction.
		  // buyLimit: at most <count> buys per player per <seconds> window.
		  // limits survive server restarts. leave it out for no limit.
		  "categories": [
		    {
		      "name": "valuables",
		      "icon": "minecraft:diamond",
		      "items": [
		        { "item": "minecraft:diamond", "buyPrice": 100, "sellPrice": 40 },
		        { "item": "minecraft:netherite_ingot", "buyPrice": 2500, "sellPrice": 900, "buyLimit": { "count": 2, "seconds": 86400 } }
		      ]
		    },
		    {
		      "name": "blocks",
		      "icon": "minecraft:grass_block",
		      "items": [
		        { "item": "minecraft:stone", "buyPrice": 2 },
		        { "item": "minecraft:glass", "buyPrice": 4, "sellPrice": 1 }
		      ]
		    }
		  ]
		}
		""";
}
