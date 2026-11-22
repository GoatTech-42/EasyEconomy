package dev.easyeconomy.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.config.EeConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Minecraft-facing wrapper around EconomyCore: persistence, autosave, reload.
public final class EconomyService {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Type ACCOUNTS_TYPE = new TypeToken<Map<String, Long>>() {
	}.getType();

	private final Path dataDir;
	private final Path accountsFile;
	private final JsonlLedger ledger;
	private EconomyCore core;
	private boolean dirty;
	private long ticksSinceSave;

	public EconomyService(EeConfig config) {
		this.dataDir = FabricLoader.getInstance().getGameDir().resolve("easyeconomy");
		this.accountsFile = dataDir.resolve("accounts.json");
		this.ledger = new JsonlLedger(dataDir.resolve("ledger.jsonl"), System::currentTimeMillis);
		this.core = new EconomyCore(config.startingBalance, config.maxBalance, ledger);
	}

	public EconomyCore core() {
		return core;
	}

	public String format(long amount) {
		EeConfig config = ConfigManager.get();
		return amount + " " + (amount == 1 ? config.currencySingular : config.currencyPlural);
	}

	public void changed() {
		dirty = true;
	}

	public Path dataDir() {
		return dataDir;
	}

	public synchronized void load() {
		try {
			if (Files.exists(accountsFile)) {
				String json = Files.readString(accountsFile, StandardCharsets.UTF_8);
				Map<String, Long> raw = GSON.fromJson(json, ACCOUNTS_TYPE);
				Map<UUID, Long> data = new HashMap<>();
				if (raw != null) {
					for (Map.Entry<String, Long> e : raw.entrySet()) {
						try {
							data.put(UUID.fromString(e.getKey()), e.getValue());
						} catch (IllegalArgumentException bad) {
							EasyEconomyMod.LOGGER.warn("skipping bad account id {}", e.getKey());
						}
					}
				}
				core.loadAll(data);
			}
			EasyEconomyMod.LOGGER.info("loaded {} accounts", core.snapshot().size());
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load accounts, starting empty", e);
		}
	}

	public synchronized void save() {
		try {
			Map<String, Long> raw = new HashMap<>();
			for (Map.Entry<UUID, Long> e : core.snapshot().entrySet()) {
				raw.put(e.getKey().toString(), e.getValue());
			}
			ConfigManager.writeAtomic(accountsFile, GSON.toJson(raw));
			dirty = false;
			ticksSinceSave = 0;
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to save accounts", e);
		}
	}

	public void autosaveTick() {
		int interval = Math.max(1, ConfigManager.get().autosaveMinutes) * 20 * 60;
		if (++ticksSinceSave >= interval && dirty) {
			save();
		}
	}

	public synchronized void reload() {
		ConfigManager.load();
		EeConfig config = ConfigManager.get();
		Map<UUID, Long> keep = core.snapshot();
		core = new EconomyCore(config.startingBalance, config.maxBalance, ledger);
		core.loadAll(keep);
	}
}
