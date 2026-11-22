package dev.easyeconomy.challenge;

import dev.easyeconomy.EasyEconomyMod;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.triggers.ImpossibleTrigger;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Shows a challenge completion as a vanilla advancement toast. A
// throwaway client-side advancement is sent already done (that is what
// makes the client pop the toast), then removed a few ticks later so the
// advancement screen never keeps it. Nothing here touches the player's
// real advancement data.
public final class ChallengeToast {
	private static final String CRITERION = "done";
	private static final int REMOVE_AFTER_TICKS = 10;

	private static final class Pending {
		final UUID player;
		final Identifier id;
		int ticksLeft = REMOVE_AFTER_TICKS;

		Pending(UUID player, Identifier id) {
			this.player = player;
			this.id = id;
		}
	}

	private static final List<Pending> pendingRemovals = new ArrayList<>();

	private ChallengeToast() {
	}

	public static void show(ServerPlayer player, ChallengeConfig.Challenge challenge, Item icon, String rewardText) {
		Identifier id = Identifier.fromNamespaceAndPath(EasyEconomyMod.MOD_ID, "toast/" + challenge.id);
		DisplayInfo display = new DisplayInfo(
				ItemStackTemplate.fromNonEmptyStack(new ItemStack(icon)),
				Component.literal(challenge.name),
				Component.literal("Challenge complete. " + rewardText),
				Optional.empty(),
				AdvancementType.TASK,
				true, false, false);
		Map<String, Criterion<?>> criteria = new HashMap<>();
		criteria.put(CRITERION, new Criterion<>(CriteriaTriggers.IMPOSSIBLE, new ImpossibleTrigger.TriggerInstance()));
		Advancement advancement = new Advancement(
				Optional.empty(), Optional.of(display), AdvancementRewards.EMPTY,
				criteria, AdvancementRequirements.allOf(List.of(CRITERION)), false, Optional.empty());
		AdvancementHolder holder = new AdvancementHolder(id, advancement);
		AdvancementProgress progress = new AdvancementProgress();
		progress.update(advancement.requirements());
		progress.grantProgress(CRITERION);
		// a still-pending removal for this id would wipe the new toast
		pendingRemovals.removeIf(p -> p.player.equals(player.getUUID()) && p.id.equals(id));
		player.connection.send(new ClientboundUpdateAdvancementsPacket(
				false,
				List.of(new ClientboundUpdateAdvancementsPacket.PositionedAdvancement(holder, 0.0f, 0.0f)),
				Set.of(),
				Map.of(id, progress),
				false));
		pendingRemovals.add(new Pending(player.getUUID(), id));
	}

	// Called once per server tick; removes sent toasts from the client
	// after they have had time to pop.
	public static void tick(MinecraftServer server) {
		if (pendingRemovals.isEmpty()) {
			return;
		}
		Iterator<Pending> it = pendingRemovals.iterator();
		while (it.hasNext()) {
			Pending pending = it.next();
			if (--pending.ticksLeft > 0) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(pending.player);
			if (player != null) {
				player.connection.send(new ClientboundUpdateAdvancementsPacket(
						false, List.of(), Set.of(pending.id), Map.of(), false));
			}
			it.remove();
		}
	}
}
