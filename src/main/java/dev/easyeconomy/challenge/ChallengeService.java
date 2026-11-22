package dev.easyeconomy.challenge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.economy.EconomyCore;
import dev.easyeconomy.economy.EconomyService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// Challenges pay coins for things players do. Progress comes from vanilla
// stats and advancement state, polled once a second: deltas accumulate into
// per-player progress, so relogging or re-picking an item can't retrigger
// anything, and a stat a player already had never counts toward a challenge.
public final class ChallengeService {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int POLL_TICKS = 20;

	private final EconomyService economy;
	private final Path file;
	private final Path progressFile;
	private ChallengeConfig config = new ChallengeConfig();
	private MinecraftServer server;
	private int ticks;

	// player uuid -> progress state
	private final Map<String, PlayerState> states = new HashMap<>();
	private boolean dirty;
	private long ticksSinceSave;

	private static final class PlayerState {
		Map<String, Long> lastStats = new HashMap<>();
		Map<String, Integer> progress = new HashMap<>();
		Map<String, Long> completedAt = new HashMap<>();
		Map<String, Long> cooldownUntil = new HashMap<>();
	}

	public ChallengeService(EconomyService economy) {
		this.economy = economy;
		this.file = FabricLoader.getInstance().getConfigDir().resolve("easyeconomy").resolve("challenges.json");
		this.progressFile = economy.dataDir().resolve("challenge-progress.json");
	}

	public ChallengeConfig config() {
		return config;
	}

	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		load();
		loadProgress();
	}

	public void tick(MinecraftServer server) {
		if (++ticks >= POLL_TICKS) {
			ticks = 0;
			poll(server);
		}
		int interval = Math.max(1, ConfigManager.get().autosaveMinutes) * 20 * 60;
		if (++ticksSinceSave >= interval && dirty) {
			saveProgress();
		}
	}

	public Item iconOf(ChallengeConfig.Challenge c) {
		Identifier id = Identifier.tryParse(c.icon);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
		return item == null || item == Items.AIR ? Items.PAPER : item;
	}

	// progress for the gui: -1 when not started
	public synchronized int progressOf(java.util.UUID player, String challengeId) {
		PlayerState state = states.get(player.toString());
		if (state == null) {
			return -1;
		}
		return state.progress.getOrDefault(challengeId, 0);
	}

	public synchronized boolean isDone(java.util.UUID player, String challengeId) {
		PlayerState state = states.get(player.toString());
		return state != null && state.completedAt.containsKey(challengeId);
	}

	public synchronized long cooldownLeft(java.util.UUID player, String challengeId) {
		PlayerState state = states.get(player.toString());
		if (state == null) {
			return 0;
		}
		long until = state.cooldownUntil.getOrDefault(challengeId, 0L);
		return Math.max(0, (until - System.currentTimeMillis()) / 1000);
	}

	private void poll(MinecraftServer server) {
		if (config.challenges.isEmpty()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerState state = states.computeIfAbsent(player.getUUID().toString(), k -> new PlayerState());
			for (ChallengeConfig.Challenge c : config.challenges) {
				pollOne(player, state, c);
			}
		}
	}

	private void pollOne(ServerPlayer player, PlayerState state, ChallengeConfig.Challenge c) {
		if (c.id.isEmpty() || c.reward <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean doneOnce = state.completedAt.containsKey(c.id);
		if (doneOnce && !c.repeatable) {
			return;
		}
		if (state.cooldownUntil.getOrDefault(c.id, 0L) > now) {
			return;
		}
		if ("advancement".equals(c.type)) {
			Identifier advId = Identifier.tryParse(c.target);
			if (advId == null) {
				return;
			}
			AdvancementHolder holder = server.getAdvancements().get(advId);
			if (holder == null) {
				return;
			}
			boolean done = player.getAdvancements().getOrStartProgress(holder).isDone();
			String key = "adv:" + c.id;
			boolean was = state.lastStats.getOrDefault(key, 0L) == 1;
			state.lastStats.put(key, done ? 1L : 0L);
			if (done && !was) {
				complete(player, state, c, now);
			}
			return;
		}
		long current = currentValue(player, c);
		if (current < 0) {
			return;
		}
		String key = c.id;
		Long last = state.lastStats.put(key, current);
		if (last == null) {
			return; // baseline only: existing stats never count
		}
		long delta = current - last;
		if (delta <= 0) {
			return;
		}
		long goal = goalOf(c);
		long progress = state.progress.getOrDefault(c.id, 0);
		long next = Math.min(goal, progress + delta);
		state.progress.put(c.id, (int) next);
		dirty = true;
		if (next >= goal) {
			complete(player, state, c, now);
		}
	}

	private static long goalOf(ChallengeConfig.Challenge c) {
		// playtime counts minutes, the stat is ticks
		return "playtime".equals(c.type) ? Math.max(1, c.count) * 1200L : Math.max(1, c.count);
	}

	public long goal(ChallengeConfig.Challenge c) {
		return goalOf(c);
	}

	// Current stat value for a challenge, or -1 when its target is invalid.
	// obtain counts both pickups and crafts.
	private long currentValue(ServerPlayer player, ChallengeConfig.Challenge c) {
		Identifier id = Identifier.tryParse(c.target);
		switch (c.type) {
			case "obtain" -> {
				if (id == null) {
					return -1;
				}
				Item item = BuiltInRegistries.ITEM.getValue(id);
				if (item == null || item == Items.AIR) {
					return -1;
				}
				return (long) player.getStats().getValue(Stats.ITEM_PICKED_UP.get(item))
						+ player.getStats().getValue(Stats.ITEM_CRAFTED.get(item));
			}
			case "kill" -> {
				if (id == null) {
					return -1;
				}
				EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
				return type == null ? -1 : player.getStats().getValue(Stats.ENTITY_KILLED.get(type));
			}
			case "mine" -> {
				if (id == null) {
					return -1;
				}
				Block block = BuiltInRegistries.BLOCK.getValue(id);
				return block == null || block == Blocks.AIR ? -1 : player.getStats().getValue(Stats.BLOCK_MINED.get(block));
			}
			case "craft" -> {
				if (id == null) {
					return -1;
				}
				Item item = BuiltInRegistries.ITEM.getValue(id);
				return item == null || item == Items.AIR ? -1 : player.getStats().getValue(Stats.ITEM_CRAFTED.get(item));
			}
			case "playtime" -> {
				return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
			}
			default -> {
				return -1;
			}
		}
	}

	private void complete(ServerPlayer player, PlayerState state, ChallengeConfig.Challenge c, long now) {
		EconomyCore.Result result = economy.core().deposit(player.getUUID(), c.reward, "challenge:" + c.id);
		if (result == EconomyCore.Result.OVER_MAX_BALANCE) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
					"challenge \"" + c.name + "\" done, but your balance is full - make room and it will pay out"));
			return; // stays completable: progress is already at goal, retried next poll
		}
		if (result != EconomyCore.Result.OK) {
			return;
		}
		economy.changed();
		state.completedAt.put(c.id, now);
		if (c.repeatable) {
			state.progress.put(c.id, 0);
			if (c.cooldownSeconds > 0) {
				state.cooldownUntil.put(c.id, now + c.cooldownSeconds * 1000L);
			}
		}
		dirty = true;
		player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"challenge complete: " + c.name + " (+" + economy.format(c.reward) + ")"));
	}

	public synchronized void load() {
		try {
			Files.createDirectories(file.getParent());
			if (Files.exists(file)) {
				try (JsonReader reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
					reader.setLenient(true);
					ChallengeConfig loaded = GSON.fromJson(reader, ChallengeConfig.class);
					config = loaded != null && loaded.challenges != null ? loaded : new ChallengeConfig();
				}
			} else {
				Files.writeString(file, DEFAULT_CHALLENGES, StandardCharsets.UTF_8);
				try (JsonReader reader = new JsonReader(new StringReader(DEFAULT_CHALLENGES))) {
					reader.setLenient(true);
					ChallengeConfig loaded = GSON.fromJson(reader, ChallengeConfig.class);
					config = loaded != null && loaded.challenges != null ? loaded : new ChallengeConfig();
				}
			}
			EasyEconomyMod.LOGGER.info("challenges loaded: {}", config.challenges.size());
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load challenges.json, none active", e);
			config = new ChallengeConfig();
		}
	}

	public synchronized void saveProgress() {
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<String, PlayerState> e : states.entrySet()) {
				JsonObject o = new JsonObject();
				o.add("lastStats", GSON.toJsonTree(e.getValue().lastStats));
				o.add("progress", GSON.toJsonTree(e.getValue().progress));
				o.add("completedAt", GSON.toJsonTree(e.getValue().completedAt));
				o.add("cooldownUntil", GSON.toJsonTree(e.getValue().cooldownUntil));
				root.add(e.getKey(), o);
			}
			ConfigManager.writeAtomic(progressFile, GSON.toJson(root));
			dirty = false;
			ticksSinceSave = 0;
		} catch (Exception ex) {
			EasyEconomyMod.LOGGER.error("failed to save challenge progress", ex);
		}
	}

	private void loadProgress() {
		states.clear();
		try {
			if (!Files.exists(progressFile)) {
				return;
			}
			JsonObject root = JsonParser.parseString(Files.readString(progressFile, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				PlayerState state = new PlayerState();
				state.lastStats = readLongMap(o, "lastStats");
				state.progress = readIntMap(o, "progress");
				state.completedAt = readLongMap(o, "completedAt");
				state.cooldownUntil = readLongMap(o, "cooldownUntil");
				states.put(e.getKey(), state);
			}
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("failed to load challenge progress, starting fresh", e);
		}
	}

	private static Map<String, Long> readLongMap(JsonObject o, String key) {
		Map<String, Long> out = new HashMap<>();
		if (o.has(key)) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
				out.put(e.getKey(), e.getValue().getAsLong());
			}
		}
		return out;
	}

	private static Map<String, Integer> readIntMap(JsonObject o, String key) {
		Map<String, Integer> out = new HashMap<>();
		if (o.has(key)) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
				out.put(e.getKey(), e.getValue().getAsInt());
			}
		}
		return out;
	}

	private static final String DEFAULT_CHALLENGES = """
		{
		  // easyeconomy challenges. /challenges shows them in game.
		  // types:
		  //   advancement - finish an advancement. target: its id.
		  //   obtain      - pick up or craft <count> of an item.
		  //   kill        - kill <count> of a mob.
		  //   mine        - break <count> of a block.
		  //   craft       - craft <count> of an item.
		  //   playtime    - play for <count> minutes. target ignored.
		  // progress only counts things done after a player is first seen,
		  // so old stats never complete a challenge. repeatable challenges
		  // can pay out again after cooldownSeconds.
		  "challenges": [
		    {
		      "id": "uneasy-alliance",
		      "name": "an uneasy alliance",
		      "description": "rescue a ghast from the nether, bring it home, and kill it",
		      "icon": "minecraft:ghast_tear",
		      "reward": 14,
		      "type": "advancement",
		      "target": "minecraft:nether/uneasy_alliance"
		    },
		    {
		      "id": "wither-skull",
		      "name": "wither hunter",
		      "description": "obtain a wither skeleton skull",
		      "icon": "minecraft:wither_skeleton_skull",
		      "reward": 30,
		      "type": "obtain",
		      "target": "minecraft:wither_skeleton_skull",
		      "count": 1,
		      "repeatable": true,
		      "cooldownSeconds": 86400
		    },
		    {
		      "id": "zombie-slayer",
		      "name": "zombie slayer",
		      "description": "kill 100 zombies",
		      "icon": "minecraft:zombie_head",
		      "reward": 50,
		      "type": "kill",
		      "target": "minecraft:zombie",
		      "count": 100,
		      "repeatable": true,
		      "cooldownSeconds": 86400
		    },
		    {
		      "id": "diamond-miner",
		      "name": "diamond miner",
		      "description": "mine 32 diamond ore",
		      "icon": "minecraft:diamond_ore",
		      "reward": 100,
		      "type": "mine",
		      "target": "minecraft:diamond_ore",
		      "count": 32
		    }
		  ]
		}
		""";
}
