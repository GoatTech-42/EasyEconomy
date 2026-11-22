package dev.easyeconomy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.currency.CurrencyMath;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static EeConfig config = new EeConfig();

	private ConfigManager() {
	}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("easyeconomy");
	}

	public static Path file() {
		return dir().resolve("config.json");
	}

	public static EeConfig get() {
		return config;
	}

	public static synchronized void load() {
		try {
			Files.createDirectories(dir());
			if (Files.exists(file())) {
				try (JsonReader reader = new JsonReader(Files.newBufferedReader(file(), StandardCharsets.UTF_8))) {
					reader.setLenient(true);
					EeConfig loaded = GSON.fromJson(reader, EeConfig.class);
					if (loaded != null) {
						config = loaded;
					}
				}
			} else {
				config = new EeConfig();
				Files.writeString(file(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load config, using defaults", e);
			config = new EeConfig();
		}
		try {
			CurrencyMath.validate(config.denominations);
		} catch (IllegalArgumentException e) {
			EasyEconomyMod.LOGGER.error("bad denominations in config ({}), using defaults", e.getMessage());
			config.denominations = new EeConfig().denominations;
		}
	}

	public static void writeAtomic(Path target, String content) throws IOException {
		dev.easyeconomy.util.AtomicFiles.writeAtomic(target, content);
	}

	private static final String DEFAULT_CONFIG = """
		{
		  // easyeconomy config. lines starting with // are comments.

		  // name of the currency in messages, one and many
		  "currencySingular": "coin",
		  "currencyPlural": "coins",

		  // balance a brand new player starts with
		  "startingBalance": 50,

		  // nobody can ever hold more than this
		  "maxBalance": 1000000000,

		  // smallest amount /pay accepts
		  "minPayAmount": 1,

		  // how often balances are written to disk, in minutes
		  "autosaveMinutes": 5,

		  // vanilla item the coins are built on. must stay an item every
		  // vanilla client already has, or players get kicked on join.
		  "currencyBaseItem": "minecraft:paper",

		  // coin denominations, biggest used first. must contain 1.
		  "denominations": [1, 5, 10, 50, 100],

		  // most listings one player can have open in the player shop
		  "maxListingsPerPlayer": 10,

		  // built-in resource pack web server. off by default.
		  // it serves one static pack file at one fixed path
		  // (/easyeconomy-pack.zip), nothing else, with per-client rate
		  // limiting. if you turn it on, point resource-pack= in
		  // server.properties at http://<this-server>:<port>/easyeconomy-pack.zip
		  // and set resource-pack-sha1 to the hash the mod logs at startup.
		  // better: host the generated pack (easyeconomy/easyeconomy-pack.zip)
		  // yourself and put that url in server.properties, then leave this off.
		  "packServer": {
		    "enabled": false,
		    "bind": "127.0.0.1",
		    "port": 8180
		  }
		}
		""";
}
