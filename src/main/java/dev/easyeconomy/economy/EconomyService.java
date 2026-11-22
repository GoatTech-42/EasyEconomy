package dev.easyeconomy.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.config.EeConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Minecraft-facing wrapper around EconomyCore: persistence, autosave, reload.
public final class EconomyService {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Path dataDir;
	private final Path accountsFile;
	private final JsonlLedger ledger;
	private EconomyCore core;
	private boolean dirty;
	private long ticksSinceSave;
	// fail closed: set when an unreadable accounts file could not be
	// quarantined; save() refuses until a valid load confirms recovery
	private boolean savesBlocked;

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
				// whole-file syntax corruption throws here and takes the
				// quarantine path; row-level corruption is handled per row
				JsonObject raw = GSON.fromJson(json, JsonObject.class);
				Map<UUID, Long> data = new HashMap<>();
				if (raw != null) {
					for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
						UUID id;
						try {
							id = UUID.fromString(e.getKey());
						} catch (IllegalArgumentException bad) {
							EasyEconomyMod.LOGGER.warn("skipping bad account id {}", e.getKey());
							continue;
						}
						// one malformed row must never wipe the rest of the file:
						// decode the balance per row so a bad value is skipped
						// without discarding its valid siblings
						long value;
						try {
							// strict integer: getAsLong() would silently truncate
							// a fractional balance (75.9 -> 75)
							value = Long.parseLong(e.getValue().getAsString().trim());
						} catch (RuntimeException bad) {
							EasyEconomyMod.LOGGER.warn("skipping account {} with malformed balance {}", id, e.getValue());
							continue;
						}
						if (value < 0 || value > core.maxBalance()) {
							EasyEconomyMod.LOGGER.warn("skipping account {} with out-of-range balance {}", id, value);
							continue;
						}
						data.put(id, value);
					}
				}
				core.loadAll(data);
			}
			savesBlocked = false;
			EasyEconomyMod.LOGGER.info("loaded {} accounts", core.snapshot().size());
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load accounts, starting empty", e);
			// never let the next autosave overwrite a file we could not read:
			// move it aside first, and never overwrite an earlier quarantine
			try {
				if (Files.exists(accountsFile)) {
					String base = accountsFile.getFileName() + ".corrupt-" + System.currentTimeMillis();
					Path aside = accountsFile.resolveSibling(base);
					int n = 1;
					while (Files.exists(aside)) {
						aside = accountsFile.resolveSibling(base + "-" + (n++));
					}
					Files.move(accountsFile, aside);
					savesBlocked = false;
					EasyEconomyMod.LOGGER.error("unreadable accounts file quarantined to {}", aside.getFileName());
				}
			} catch (Exception moveFailed) {
				savesBlocked = true;
				EasyEconomyMod.LOGGER.error("could not quarantine the unreadable accounts file; saves are BLOCKED until a valid load succeeds", moveFailed);
			}
		}
	}

	public synchronized boolean save() {
		if (savesBlocked) {
			EasyEconomyMod.LOGGER.error("refusing to save accounts: an unreadable accounts file could not be"
					+ " quarantined; fix or remove it and reload before saving can resume");
			return false;
		}
		try {
			Map<String, Long> raw = new HashMap<>();
			for (Map.Entry<UUID, Long> e : core.snapshot().entrySet()) {
				raw.put(e.getKey().toString(), e.getValue());
			}
			ConfigManager.writeAtomic(accountsFile, GSON.toJson(raw));
			dirty = false;
			ticksSinceSave = 0;
			return true;
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to save accounts", e);
			return false;
		}
	}

	public void autosaveTick() {
		long interval = (long) Math.max(1, ConfigManager.get().autosaveMinutes) * 20 * 60;
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
