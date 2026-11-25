package dev.easyeconomy.pack;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

// Registers the bundled gui pack (resourcepacks/gui inside the jar) as an
// always-enabled builtin pack, so a client that has the mod shows the shop
// skin with no separate download. User and server-pushed packs still sit
// above it; the server-built zip remains the fallback for clients without
// the mod.
public final class EasyEconomyClientPack implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FabricLoader.getInstance().getModContainer("easyeconomy").ifPresent(mod ->
				ResourceLoader.registerBuiltinPack(
						Identifier.fromNamespaceAndPath("easyeconomy", "gui"),
						mod,
						Component.literal("EasyEconomy GUI"),
						PackActivationType.ALWAYS_ENABLED));
	}
}
