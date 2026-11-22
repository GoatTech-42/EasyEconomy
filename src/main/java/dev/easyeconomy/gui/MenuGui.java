package dev.easyeconomy.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

// Lean chest-menu gui base. The chest rows are buttons: clicks on them are
// intercepted and never move items in or out through any input path
// (pickup, shift, number key, offhand swap, drag, double-click gather).
// The player's own inventory rows below stay fully usable: items can be
// picked up, placed, swapped and shift-moved between the main inventory
// and the hotbar while the gui is open, they just can never enter the
// chest rows. After each click the menu is re-synced so the client drops
// any ghost items.
public abstract class MenuGui {
	protected final ServerPlayer player;
	protected final SimpleContainer container;
	protected final int rows;
	private ChestMenu menu;

	protected MenuGui(ServerPlayer player, int rows) {
		this.player = player;
		this.rows = Math.max(1, Math.min(6, rows));
		this.container = new SimpleContainer(this.rows * 9);
	}

	public void open() {
		MenuProvider provider = new SimpleMenuProvider((syncId, inv, p) -> {
			menu = new ChestMenu(typeFor(rows), syncId, inv, container, rows) {
				@Override
				public void clicked(int slot, int button, ContainerInput input, Player clicker) {
					int chestSlots = container.getContainerSize();
					if (slot >= 0 && slot < chestSlots) {
						// a button row: act on it, never move items
						onClick(slot, button, input);
						broadcastChanges();
						return;
					}
					// drag-painting and double-click gather reach across the
					// whole menu, chest rows included, so they stay blocked
					if (input == ContainerInput.QUICK_CRAFT || input == ContainerInput.PICKUP_ALL) {
						broadcastChanges();
						return;
					}
					// the player's own inventory: normal vanilla behavior,
					// shift-moves stay inside the player rows (see below)
					super.clicked(slot, button, input, clicker);
					broadcastChanges();
				}

				@Override
				public ItemStack quickMoveStack(Player mover, int index) {
					int chestSlots = container.getContainerSize();
					if (index < chestSlots || index >= slots.size()) {
						return ItemStack.EMPTY;
					}
					net.minecraft.world.inventory.Slot slot = slots.get(index);
					if (!slot.hasItem()) {
						return ItemStack.EMPTY;
					}
					ItemStack stack = slot.getItem();
					// rebalance inside the player inventory only: main rows
					// shift to the hotbar, hotbar rows shift to the main rows
					if (index < chestSlots + 27) {
						moveItemStackTo(stack, chestSlots + 27, chestSlots + 36, false);
					} else {
						moveItemStackTo(stack, chestSlots, chestSlots + 27, false);
					}
					if (stack.isEmpty()) {
						slot.set(ItemStack.EMPTY);
					} else {
						slot.setChanged();
					}
					return ItemStack.EMPTY;
				}

				@Override
				public void removed(Player closer) {
					// vanilla settlement first: a stack still on the cursor goes
					// back into the player's inventory (or drops at their feet),
					// then the gui's own close hook runs
					super.removed(closer);
					onClose();
				}
			};
			return menu;
		}, title());
		player.openMenu(provider);
	}

	protected abstract Component title();

	// Menu titles: gold and bold, optionally prefixed with the pack's coin
	// glyph when the server enforces the resource pack (config flag).
	protected static Component styledTitle(String text) {
		net.minecraft.network.chat.MutableComponent title = Component.literal(text)
				.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD).withBold(true));
		if (dev.easyeconomy.config.ConfigManager.get().guiTitleBadge) {
			return Component.literal("\uE000 ")
					.withStyle(style -> style.withFont(new net.minecraft.network.chat.FontDescription.Resource(
							net.minecraft.resources.Identifier.fromNamespaceAndPath("easyeconomy", "gui")))
							.withColor(ChatFormatting.WHITE))
					.append(title);
		}
		return title;
	}

	protected abstract void onClick(int slot, int button, ContainerInput input);

	protected void onClose() {
	}

	protected void set(int slot, ItemStack stack) {
		if (slot >= 0 && slot < container.getContainerSize()) {
			container.setItem(slot, stack);
		}
	}

	protected void refresh() {
		if (menu != null) {
			menu.broadcastChanges();
		}
	}

	public static ItemStack display(Item item, String name, List<String> lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME,
				Component.literal(name).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.WHITE)));
		if (!lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lore.stream()
					.map(line -> (Component) Component.literal(line)
							.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)))
					.toList()));
		}
		return stack;
	}

	private static MenuType<ChestMenu> typeFor(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}
}
