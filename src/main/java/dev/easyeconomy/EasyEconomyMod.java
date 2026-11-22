package dev.easyeconomy;

import dev.easyeconomy.command.EasyEconomyCommands;
import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.economy.EconomyService;
import dev.easyeconomy.pack.PackBuilder;
import dev.easyeconomy.pack.PackServer;
import dev.easyeconomy.shop.ShopService;
import dev.easyeconomy.pshop.PlayerShopService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EasyEconomyMod implements ModInitializer {
	public static final String MOD_ID = "easyeconomy";
	public static final Logger LOGGER = LoggerFactory.getLogger("EasyEconomy");

	private static EconomyService economy;
	private static ShopService shop;
	private static PlayerShopService playerShop;
	private static final PackServer packServer = new PackServer();
	private static PackBuilder.Built pack;

	@Override
	public void onInitialize() {
		ConfigManager.load();
		economy = new EconomyService(ConfigManager.get());
		shop = new ShopService(economy);
		playerShop = new PlayerShopService(economy);
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			economy.load();
			shop.load();
			playerShop.load(server.registryAccess());
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> rebuildPack());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			packServer.stop();
			shop.saveLimits();
			economy.save();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			economy.autosaveTick();
			shop.autosaveTick();
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
				EasyEconomyCommands.register(dispatcher, economy, shop, playerShop));
		LOGGER.info("easyeconomy loaded");
	}

	public static EconomyService economy() {
		return economy;
	}

	public static ShopService shop() {
		return shop;
	}

	public static PackBuilder.Built pack() {
		return pack;
	}

	public static void reloadAll() {
		economy.reload();
		shop.load();
		rebuildPack();
	}

	private static synchronized void rebuildPack() {
		dev.easyeconomy.config.EeConfig config = ConfigManager.get();
		try {
			pack = PackBuilder.build(economy.dataDir().resolve("easyeconomy-pack.zip"), config);
		} catch (Exception e) {
			LOGGER.error("failed to build resource pack", e);
			pack = null;
		}
		try {
			if (config.packServer.enabled && pack != null) {
				packServer.start(config.packServer, pack.zip());
			} else {
				packServer.stop();
			}
		} catch (Exception e) {
			LOGGER.error("failed to start pack server", e);
		}
	}
}
