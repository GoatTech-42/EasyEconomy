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

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, EconomyService eco, ShopService shop, PlayerShopService playerShop) {
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

	private static int withdraw(CommandSourceStack source, long amount, EconomyService eco) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		EconomyCore.Result result = eco.core().withdraw(player.getUUID(), amount, "withdraw");
		if (result != EconomyCore.Result.OK) {
			if (result == EconomyCore.Result.INSUFFICIENT_FUNDS) {
				source.sendFailure(Component.literal("not enough " + ConfigManager.get().currencyPlural));
			} else {
				source.sendFailure(Component.literal("bad amount"));
			}
			return 0;
		}
		eco.changed();
		for (java.util.Map.Entry<Long, Long> e : CurrencyMath.split(amount, CurrencyItems.denominations()).entrySet()) {
			long left = e.getValue();
			while (left > 0) {
				int count = (int) Math.min(64, left);
				left -= count;
				ItemStack stack = CurrencyItems.coin(e.getKey(), count);
				player.getInventory().add(stack);
				if (!stack.isEmpty()) {
					player.spawnAtLocation(source.getLevel(), stack);
				}
			}
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
		EconomyCore.Result result = eco.core().deposit(player.getUUID(), value, "deposit");
		if (result == EconomyCore.Result.OVER_MAX_BALANCE) {
			source.sendFailure(Component.literal("that would put you over the max balance"));
			return 0;
		}
		if (result != EconomyCore.Result.OK) {
			source.sendFailure(Component.literal("bad amount"));
			return 0;
		}
		eco.changed();
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
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
		EconomyCore.Result result = eco.core().deposit(player.getUUID(), total, "deposit-all");
		if (result == EconomyCore.Result.OVER_MAX_BALANCE) {
			source.sendFailure(Component.literal("that would put you over the max balance"));
			return 0;
		}
		if (result != EconomyCore.Result.OK) {
			source.sendFailure(Component.literal("bad amount"));
			return 0;
		}
		eco.changed();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (CurrencyItems.denominationOf(inv.getItem(i)) > 0) {
				inv.setItem(i, ItemStack.EMPTY);
			}
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
			default -> source.sendFailure(Component.literal("can't list that"));
		}
		return 0;
	}

	private static int sell(CommandSourceStack source, EconomyService eco, ShopService shop) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ItemStack held = player.getMainHandItem();
		ShopService.SellOutcome outcome = shop.sell(player, held);
		switch (outcome.result()) {
			case OK -> {
				player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				source.sendSuccess(() -> Component.literal("sold " + outcome.count() + " for " + eco.format(outcome.value())), false);
				return 1;
			}
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
