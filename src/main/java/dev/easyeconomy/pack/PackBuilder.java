package dev.easyeconomy.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.currency.CurrencyMath;
import dev.easyeconomy.config.EeConfig;
import net.minecraft.DetectedVersion;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// Builds easyeconomy-pack.zip from the crafted textures and models under
// src/main/resources/pack (made by tools/pack-art/gen_art.py): the dark gui
// skins drawn over chest menus through a font glyph, coin and menu-icon
// item models, the title badge glyph, and pack.mcmeta. Denominations are
// config-driven, so one small items/coin_<value>.json per denomination is
// generated here and pointed at the tier texture for its rank.
//
// The zip goes in the server run dir so an admin can host it anywhere, and
// the built-in PackServer can serve it when enabled.
public final class PackBuilder {
	private static final int MAX_TIER = 7;

	// everything below assets/easyeconomy/, copied from /pack/ verbatim
	private static final String[] STATIC_FILES = staticFiles();

	public record Built(Path zip, String sha1) {
	}

	public static Built build(Path outZip, EeConfig config) throws Exception {
		List<Long> denoms = CurrencyMath.validate(config.denominations);
		Path tmp = outZip.resolveSibling(outZip.getFileName() + ".tmp");
		Files.createDirectories(outZip.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
			put(zip, "pack.mcmeta", packMeta());
			byte[] icon = resourceBytes("/assets/easyeconomy/icon.png");
			if (icon != null) {
				put(zip, "pack.png", icon);
			}
			for (String file : STATIC_FILES) {
				byte[] data = resourceBytes("/pack/" + file);
				if (data == null) {
					throw new IllegalStateException("missing bundled pack file: /pack/" + file);
				}
				// files under pack/minecraft/ override vanilla assets; the rest
				// live in the mod's own namespace
				String target = file.startsWith("minecraft/")
						? "assets/" + file
						: "assets/easyeconomy/" + file;
				put(zip, target, data);
			}
			int tier = 0;
			for (long d : denoms) {
				String model = "easyeconomy:item/coin_tier" + Math.min(tier++, MAX_TIER);
				put(zip, "assets/easyeconomy/items/coin_" + d + ".json",
						("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}}")
								.getBytes(StandardCharsets.UTF_8));
			}
		}
		Files.move(tmp, outZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(outZip)));
		EasyEconomyMod.LOGGER.info("resource pack built at {} (sha1 {})", outZip, sha1);
		return new Built(outZip, sha1);
	}

	private static String[] staticFiles() {
		java.util.List<String> files = new java.util.ArrayList<>();
		files.add("font/gui.json");
		files.add("textures/font/gui_title.png");
		// dark reskin of the vanilla generic container texture: it shows
		// through the gui skin's transparent slot cutouts and restyles plain
		// chests to match while the pack is on
		files.add("minecraft/textures/gui/container/generic_54.png");
		String[] guiIcons = {"back", "nav_prev", "nav_next", "listings_all", "listings_mine", "balance"};
		for (int rows = 1; rows <= 6; rows++) {
			files.add("textures/font/gui_bg_" + rows + ".png");
		}
		for (int tier = 0; tier <= MAX_TIER; tier++) {
			files.add("textures/item/coin_tier" + tier + ".png");
			files.add("models/item/coin_tier" + tier + ".json");
		}
		for (String icon : guiIcons) {
			files.add("textures/item/gui/" + icon + ".png");
			files.add("models/item/gui/" + icon + ".json");
			files.add("items/gui/" + icon + ".json");
		}
		return files.toArray(new String[0]);
	}

	private static byte[] packMeta() {
		PackFormat format = DetectedVersion.BUILT_IN.packVersion(PackType.CLIENT_RESOURCES);
		PackMetadataSection section = new PackMetadataSection(
				Component.literal("easyeconomy gui skins and coin models"),
				new InclusiveRange<>(format, format));
		JsonElement encoded = PackMetadataSection.CLIENT_TYPE.codec()
				.encodeStart(JsonOps.INSTANCE, section)
				.getOrThrow(IllegalStateException::new);
		JsonObject root = new JsonObject();
		root.add("pack", encoded);
		return root.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] resourceBytes(String path) throws Exception {
		try (InputStream in = PackBuilder.class.getResourceAsStream(path)) {
			return in == null ? null : in.readAllBytes();
		}
	}

	private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(data);
		zip.closeEntry();
	}
}
