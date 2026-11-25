package dev.easyeconomy.challenge;

import dev.easyeconomy.economy.EconomyService;
import dev.easyeconomy.gui.MenuGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;

// /challenges: one button per challenge with live progress in the lore.
public final class ChallengesGui extends MenuGui {
	private final ChallengeService service;
	private final EconomyService economy;

	private ChallengesGui(ServerPlayer player, ChallengeService service, EconomyService economy) {
		super(player, rowsFor(service.config().challenges.size()));
		this.service = service;
		this.economy = economy;
		fill();
	}

	public static void open(ServerPlayer player, ChallengeService service, EconomyService economy) {
		new ChallengesGui(player, service, economy).open();
	}

	private static int rowsFor(int entries) {
		return Math.min(6, 1 + (Math.max(1, entries) + 8) / 9);
	}

	@Override
	protected Component title() {
		return styledTitle("Challenges \u00b7 " + economy.formatCompact(economy.core().balance(player.getUUID())));
	}

	private void fill() {
		List<ChallengeConfig.Challenge> challenges = service.config().challenges;
		for (int i = 0; i < challenges.size(); i++) {
			ChallengeConfig.Challenge c = challenges.get(i);
			List<String> lore = new ArrayList<>();
			if (!c.description.isEmpty()) {
				lore.add(c.description);
			}
			lore.add("Reward: " + economy.format(c.reward) + ".");
			long pending = service.pendingOf(player.getUUID(), c.id);
			if (pending > 0) {
				lore.add("Payment pending - make room in your balance.");
			}
			if (service.isDone(player.getUUID(), c.id) && !c.repeatable) {
				lore.add("Completed.");
			} else {
				long cooldown = service.cooldownLeft(player.getUUID(), c.id);
				if (cooldown > 0) {
					lore.add("Again in " + (cooldown / 3600) + "h " + (cooldown % 3600) / 60 + "m.");
				} else if (!"advancement".equals(c.type)) {
					int progress = Math.max(0, service.progressOf(player.getUUID(), c.id));
					lore.add("Progress: " + progress + "/" + service.goal(c) + ".");
				}
			}
			set(i, MenuGui.display(service.iconOf(c), c.name, lore));
		}
	}

	@Override
	protected void onClick(int slot, int button, ContainerInput input) {
		// reopen so the progress lore and the title balance both refresh
		open(player, service, economy);
	}
}
