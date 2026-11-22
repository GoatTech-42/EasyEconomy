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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class EasyEconomyCommands {

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, EconomyService eco) {
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
		eco.reload();
		source.sendSuccess(() -> Component.literal("reloaded easyeconomy config"), true);
		return 1;
	}
}
