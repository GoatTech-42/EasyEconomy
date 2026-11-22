package dev.easyeconomy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import dev.easyeconomy.EasyEconomyMod;
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
	}

	public static void writeAtomic(Path target, String content) throws IOException {
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
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

		  // built-in resource pack web server. off by default.
		  // it serves one static pack file at one fixed path, nothing else,
		  // with per-client rate limiting. if you turn it on, point
		  // resource-pack= in server.properties at it.
		  // better: host the generated pack yourself and put that url in
		  // server.properties instead, then leave this off.
		  "packServer": {
		    "enabled": false,
		    "bind": "127.0.0.1",
		    "port": 8180
		  }
		}
		""";
}
