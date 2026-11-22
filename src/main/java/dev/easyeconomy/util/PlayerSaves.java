package dev.easyeconomy.util;

import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.selftest.SelfTest;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.TagValueOutput;

// Verifiable player-data saves. Vanilla's PlayerDataStorage.save catches
// every exception internally and returns normally, so calling saveAll()
// can never prove the data reached disk. This helper saves and then reads
// the .dat back, comparing the serialized inventory with the live one;
// it returns true only when the two match.
public final class PlayerSaves {
	// Selftest-only fault injection. WRITE_BUT_UNVERIFIED performs the real
	// save but reports the read-back as failed (the .dat reached disk while
	// the caller cannot prove it); SKIP_AND_UNVERIFIED performs no write and
	// reports failure (the silent-loss case). Both exist so the integration
	// suite can rehearse crash windows against the real services.
	public enum TestFault { WRITE_BUT_UNVERIFIED, SKIP_AND_UNVERIFIED }

	private static Field storageField; // MinecraftServer.playerDataStorage (no public getter)
	private static boolean storageFieldTried;
	private static boolean degradedLogged;
	private static final Deque<TestFault> testScript = new ArrayDeque<>();

	private PlayerSaves() {
	}

	public static void scriptForTesting(TestFault... faults) {
		if (!SelfTest.enabled()) {
			throw new IllegalStateException("save fault injection is selftest-only");
		}
		testScript.clear();
		testScript.addAll(Arrays.asList(faults));
	}

	public static void clearTestScript() {
		testScript.clear();
	}

	// Save every online player, then verify this player's file by read-back.
	public static boolean saveAllVerified(MinecraftServer server, ServerPlayer player) {
		// the latch only fires while the selftest is enabled; a queued fault
		// must never leak into a normal server if the property went away
		TestFault fault = null;
		if (SelfTest.enabled()) {
			fault = testScript.poll();
		} else if (!testScript.isEmpty()) {
			testScript.clear();
		}
		if (fault == TestFault.SKIP_AND_UNVERIFIED) {
			EasyEconomyMod.LOGGER.error("player save skipped by selftest fault injection");
			return false;
		}
		try {
			server.getPlayerList().saveAll();
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("forced player save threw", e);
			return false;
		}
		if (fault == TestFault.WRITE_BUT_UNVERIFIED) {
			EasyEconomyMod.LOGGER.error("player save read-back failed by selftest fault injection");
			return false;
		}
		return verifyOnDisk(server, player);
	}

	private static boolean verifyOnDisk(MinecraftServer server, ServerPlayer player) {
		PlayerDataStorage storage = storage(server);
		if (storage == null) {
			// the read-back path itself is unavailable (api drift); degrade
			// to a plain forced save rather than bricking the economy, and
			// say so loudly once
			if (!degradedLogged) {
				degradedLogged = true;
				EasyEconomyMod.LOGGER.error("player save verification unavailable; forced saves degrade to best-effort");
			}
			return true;
		}
		try {
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
			player.getInventory().save(out.list("Inventory", net.minecraft.world.ItemStackWithSlot.CODEC));
			ListTag live = out.buildResult().getListOrEmpty("Inventory");
			var loaded = storage.load(new NameAndId(player.getUUID(), player.getGameProfile().name()));
			if (loaded.isEmpty()) {
				EasyEconomyMod.LOGGER.error("player save verification failed: no saved file for {}", player.getUUID());
				return false;
			}
			ListTag disk = loaded.get().getListOrEmpty("Inventory");
			if (!live.equals(disk)) {
				EasyEconomyMod.LOGGER.error("player save verification failed: saved inventory does not match live for {}", player.getUUID());
				return false;
			}
			return true;
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("player save verification threw", e);
			return false;
		}
	}

	private static PlayerDataStorage storage(MinecraftServer server) {
		if (!storageFieldTried) {
			storageFieldTried = true;
			try {
				storageField = MinecraftServer.class.getDeclaredField("playerDataStorage");
				storageField.setAccessible(true);
			} catch (Exception e) {
				EasyEconomyMod.LOGGER.error("cannot reach MinecraftServer.playerDataStorage", e);
				storageField = null;
			}
		}
		if (storageField == null) {
			return null;
		}
		try {
			return (PlayerDataStorage) storageField.get(server);
		} catch (Exception e) {
			return null;
		}
	}
}
