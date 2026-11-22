package dev.easyeconomy.selftest;

import com.mojang.authlib.GameProfile;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.challenge.ChallengeService;
import dev.easyeconomy.currency.CurrencyItems;
import dev.easyeconomy.economy.EconomyService;
import dev.easyeconomy.gui.MenuGui;
import dev.easyeconomy.pshop.PlayerShopGui;
import dev.easyeconomy.pshop.PlayerShopService;
import dev.easyeconomy.shop.ShopGui;
import dev.easyeconomy.shop.ShopService;
import dev.easyeconomy.util.PlayerSaves;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.server.level.ParticleStatus;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

// End-to-end integration suite, run inside a real headless server with
// fake players: gradle runServer -PeeSelftest=run, then =verify. The run
// phase walks every command and gui flow against real services, forces
// saves, and records expected state; the verify phase boots a fresh server
// and checks that state survived. Not active in normal play: everything
// here is gated on -Deasyeconomy.selftest.
public final class SelfTest {
	private static boolean started;
	private static boolean failed;
	private static int tick;
	private static MinecraftServer server;
	private static ServerPlayer a;
	private static ServerPlayer b;
	private static final Map<Integer, Runnable> stages = new TreeMap<>();

	private SelfTest() {
	}

	// Only the two real modes enable the selftest. The gradle dev runtime
	// passes -Deasyeconomy.selftest=off by default, and "off" (or anything
	// else unexpected) must mean off: otherwise every dev server would run
	// the verify plan against a world that was never staged.
	public static boolean enabled() {
		String mode = System.getProperty("easyeconomy.selftest");
		return "run".equals(mode) || "verify".equals(mode);
	}

	// thrown after a failed check so the rest of the stage does not run
	// against a server that is already halting
	private static final class SelfTestFailure extends RuntimeException {
		SelfTestFailure(String name) {
			super(name, null, false, false);
		}
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			started = true;
		});
		if ("run".equals(System.getProperty("easyeconomy.selftest"))) {
			planRun();
		} else {
			planVerify();
		}
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (!started || failed) {
				return;
			}
			tick++;
			Runnable stage = stages.remove(tick);
			if (stage != null) {
				try {
					stage.run();
				} catch (SelfTestFailure t) {
					// already logged by check(); just stop
				} catch (Throwable t) {
					EasyEconomyMod.LOGGER.error("SELFTEST FAIL: stage threw", t);
					failed = true;
					s.halt(false);
				}
			}
		});
	}

	// ---------- run phase ----------

	private static void planRun() {
		stages.put(20, () -> {
			a = fakePlayer("itest_a");
			b = fakePlayer("itest_b");
			check("fake players joined", server.getPlayerList().getPlayers().size() >= 2);
		});
		stages.put(25, () -> {
			console("eco give itest_a 100");
			check("eco give", bal(a) == 150);
		});
		stages.put(30, () -> {
			as(a, "pay itest_b 40");
			check("pay debits", bal(a) == 110);
			check("pay credits", bal(b) == 90);
		});
		stages.put(35, () -> {
			as(a, "withdraw 37");
			check("withdraw debits", bal(a) == 73);
			check("withdraw gives coins", coinValue(a) == 37);
		});
		stages.put(40, () -> {
			as(a, "deposit all");
			check("deposit all credits", bal(a) == 110);
			check("deposit all clears coins", coinValue(a) == 0);
		});
		stages.put(45, () -> {
			// forged by name only: anvil renames must not count as money
			ItemStack fake = new ItemStack(Items.PAPER);
			fake.set(DataComponents.CUSTOM_NAME, Component.literal("5 coins"));
			a.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, fake);
			as(a, "deposit");
			check("name-forged coin rejected", bal(a) == 110 && a.getMainHandItem().is(Items.PAPER));
			// forged tag on the wrong base item must not count either
			CompoundTag inner = new CompoundTag();
			inner.putLong("d", 5L);
			CompoundTag tag = new CompoundTag();
			tag.put("easyeconomy", inner);
			ItemStack wrongBase = new ItemStack(Items.STONE);
			wrongBase.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
			a.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, wrongBase);
			as(a, "deposit");
			check("wrong-base forged coin rejected", bal(a) == 110 && a.getMainHandItem().is(Items.STONE));
			// a real coin deposits fine
			as(a, "withdraw 5");
			for (int i = 0; i < a.getInventory().getContainerSize(); i++) {
				ItemStack s = a.getInventory().getItem(i);
				if (CurrencyItems.denominationOf(s) > 0) {
					a.getInventory().setItem(i, ItemStack.EMPTY);
					a.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, s);
					break;
				}
			}
			as(a, "deposit");
			check("real coin deposits", bal(a) == 110 && a.getMainHandItem().isEmpty());
		});
		stages.put(60, () -> {
			ShopService shop = EasyEconomyMod.shop();
			ShopGui.openRoot(a, shop, EasyEconomyMod.economy());
			check("shop menu open", a.containerMenu instanceof net.minecraft.world.inventory.ChestMenu);
			click(a, 0, ContainerInput.PICKUP); // valuables category
			click(a, 0, ContainerInput.PICKUP); // diamond x1 @100
			check("shop buy debits", bal(a) == 10);
			check("shop buy delivers", countItem(a, Items.DIAMOND) == 1);
			click(a, 1, ContainerInput.PICKUP); // netherite, can't afford
			check("shop buy rejects poor", bal(a) == 10);
			console("eco give itest_a 10000");
			click(a, 1, ContainerInput.PICKUP);
			check("netherite buy 1", bal(a) == 7510);
			click(a, 1, ContainerInput.PICKUP);
			check("netherite buy 2", bal(a) == 5010);
			click(a, 1, ContainerInput.PICKUP);
			check("buy limit blocks third", bal(a) == 5010);
			var netherite = shop.config().categories.get(0).items.get(1);
			check("limit recorded", shop.limitRemaining(a.getUUID(), netherite) == 0);
			// back button is the last slot of the chest container; the menu
			// also exposes the player's 36 inventory slots after it
			click(a, a.containerMenu.slots.size() - 36 - 1, ContainerInput.PICKUP);
			click(a, 1, ContainerInput.PICKUP); // blocks category
			click(a, 0, ContainerInput.QUICK_MOVE); // stone x16 @32
			check("shift buy 16 stone", bal(a) == 4978 && countItem(a, Items.STONE) == 16);
		});
		stages.put(80, () -> {
			a.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 3));
			as(a, "sell");
			check("sell pays out", bal(a) == 5098);
			check("sell clears hand", a.getMainHandItem().isEmpty());
		});
		stages.put(90, () -> {
			a.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 16));
			as(a, "ps sell 25");
			check("listing created", EasyEconomyMod.playerShop().all().size() == 1);
			check("listing escrowed hand", a.getMainHandItem().isEmpty());
			PlayerShopGui.open(b, EasyEconomyMod.playerShop(), EasyEconomyMod.economy());
			click(b, 0, ContainerInput.PICKUP); // buy the listing
			check("buyer charged", bal(b) == 65);
			check("seller paid", bal(a) == 5123);
			check("buyer received item", countItem(b, Items.STONE) == 16);
			check("listing consumed", EasyEconomyMod.playerShop().all().isEmpty());
		});
		stages.put(100, () -> {
			a.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 8));
			as(a, "ps sell 10");
			check("second listing created", EasyEconomyMod.playerShop().all().size() == 1);
			PlayerShopGui.open(a, EasyEconomyMod.playerShop(), EasyEconomyMod.economy());
			click(a, 49, ContainerInput.PICKUP); // toggle to my listings
			click(a, 0, ContainerInput.PICKUP); // cancel it
			check("cancel returns listing", EasyEconomyMod.playerShop().all().isEmpty());
			check("cancel returns item", countItem(a, Items.STONE) == 8 + 16);
		});
		stages.put(110, () -> b.awardStat(Stats.ENTITY_KILLED.get(EntityTypes.ZOMBIE), 100));
		stages.put(145, () -> {
			ChallengeService challenges = EasyEconomyMod.challenges();
			check("zombie slayer complete", challenges.isDone(b.getUUID(), "zombie-slayer"));
			check("challenge paid", bal(b) == 115);
			b.awardStat(Stats.ITEM_PICKED_UP.get(Items.WITHER_SKELETON_SKULL), 1);
		});
		stages.put(180, () -> {
			ChallengeService challenges = EasyEconomyMod.challenges();
			check("wither skull complete", challenges.cooldownLeft(b.getUUID(), "wither-skull") > 0);
			check("obtain challenge paid", bal(b) == 145);
		});
		stages.put(185, () -> {
			// fill the balance to the cap, then complete a challenge: the
			// reward must not vanish, it must go pending
			console("eco set itest_b 1000000000");
			b.awardStat(Stats.BLOCK_MINED.get(net.minecraft.world.level.block.Blocks.DIAMOND_ORE), 32);
		});
		stages.put(220, () -> {
			ChallengeService challenges = EasyEconomyMod.challenges();
			check("diamond miner done", challenges.isDone(b.getUUID(), "diamond-miner"));
			check("full balance not paid", bal(b) == 1_000_000_000L);
			check("reward pending", challenges.pendingOf(b.getUUID(), "diamond-miner") == 100);
			console("eco take itest_b 500000000");
		});
		stages.put(255, () -> {
			ChallengeService challenges = EasyEconomyMod.challenges();
			check("pending reward paid after room", bal(b) == 500_000_100L);
			check("pending cleared", challenges.pendingOf(b.getUUID(), "diamond-miner") == 0);
		});
		stages.put(256, () -> {
			// QA round-3 regression: a buyer whose 36 main slots are full has no
			// room, even though equipment/offhand are empty. The buy must be
			// refused, not completed with the item spilled at their feet.
			console("eco give itest_a 1000");
			for (int i = 0; i < a.getInventory().getNonEquipmentItems().size(); i++) {
				a.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
			}
			b.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 1));
			as(b, "ps sell 5");
			check("r3 listing created", EasyEconomyMod.playerShop().all().size() == 1);
			long before = bal(a);
			PlayerShopService.BuyResult result = EasyEconomyMod.playerShop().buy(a,
					EasyEconomyMod.playerShop().all().get(0).id);
			check("full-inventory pshop buy refused", result == PlayerShopService.BuyResult.NO_ROOM);
			check("full-inventory pshop buy left balance alone", bal(a) == before);
			check("refused listing still for sale", EasyEconomyMod.playerShop().all().size() == 1);
			PlayerShopGui.open(b, EasyEconomyMod.playerShop(), EasyEconomyMod.economy());
			click(b, 49, ContainerInput.PICKUP); // my listings
			click(b, 0, ContainerInput.PICKUP); // cancel it
			check("r3 listing cancelled", EasyEconomyMod.playerShop().all().isEmpty());
			a.getInventory().clearContent();
		});
		stages.put(257, () -> {
			// QA round-3 regression: the delivery save writes but its read-back
			// fails, then the removal save silently does not write. The sale must
			// stand: refunding there would leave the paid item durable on disk
			// AND the money back in the account.
			ShopService shop = EasyEconomyMod.shop();
			dev.easyeconomy.shop.ShopConfig.Entry entry = null;
			for (var cat : shop.config().categories) {
				for (var e : cat.items) {
					if (e.buyPrice > 0 && shop.limitRemaining(a.getUUID(), e) != 0) {
						entry = e;
						break;
					}
				}
				if (entry != null) {
					break;
				}
			}
			check("r3 shop entry available", entry != null);
			final var chosen = entry;
			var item = shop.itemOf(chosen);
			long beforeBal = bal(a);
			int beforeCount = countItem(a, item);
			PlayerSaves.scriptForTesting(PlayerSaves.TestFault.WRITE_BUT_UNVERIFIED,
					PlayerSaves.TestFault.SKIP_AND_UNVERIFIED);
			ShopService.BuyResult result = shop.buy(a, a.level(), chosen, 1);
			PlayerSaves.clearTestScript();
			check("unverified shop delivery: sale stands", result == ShopService.BuyResult.OK_SAVE_UNVERIFIED);
			check("unverified shop delivery: balance debited", bal(a) == beforeBal - chosen.buyPrice);
			check("unverified shop delivery: item kept", countItem(a, item) == beforeCount + 1);
		});
		stages.put(258, () -> {
			// Same fault pair on /withdraw: the coins may already be durable, so
			// the debit must stand and the coins stay in hand; refunding against
			// possibly-saved coins would dupe them.
			long beforeBal = bal(a);
			PlayerSaves.scriptForTesting(PlayerSaves.TestFault.WRITE_BUT_UNVERIFIED,
					PlayerSaves.TestFault.SKIP_AND_UNVERIFIED);
			as(a, "withdraw 5");
			PlayerSaves.clearTestScript();
			check("unverified withdraw: withdrawal stands", bal(a) == beforeBal - 5);
			check("unverified withdraw: coins kept", coinValue(a) == 5);
		});
		stages.put(259, () -> {
			// QA round-3: the cancel return-save window. Escrow removal is
			// durable before the returned item is provably saved; when that
			// save cannot be verified the cancel still stands (loss-biased,
			// never a dupe) and the failure must be loud.
			b.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD, 3));
			as(b, "ps sell 7");
			check("r3 cancel listing created", EasyEconomyMod.playerShop().all().size() == 1);
			int emeraldsBefore = countItem(b, Items.EMERALD);
			PlayerSaves.scriptForTesting(PlayerSaves.TestFault.SKIP_AND_UNVERIFIED);
			PlayerShopGui.open(b, EasyEconomyMod.playerShop(), EasyEconomyMod.economy());
			click(b, 49, ContainerInput.PICKUP); // my listings
			click(b, 0, ContainerInput.PICKUP); // cancel it
			PlayerSaves.clearTestScript();
			check("cancel with unverified return save: escrow empty", EasyEconomyMod.playerShop().all().isEmpty());
			check("cancel with unverified return save: item kept in memory",
					countItem(b, Items.EMERALD) == emeraldsBefore + 3);
		});
		stages.put(260, () -> {
			// QA round-4: an over-balance withdraw must refuse before anything
			// proportional to the request is allocated. On unfixed code the
			// first command exhausts the heap building coin stacks.
			long before = bal(a);
			as(a, "withdraw 9223372036854775807");
			check("huge over-balance withdraw refused, balance untouched", bal(a) == before);
			as(a, "withdraw " + (before + 1));
			check("just-over-balance withdraw refused", bal(a) == before);
			check("no coins materialized", coinValue(a) == 5); // the 5 from stage 258
		});
		stages.put(262, () -> {
			// config validation: impossible admin values must be replaced by
			// defaults with a loud log, never used silently. The live config
			// is swapped, loaded, checked and restored inside one tick.
			try {
				java.nio.file.Path cfg = dev.easyeconomy.config.ConfigManager.file();
				String good = Files.readString(cfg);
				try {
					Files.writeString(cfg, "{ \"maxBalance\": -5, \"startingBalance\": 999999999999,"
							+ " \"minPayAmount\": 0, \"autosaveMinutes\": 0, \"maxListingsPerPlayer\": 0,"
							+ " \"packServer\": { \"port\": 70000 } }");
					dev.easyeconomy.config.ConfigManager.load();
					var c = dev.easyeconomy.config.ConfigManager.get();
					check("bad maxBalance clamped", c.maxBalance == 1_000_000_000L);
					check("bad startingBalance clamped", c.startingBalance == 50);
					check("bad minPayAmount clamped", c.minPayAmount == 1);
					check("bad autosaveMinutes clamped", c.autosaveMinutes == 5);
					check("bad maxListings clamped", c.maxListingsPerPlayer == 10);
					check("bad pack port clamped", c.packServer.port == 8180);
				} finally {
					Files.writeString(cfg, good);
					dev.easyeconomy.config.ConfigManager.load();
				}
				check("config restored", dev.easyeconomy.config.ConfigManager.get().maxBalance == 1_000_000_000L);
			} catch (Exception e) {
				throw new IllegalStateException("config validation stage failed", e);
			}
		});
		stages.put(264, () -> {
			// the pack endpoint end to end: one file only, rate-limited, and
			// the rate-limit map stays bounded no matter how many source
			// addresses show up
			dev.easyeconomy.pack.PackServer packs = new dev.easyeconomy.pack.PackServer();
			try {
				java.nio.file.Path zip = EasyEconomyMod.economy().dataDir().resolve("selftest-pack.zip");
				Files.write(zip, new byte[64]);
				var cfg = new dev.easyeconomy.config.EeConfig.PackServer();
				cfg.bind = "127.0.0.1";
				cfg.port = 28180;
				packs.start(cfg, zip);
				String base = "http://127.0.0.1:28180";
				check("pack first get 200", httpGet(base + dev.easyeconomy.pack.PackServer.PACK_PATH, "GET") == 200);
				check("pack second get 429", httpGet(base + dev.easyeconomy.pack.PackServer.PACK_PATH, "GET") == 429);
				check("pack wrong path 404", httpGet(base + "/nope", "GET") == 404);
				check("pack post 405", httpGet(base + dev.easyeconomy.pack.PackServer.PACK_PATH, "POST") == 405);
				java.lang.reflect.Field f = dev.easyeconomy.pack.PackServer.class.getDeclaredField("lastServed");
				f.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<String, Long> seen = (Map<String, Long>) f.get(packs);
				long now = System.currentTimeMillis();
				synchronized (seen) {
					seen.clear();
					for (int i = 0; i < 4096; i++) {
						seen.put("10." + (i / 65536) + "." + ((i / 256) % 256) + "." + (i % 256), now);
					}
				}
				check("rate map full still 429", httpGet(base + dev.easyeconomy.pack.PackServer.PACK_PATH, "GET") == 429);
			} catch (Exception e) {
				throw new IllegalStateException("pack server stage failed", e);
			} finally {
				packs.stop();
			}
		});
		stages.put(263, () -> {
			// a malformed accounts.json row must be skipped, never wipe the
			// whole file: bad ids, null balances, negatives and over-max
			// values are dropped one by one with a warning
			try {
				EconomyService eco = EasyEconomyMod.economy();
				check("accounts flush before hostile load", eco.save());
				java.nio.file.Path accounts = eco.dataDir().resolve("accounts.json");
				String good = Files.readString(accounts);
				final long balA = bal(a);
				final long balB = bal(b);
				String hostile = "{\n"
						+ "  \"" + a.getUUID() + "\": " + balA + ",\n"
						+ "  \"" + b.getUUID() + "\": " + balB + ",\n"
						+ "  \"not-a-uuid\": 5,\n"
						+ "  \"" + UUID.randomUUID() + "\": null,\n"
						+ "  \"" + UUID.randomUUID() + "\": -10,\n"
						+ "  \"" + UUID.randomUUID() + "\": 999999999999999999\n"
						+ "}\n";
				Files.writeString(accounts, hostile);
				eco.load();
				check("hostile accounts: A intact", bal(a) == balA);
				check("hostile accounts: B intact", bal(b) == balB);
				check("hostile accounts: bad rows skipped", eco.core().snapshot().size() == 2);
				Files.writeString(accounts, good);
				eco.load();
				check("accounts restored", eco.core().snapshot().size() >= 2 && bal(a) == balA);
				// an unreadable file is quarantined (renamed aside, never
				// overwritten) so the next autosave cannot destroy it
				Files.writeString(accounts, "{ this is not json ");
				eco.load();
				check("garbage load keeps current state", bal(a) == balA);
				final boolean[] quarantined = { false };
				try (var stream = Files.list(eco.dataDir())) {
					quarantined[0] = stream.anyMatch(p -> p.getFileName().toString().startsWith("accounts.json.corrupt-"));
				}
				check("corrupt accounts file quarantined", quarantined[0]);
				check("original accounts file moved away", !Files.exists(accounts));
				Files.writeString(accounts, good);
				eco.load();
				check("accounts restored after quarantine", bal(a) == balA && eco.core().snapshot().size() >= 2);
			} catch (Exception e) {
				throw new IllegalStateException("hostile accounts stage failed", e);
			}
		});
		stages.put(265, () -> {
			EconomyService eco = EasyEconomyMod.economy();
			check("accounts flush", eco.save());
			EasyEconomyMod.shop().saveLimits();
			EasyEconomyMod.challenges().saveProgress();
			try {
				Properties props = new Properties();
				props.setProperty("uuidA", a.getUUID().toString());
				props.setProperty("uuidB", b.getUUID().toString());
				props.setProperty("balA", Long.toString(bal(a)));
				props.setProperty("balB", Long.toString(bal(b)));
				Path file = eco.dataDir().resolve("selftest.properties");
				Files.createDirectories(file.getParent());
				try (OutputStream out = Files.newOutputStream(file)) {
					props.store(out, "easyeconomy selftest");
				}
				EasyEconomyMod.LOGGER.info("SELFTEST RUN DONE balA={} balB={}", bal(a), bal(b));
			} catch (Exception e) {
				throw new IllegalStateException("failed to write expectations", e);
			}
			server.halt(false);
		});
	}

	// ---------- verify phase ----------

	private static void planVerify() {
		stages.put(20, () -> {
			EconomyService eco = EasyEconomyMod.economy();
			try (InputStream in = Files.newInputStream(eco.dataDir().resolve("selftest.properties"))) {
				Properties props = new Properties();
				props.load(in);
				UUID uuidA = UUID.fromString(props.getProperty("uuidA"));
				UUID uuidB = UUID.fromString(props.getProperty("uuidB"));
				long balA = Long.parseLong(props.getProperty("balA"));
				long balB = Long.parseLong(props.getProperty("balB"));
				check("balance A survived restart", eco.core().balance(uuidA) == balA);
				check("balance B survived restart", eco.core().balance(uuidB) == balB);
				var netherite = EasyEconomyMod.shop().config().categories.get(0).items.get(1);
				check("buy limit survived restart", EasyEconomyMod.shop().limitRemaining(uuidA, netherite) == 0);
				check("challenge state survived restart", EasyEconomyMod.challenges().isDone(uuidB, "zombie-slayer"));
				check("challenge cooldown survived restart", EasyEconomyMod.challenges().cooldownLeft(uuidB, "wither-skull") > 0);
				check("escrow survived restart", EasyEconomyMod.playerShop().all().isEmpty());
				EasyEconomyMod.LOGGER.info("SELFTEST VERIFY DONE");
			} catch (Exception e) {
				throw new IllegalStateException("verify phase failed", e);
			}
			server.halt(false);
		});
	}

	// ---------- helpers ----------

	private static ServerPlayer fakePlayer(String name) {
		GameProfile profile = new GameProfile(UUID.randomUUID(), name);
		ServerPlayer player = new ServerPlayer(server, server.overworld(), profile,
				new ClientInformation("en_us", 12, ChatVisiblity.FULL, true, 127,
						HumanoidArm.RIGHT, false, false, ParticleStatus.ALL));
		server.getPlayerList().placeNewPlayer(new FakeConnection(), player,
				CommonListenerCookie.createInitial(profile, false));
		return player;
	}

	private static long bal(ServerPlayer player) {
		return EasyEconomyMod.economy().core().balance(player.getUUID());
	}

	private static void console(String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private static void as(ServerPlayer player, String command) {
		server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
	}

	private static void click(ServerPlayer player, int slot, ContainerInput input) {
		player.containerMenu.clicked(slot, 0, input, player);
	}

	private static long coinValue(ServerPlayer player) {
		long total = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack s = player.getInventory().getItem(i);
			long d = CurrencyItems.denominationOf(s);
			if (d > 0) {
				total += d * s.getCount();
			}
		}
		return total;
	}

	private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
		int total = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack s = player.getInventory().getItem(i);
			if (s.is(item)) {
				total += s.getCount();
			}
		}
		return total;
	}

	private static int httpGet(String url, String method) throws Exception {
		java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URI(url).toURL().openConnection();
		conn.setRequestMethod(method);
		conn.setConnectTimeout(2000);
		conn.setReadTimeout(2000);
		try {
			return conn.getResponseCode();
		} finally {
			conn.disconnect();
		}
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			EasyEconomyMod.LOGGER.info("selftest ok: {}", name);
		} else {
			EasyEconomyMod.LOGGER.error("SELFTEST FAIL: {}", name);
			failed = true;
			server.halt(false);
			// halt is asynchronous; abort the stage so later steps in it do
			// not keep running (and keep logging misleading oks) against a
			// server that is already going down
			throw new SelfTestFailure(name);
		}
	}
}
