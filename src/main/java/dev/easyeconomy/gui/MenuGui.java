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
	// Title width budget. The pack's title bar runs x 0..175 with the coin
	// motif at x161; bold vanilla glyphs advance at most 7px from the x8
	// text start, so 21 characters always clear the motif.
	// Guis currently open, keyed by their viewer, so a balance change can
	// refresh the title (see tickRefresh). The map value (the gui) holds its
	// viewer strongly, so the weak keys are never actually collected; the
	// per-tick prune in tickRefresh is what removes entries once the menu is
	// no longer open. The map stays weak only as harmless belt-and-braces.
	private static final java.util.WeakHashMap<ServerPlayer, MenuGui> OPEN = new java.util.WeakHashMap<>();

	// Per-viewer tick of the last menu click, for the tickRefresh debounce.
	private static final java.util.WeakHashMap<ServerPlayer, Integer> LAST_CLICK = new java.util.WeakHashMap<>();
	private static int tick;

	// Ticks a balance refresh waits after the viewer's last click, so an
	// outside balance change never reopens the menu from under a click that
	// is already in flight (the reopen would recenter the cursor and, in the
	// player shop, shift sold listings before the click lands).
	private static final int CLICK_DEBOUNCE_TICKS = 10;

	protected final ServerPlayer player;
	protected final SimpleContainer container;
	protected final int rows;
	private ChestMenu menu;
	private long titleBalance;

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
					LAST_CLICK.put(MenuGui.this.player, tick);
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
					// then the gui deregisters and its own close hook runs
					super.removed(closer);
					closed();
				}
			};
			return menu;
		}, title());
		titleBalance = balanceForTitle();
		OPEN.put(player, this);
		player.openMenu(provider);
	}

	protected abstract Component title();

	// Builds base + " " + tail (tail is "· " + the compact balance) within
	// the title's pixel budget (TitleFit), cutting an over-long base (custom
	// category names from the config) by code points with an ellipsis so the
	// painted string never reaches the coin motif at x161, wide unicode
	// glyphs included.
	protected static String fitTitle(String base, String tail) {
		return TitleFit.fit(base, tail);
	}

	// Menus that show the balance in their title override these three.
	protected boolean hasBalanceTitle() {
		return false;
	}

	protected long balanceForTitle() {
		return 0;
	}

	// Reopen this exact view (same category, page, filter) so the title
	// repaints; there is no title-update packet for an open container.
	protected abstract void reopenSelf();

	// Every tick: any open balance-title menu whose viewer's balance moved
	// since the title was painted (a /pay received, seller proceeds, an eco
	// grant) is reopened so the number is never stale. Reopen-on-click covers
	// the menu's own actions; this covers everything else.
	public static void tickRefresh(dev.easyeconomy.economy.EconomyService economy) {
		tick++;
		if (OPEN.isEmpty()) {
			return;
		}
		for (java.util.Map.Entry<ServerPlayer, MenuGui> entry
				: new java.util.ArrayList<>(OPEN.entrySet())) {
			ServerPlayer viewer = entry.getKey();
			MenuGui gui = entry.getValue();
			if (viewer.containerMenu != gui.menu) {
				OPEN.remove(viewer, gui);
				continue;
			}
			if (!gui.hasBalanceTitle()
					|| economy.core().balance(viewer.getUUID()) == gui.titleBalance) {
				continue;
			}
			// a reopen settles a stack carried on the cursor back into the
			// inventory, so wait for a tick where the cursor is empty; a buy
			// or chest click always leaves it empty, so this only defers the
			// refresh while the viewer is mid-drag in their own rows
			if (!viewer.containerMenu.getCarried().isEmpty()) {
				continue;
			}
			// debounce: a click in the last few ticks means the viewer is about
			// to act on what they see; an outside balance change can wait a
			// half second rather than reopen the menu from under that click
			Integer lastClick = LAST_CLICK.get(viewer);
			if (lastClick != null && tick - lastClick < CLICK_DEBOUNCE_TICKS) {
				continue;
			}
			gui.reopenSelf();
		}
	}

	// Menu titles. When the gui theme is on (guiTheme in the config, or the
	// legacy guiTitleBadge flag) and the player is not on Bedrock, the title
	// paints the pack's full gui skin behind the menu: a shift glyph moves
	// the pen to the menu's left edge, the background glyph for this row
	// count draws the skin, and a second shift glyph returns the pen so the
	// title text lands where it always did. Without the pack (or for Bedrock
	// players through Geyser, whose client can't use Java fonts) the title
	// stays plain gold text and the vanilla menu look is untouched.
	protected Component styledTitle(String text) {
		if (themed()) {
			String frame = "\uE020" + (char) ('\uE010' + rows) + "\uE021";
			return Component.literal(frame)
					.withStyle(style -> style
							.withFont(new net.minecraft.network.chat.FontDescription.Resource(
									net.minecraft.resources.Identifier.fromNamespaceAndPath("easyeconomy", "gui")))
							.withColor(ChatFormatting.WHITE).withItalic(false))
					.append(Component.literal(text).withStyle(style -> style
							.withFont(net.minecraft.network.chat.FontDescription.DEFAULT)
							.withItalic(false).withColor(ChatFormatting.WHITE).withBold(true)));
		}
		return Component.literal(text)
				.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD).withBold(true));
	}

	// True when this player should see the pack's themed menus: the theme is
	// enabled server-wide and the client can actually render Java font
	// glyphs (Bedrock clients through Geyser cannot).
	protected boolean themed() {
		dev.easyeconomy.config.EeConfig config = dev.easyeconomy.config.ConfigManager.get();
		return (config.guiTheme || config.guiTitleBadge)
				&& !dev.easyeconomy.util.Bedrock.isBedrock(player.getUUID());
	}

	protected abstract void onClick(int slot, int button, ContainerInput input);

	private void closed() {
		OPEN.remove(player, this);
		onClose();
	}

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

	// A button with a pack icon model. The custom model is only set for
	// themed players; everyone else gets the plain vanilla item, so menus
	// stay readable without the pack and on Bedrock.
	protected ItemStack display(Item item, String guiModel, String name, List<String> lore) {
		ItemStack stack = display(item, name, lore);
		if (themed()) {
			stack.set(DataComponents.ITEM_MODEL,
					net.minecraft.resources.Identifier.fromNamespaceAndPath("easyeconomy", "gui/" + guiModel));
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
