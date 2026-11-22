package dev.easyeconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.easyeconomy.config.ConfigManager;
import dev.easyeconomy.config.EeConfig;
import dev.easyeconomy.economy.EconomyCore;
import dev.easyeconomy.economy.EconomyService;
import dev.easyeconomy.currency.CurrencyItems;
import dev.easyeconomy.currency.CurrencyMath;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.shop.ShopGui;
import dev.easyeconomy.shop.ShopService;
import dev.easyeconomy.pshop.PlayerShopGui;
import dev.easyeconomy.pshop.PlayerShopService;
import dev.easyeconomy.challenge.ChallengeService;
import dev.easyeconomy.challenge.ChallengesGui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class EasyEconomyCommands {

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, EconomyService eco, ShopService shop, PlayerShopService playerShop, ChallengeService challenges) {
		dispatcher.register(Commands.literal("balance")
				.executes(ctx -> showBalance(ctx.getSource(), ctx.getSource().getPlayerOrException(), eco))
				.then(balanceOtherChild(eco)));
		dispatcher.register(Commands.literal("bal")
				.executes(ctx -> showBalance(ctx.getSource(), ctx.getSource().getPlayerOrException(), eco))
				.then(balanceOtherChild(eco)));
		dispatcher.register(Commands.literal("pay")
				.then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("amount", LongArgumentType.longArg(1))
								.executes(ctx -> pay(ctx, eco)))));
		dispatcher.register(Commands.literal("eco")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("give")
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("amount", LongArgumentType.longArg(1))
										.executes(ctx -> admin(ctx, eco, AdminOp.GIVE)))))
				.then(Commands.literal("take")
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("amount", LongArgumentType.longArg(1))
										.executes(ctx -> admin(ctx, eco, AdminOp.TAKE)))))
				.then(Commands.literal("set")
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("amount", LongArgumentType.longArg(0))
										.executes(ctx -> admin(ctx, eco, AdminOp.SET))))));
		dispatcher.register(Commands.literal("withdraw")
				.then(Commands.argument("amount", LongArgumentType.longArg(1))
						.executes(ctx -> withdraw(ctx.getSource(), LongArgumentType.getLong(ctx, "amount"), eco))));
		dispatcher.register(Commands.literal("deposit")
				.executes(ctx -> depositHand(ctx.getSource(), eco))
				.then(Commands.literal("all")
						.executes(ctx -> depositAll(ctx.getSource(), eco))));
		dispatcher.register(Commands.literal("shop")
				.executes(ctx -> {
					ShopGui.openRoot(ctx.getSource().getPlayerOrException(), shop, eco);
					return 1;
				}));
		dispatcher.register(Commands.literal("sell")
				.executes(ctx -> sell(ctx.getSource(), eco, shop)));
		registerPlayerShop(dispatcher, "playershop", eco, playerShop);
		dispatcher.register(Commands.literal("challenges")
				.executes(ctx -> {
					ChallengesGui.open(ctx.getSource().getPlayerOrException(), challenges, eco);
					return 1;
				}));
		registerPlayerShop(dispatcher, "ps", eco, playerShop);
		dispatcher.register(Commands.literal("easyeconomy")
				.executes(ctx -> info(ctx.getSource(), eco))
				.then(Commands.literal("reload")
						.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
						.executes(ctx -> reload(ctx.getSource(), eco))));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> balanceOtherChild(EconomyService eco) {
		return Commands.argument("player", EntityArgument.player())
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> showBalance(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), eco));
	}

	private static int showBalance(CommandSourceStack source, ServerPlayer who, EconomyService eco) {
		long balance = eco.core().balance(who.getUUID());
		if (source.getEntity() == who) {
			source.sendSuccess(() -> Component.literal("balance: " + eco.format(balance)), false);
		} else {
			source.sendSuccess(() -> Component.literal(who.getName().getString() + "'s balance: " + eco.format(balance)), false);
		}
		return 1;
	}

	private static int pay(CommandContext<CommandSourceStack> ctx, EconomyService eco) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer from = source.getPlayerOrException();
		ServerPlayer to = EntityArgument.getPlayer(ctx, "player");
		long amount = LongArgumentType.getLong(ctx, "amount");
		EeConfig config = ConfigManager.get();
		if (amount < config.minPayAmount) {
			source.sendFailure(Component.literal("the smallest payment is " + eco.format(config.minPayAmount)));
			return 0;
		}
		EconomyCore.Result result = eco.core().transfer(from.getUUID(), to.getUUID(), amount, "pay");
		switch (result) {
			case OK -> {
				eco.changed();
				source.sendSuccess(() -> Component.literal("paid " + eco.format(amount) + " to " + to.getName().getString()), false);
				to.sendSystemMessage(Component.literal(from.getName().getString() + " paid you " + eco.format(amount)));
				return 1;
			}
			case INSUFFICIENT_FUNDS -> source.sendFailure(Component.literal("not enough " + config.currencyPlural));
			case OVER_MAX_BALANCE -> source.sendFailure(Component.literal("that would put " + to.getName().getString() + " over the max balance"));
			case SELF_TRANSFER -> source.sendFailure(Component.literal("you can't pay yourself"));
			default -> source.sendFailure(Component.literal("bad amount"));
		}
		return 0;
	}

	// Force every player's data to disk so an inventory change made by a
	// money command cannot be rolled back by a crash. Vanilla saves on its
	// own schedule; without this a crash can turn removal-then-credit into
	// a loss, or debit-then-give into a rollback dupe. Best-effort: a failed
	// save is logged, the in-memory state stays consistent.
	// returns true only when the save is verified on disk by read-back;
	// vanilla's save swallows write failures, so a void call proves nothing
	private static boolean savePlayers(CommandSourceStack source, ServerPlayer player) {
		return dev.easyeconomy.util.PlayerSaves.saveAllVerified(source.getServer(), player);
	}

	// Flush balances now, not at the next autosave, so the money side of an
	// item trade is durable before the item side exists (or right after the
	// item side is durable).
	private static boolean saveMoney(EconomyService eco) {
		try {
			if (eco.save()) {
				return true;
			}
		} catch (Exception e) {
			EasyEconomyMod.LOGGER.error("forced balance save failed", e);
		}
		EasyEconomyMod.LOGGER.error("forced balance save failed");
		return false;
	}

	// Largest single /withdraw. Bigger holdings move in batches; the cap
	// keeps a request from ever driving work proportional to its size.
	private static final long MAX_WITHDRAW = 100_000_000L;

	private static int withdraw(CommandSourceStack source, long amount, EconomyService eco) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		// affordability and the cap come first: nothing proportional to the
		// requested amount may be allocated before both pass
		if (eco.core().balance(player.getUUID()) < amount) {
			source.sendFailure(Component.literal("not enough " + ConfigManager.get().currencyPlural));
			return 0;
		}
		if (amount > MAX_WITHDRAW) {
			source.sendFailure(Component.literal("too much at once; withdraw at most " + eco.format(MAX_WITHDRAW)));
			return 0;
		}
		// one stack per denomination (bounded by the denomination count),
		// and make sure the coins can actually be carried: chunks are only
		// materialized once capacity is proven, and an overflow dropped at
		// the feet could never be recalled safely if the inventory save
		// afterwards cannot be verified
		java.util.List<ItemStack> plan = new java.util.ArrayList<>();
		for (java.util.Map.Entry<Long, Long> e : CurrencyMath.split(amount, CurrencyItems.denominations()).entrySet()) {
			plan.add(CurrencyItems.coin(e.getKey(), e.getValue().intValue()));
		}
		if (!dev.easyeconomy.util.Inventories.canAbsorb(player.getInventory(), plan)) {
			source.sendFailure(Component.literal("not enough inventory space"));
			return 0;
		}
		java.util.List<ItemStack> coins = new java.util.ArrayList<>();
		for (ItemStack wanted : plan) {
			int toMake = wanted.getCount();
			while (toMake > 0) {
				int count = Math.min(64, toMake);
				toMake -= count;
				coins.add(wanted.copyWithCount(count));
			}
		}
		EconomyCore.Result result = eco.core().withdraw(player.getUUID(), amount, "withdraw");
		if (result != EconomyCore.Result.OK) {
			if (result == EconomyCore.Result.INSUFFICIENT_FUNDS) {
				source.sendFailure(Component.literal("not enough " + ConfigManager.get().currencyPlural));
			} else {
				source.sendFailure(Component.literal("bad amount"));
			}
			return 0;
		}
		// the debit must reach disk before the coins do: a crash may then
		// lose coins, but can never leave both the balance and the coins
		if (!saveMoney(eco)) {
			eco.core().deposit(player.getUUID(), amount, "withdraw-unwind");
			saveMoney(eco);
			source.sendFailure(Component.literal("could not save your balance; nothing withdrawn"));
			return 0;
		}
		boolean voided = false;
		for (ItemStack stack : coins) {
			player.getInventory().add(stack);
			if (!stack.isEmpty()) {
				// capacity was checked before the debit, so a remainder here
				// means the inventory changed in between. never spill it into a
				// dropped entity; void it, keep the withdrawal, and say so loudly
				EasyEconomyMod.LOGGER.error("withdraw overflow: voided {} coins for {}; the withdrawal stands, an admin should refund",
						CurrencyItems.denominationOf(stack) * stack.getCount(), player.getUUID());
				stack.setCount(0);
				voided = true;
			}
		}
		if (!savePlayers(source, player)) {
			// the coins may already be durable, so the withdrawal stands:
			// refunding against possibly-saved coins would duplicate them.
			// a crash may lose the coins, never duplicate them
			EasyEconomyMod.LOGGER.error("withdraw: coin save unverified for {}; the withdrawal stands, a crash now may lose the coins",
					player.getUUID());
			if (voided) {
				source.sendSuccess(() -> Component.literal("withdrew " + eco.format(amount)
						+ ", but some coins were lost in delivery and your inventory could not be saved safely; a crash may lose the rest - an admin has been notified"), false);
				return 1;
			}
			source.sendSuccess(() -> Component.literal("withdrew " + eco.format(amount)
					+ ", but your inventory could not be saved safely; a crash may lose the coins - tell an admin if they vanish"), false);
			return 1;
		}
		if (voided) {
			source.sendSuccess(() -> Component.literal("withdrew " + eco.format(amount)
					+ ", but some coins could not be carried; an admin has been notified"), false);
			return 1;
		}
		source.sendSuccess(() -> Component.literal("withdrew " + eco.format(amount)), false);
		return 1;
	}

	private static int depositHand(CommandSourceStack source, EconomyService eco) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ItemStack held = player.getMainHandItem();
		long denom = CurrencyItems.denominationOf(held);
		if (denom <= 0) {
			source.sendFailure(Component.literal("hold a currency item to deposit it"));
			return 0;
		}
		long value;
		try {
			value = Math.multiplyExact(denom, held.getCount());
		} catch (ArithmeticException e) {
			source.sendFailure(Component.literal("bad amount"));
			return 0;
		}
		// remove the coins first and make that durable, then credit: a crash
		// may lose the coins but can never leave both the coins and the credit
		ItemStack taken = held.copy();
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		if (!savePlayers(source, player)) {
			// the cleared hand is not provably on disk; put the coins back
			// and stop, so a crash cannot roll back into a dupe
			player.setItemInHand(InteractionHand.MAIN_HAND, taken);
			savePlayers(source, player);
			source.sendFailure(Component.literal("could not save your inventory; nothing deposited"));
			return 0;
		}
		EconomyCore.Result result = eco.core().deposit(player.getUUID(), value, "deposit");
		if (result != EconomyCore.Result.OK) {
			player.getInventory().add(taken);
			if (!taken.isEmpty()) {
				player.spawnAtLocation(source.getLevel(), taken);
			}
			if (!savePlayers(source, player)) {
				EasyEconomyMod.LOGGER.error("deposit abort: coins returned to {} but the save is unverified", player.getUUID());
			}
			source.sendFailure(Component.literal(result == EconomyCore.Result.OVER_MAX_BALANCE
					? "that would put you over the max balance" : "bad amount"));
			return 0;
		}
		if (!saveMoney(eco)) {
			eco.core().withdraw(player.getUUID(), value, "deposit-unwind");
			player.getInventory().add(taken);
			if (!taken.isEmpty()) {
				player.spawnAtLocation(source.getLevel(), taken);
			}
			if (!savePlayers(source, player)) {
				EasyEconomyMod.LOGGER.error("deposit unwind: coins returned to {} but the save is unverified", player.getUUID());
			}
			source.sendFailure(Component.literal("could not save your balance; coins returned"));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("deposited " + eco.format(value)), false);
		return 1;
	}

	private static int depositAll(CommandSourceStack source, EconomyService eco) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Inventory inv = player.getInventory();
		long total = 0;
		try {
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack stack = inv.getItem(i);
				long denom = CurrencyItems.denominationOf(stack);
				if (denom > 0) {
					total = Math.addExact(total, Math.multiplyExact(denom, stack.getCount()));
				}
			}
		} catch (ArithmeticException e) {
			source.sendFailure(Component.literal("bad amount"));
			return 0;
		}
		if (total == 0) {
			source.sendFailure(Component.literal("no currency items in your inventory"));
			return 0;
		}
		// same rule as /deposit: coins leave first, credit follows, and a
		// rejected deposit returns every stack exactly as it was
		java.util.List<ItemStack> takenAll = new java.util.ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (CurrencyItems.denominationOf(inv.getItem(i)) > 0) {
				takenAll.add(inv.getItem(i).copy());
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
		if (!savePlayers(source, player)) {
			// the cleared inventory is not provably on disk; put the coins
			// back and stop, so a crash cannot roll back into a dupe
			for (ItemStack back : takenAll) {
				player.getInventory().add(back);
				if (!back.isEmpty()) {
					player.spawnAtLocation(source.getLevel(), back);
				}
			}
			savePlayers(source, player);
			source.sendFailure(Component.literal("could not save your inventory; nothing deposited"));
			return 0;
		}
		EconomyCore.Result result = eco.core().deposit(player.getUUID(), total, "deposit-all");
		if (result != EconomyCore.Result.OK) {
			for (ItemStack back : takenAll) {
				player.getInventory().add(back);
				if (!back.isEmpty()) {
					player.spawnAtLocation(source.getLevel(), back);
				}
			}
			if (!savePlayers(source, player)) {
				EasyEconomyMod.LOGGER.error("deposit-all abort: coins returned to {} but the save is unverified", player.getUUID());
			}
			source.sendFailure(Component.literal(result == EconomyCore.Result.OVER_MAX_BALANCE
					? "that would put you over the max balance" : "bad amount"));
			return 0;
		}
		if (!saveMoney(eco)) {
			eco.core().withdraw(player.getUUID(), total, "deposit-all-unwind");
			for (ItemStack back : takenAll) {
				player.getInventory().add(back);
				if (!back.isEmpty()) {
					player.spawnAtLocation(source.getLevel(), back);
				}
			}
			if (!savePlayers(source, player)) {
				EasyEconomyMod.LOGGER.error("deposit-all unwind: coins returned to {} but the save is unverified", player.getUUID());
			}
			source.sendFailure(Component.literal("could not save your balance; coins returned"));
			return 0;
		}
		long deposited = total;
		source.sendSuccess(() -> Component.literal("deposited " + eco.format(deposited)), false);
		return 1;
	}

	private static void registerPlayerShop(CommandDispatcher<CommandSourceStack> dispatcher, String name, EconomyService eco, PlayerShopService playerShop) {
		dispatcher.register(Commands.literal(name)
				.executes(ctx -> {
					PlayerShopGui.open(ctx.getSource().getPlayerOrException(), playerShop, eco);
					return 1;
				})
				.then(Commands.literal("sell")
						.then(Commands.argument("price", LongArgumentType.longArg(1))
								.executes(ctx -> listForSale(ctx.getSource(), LongArgumentType.getLong(ctx, "price"), playerShop)))));
	}

	private static int listForSale(CommandSourceStack source, long price, PlayerShopService playerShop) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PlayerShopService.ListResult result = playerShop.list(player, price);
		switch (result) {
			case OK -> {
				source.sendSuccess(() -> Component.literal("listed for " + EasyEconomyMod.economy().format(price)), false);
				return 1;
			}
			case NO_ITEM -> source.sendFailure(Component.literal("hold the item you want to sell"));
			case IS_CURRENCY -> source.sendFailure(Component.literal("currency items can't be listed"));
			case BAD_PRICE -> source.sendFailure(Component.literal("bad price"));
			case TOO_MANY -> source.sendFailure(Component.literal("you have too many listings already"));
			case FAILED -> source.sendFailure(Component.literal("listing failed safely - your item was returned"));
			default -> source.sendFailure(Component.literal("can't list that"));
		}
		return 0;
	}

	private static int sell(CommandSourceStack source, EconomyService eco, ShopService shop) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) {
			source.sendFailure(Component.literal("hold an item to sell it"));
			return 0;
		}
		// item leaves the hand first and that removal is forced to disk,
		// then the payout: a crash can lose the sale, never duplicate it
		ItemStack taken = held.copy();
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		if (!savePlayers(source, player)) {
			player.setItemInHand(InteractionHand.MAIN_HAND, taken);
			savePlayers(source, player);
			source.sendFailure(Component.literal("could not save your inventory; nothing sold"));
			return 0;
		}
		ShopService.SellOutcome outcome = shop.sell(player, taken);
		if (outcome.result() == ShopService.SellResult.OK && !saveMoney(eco)) {
			eco.core().withdraw(player.getUUID(), outcome.value(), "sell-unwind");
			outcome = new ShopService.SellOutcome(ShopService.SellResult.FAILED, 0, 0);
		}
		if (outcome.result() != ShopService.SellResult.OK) {
			player.getInventory().add(taken);
			if (!taken.isEmpty()) {
				player.spawnAtLocation(source.getLevel(), taken);
			}
			if (!savePlayers(source, player)) {
				EasyEconomyMod.LOGGER.error("sell abort: item returned to {} but the save is unverified", player.getUUID());
			}
		}
		switch (outcome.result()) {
			case OK -> {
				long soldCount = outcome.count();
				long soldValue = outcome.value();
				source.sendSuccess(() -> Component.literal("sold " + soldCount + " for " + eco.format(soldValue)), false);
				return 1;
			}
			case FAILED -> source.sendFailure(Component.literal("could not save your balance; item returned"));
			case NO_ITEM -> source.sendFailure(Component.literal("hold an item to sell it"));
			case IS_CURRENCY -> source.sendFailure(Component.literal("that's currency - use /deposit"));
			case NO_PRICE -> source.sendFailure(Component.literal("the server doesn't buy that"));
			case OVER_MAX -> source.sendFailure(Component.literal("that would put you over the max balance"));
			default -> source.sendFailure(Component.literal("can't sell that"));
		}
		return 0;
	}

	private enum AdminOp { GIVE, TAKE, SET }

	private static int admin(CommandContext<CommandSourceStack> ctx, EconomyService eco, AdminOp op) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		long amount = LongArgumentType.getLong(ctx, "amount");
		String by = source.getEntity() == null ? "console" : source.getEntity().getName().getString();
		EconomyCore.Result result;
		String verb;
		switch (op) {
			case GIVE -> {
				result = eco.core().deposit(target.getUUID(), amount, "admin:" + by);
				verb = "gave";
			}
			case TAKE -> {
				result = eco.core().withdraw(target.getUUID(), amount, "admin:" + by);
				verb = "took";
			}
			default -> {
				result = eco.core().set(target.getUUID(), amount, "admin:" + by);
				verb = "set";
			}
		}
		if (result == EconomyCore.Result.OK) {
			eco.changed();
			long now = eco.core().balance(target.getUUID());
			String name = target.getName().getString();
			if (op == AdminOp.SET) {
				source.sendSuccess(() -> Component.literal("set " + name + "'s balance to " + eco.format(now)), true);
			} else if (op == AdminOp.GIVE) {
				source.sendSuccess(() -> Component.literal("gave " + eco.format(amount) + " to " + name + " (now " + eco.format(now) + ")"), true);
			} else {
				source.sendSuccess(() -> Component.literal("took " + eco.format(amount) + " from " + name + " (now " + eco.format(now) + ")"), true);
			}
			return 1;
		}
		switch (result) {
			case INSUFFICIENT_FUNDS -> source.sendFailure(Component.literal(target.getName().getString() + " only has " + eco.format(eco.core().balance(target.getUUID()))));
			case OVER_MAX_BALANCE -> source.sendFailure(Component.literal("that would put " + target.getName().getString() + " over the max balance"));
			default -> source.sendFailure(Component.literal("bad amount"));
		}
		return 0;
	}

	private static int info(CommandSourceStack source, EconomyService eco) {
		String version = FabricLoader.getInstance().getModContainer("easyeconomy")
				.map(m -> m.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
		int accounts = eco.core().snapshot().size();
		source.sendSuccess(() -> Component.literal("easyeconomy " + version + " - " + accounts + " accounts - data in " + eco.dataDir()), false);
		return 1;
	}

	private static int reload(CommandSourceStack source, EconomyService eco) {
		EasyEconomyMod.reloadAll();
		source.sendSuccess(() -> Component.literal("reloaded easyeconomy config"), true);
		return 1;
	}
}
