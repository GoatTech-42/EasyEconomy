package dev.easyeconomy;

import dev.easyeconomy.command.EasyEconomyCommands;
import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.economy.EconomyService;
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

	@Override
	public void onInitialize() {
		ConfigManager.load();
		economy = new EconomyService(ConfigManager.get());
		ServerLifecycleEvents.SERVER_STARTING.register(server -> economy.load());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> economy.save());
		ServerTickEvents.END_SERVER_TICK.register(server -> economy.autosaveTick());
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
				EasyEconomyCommands.register(dispatcher, economy));
		LOGGER.info("easyeconomy loaded");
	}

	public static EconomyService economy() {
		return economy;
	}
}
