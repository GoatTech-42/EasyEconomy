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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// Builds easyeconomy-pack.zip: coin item models + textures + pack.mcmeta.
// The zip goes in the server run dir so an admin can host it anywhere, and
// the built-in PackServer can serve it when enabled.
public final class PackBuilder {
	private static final int[] DENOM_COLORS = {
			0xd8a03c, // gold
			0xc0c0c0, // silver
			0xb87333, // copper
			0x50c878, // emerald
			0x4fc3f7, // diamond
			0xc084fc, // amethyst
			0xf472b6, // rose
			0xfde047, // bright gold
	};

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
			int colorIndex = 0;
			for (long d : denoms) {
				String name = "coin_" + d;
				put(zip, "assets/easyeconomy/items/" + name + ".json",
						("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"easyeconomy:item/" + name + "\"}}")
								.getBytes(StandardCharsets.UTF_8));
				put(zip, "assets/easyeconomy/models/item/" + name + ".json",
						("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"easyeconomy:item/" + name + "\"}}")
								.getBytes(StandardCharsets.UTF_8));
				put(zip, "assets/easyeconomy/textures/item/" + name + ".png",
						coinTexture(DENOM_COLORS[colorIndex++ % DENOM_COLORS.length]));
			}
		}
		Files.move(tmp, outZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(outZip)));
		EasyEconomyMod.LOGGER.info("resource pack built at {} (sha1 {})", outZip, sha1);
		return new Built(outZip, sha1);
	}

	private static byte[] packMeta() {
		PackFormat format = DetectedVersion.BUILT_IN.packVersion(PackType.CLIENT_RESOURCES);
		PackMetadataSection section = new PackMetadataSection(
				Component.literal("easyeconomy coin models"),
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

	private static byte[] coinTexture(int rgb) throws Exception {
		int size = 16;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
		int rim = 0xff000000 | (r * 2 / 3) << 16 | (g * 2 / 3) << 8 | (b * 2 / 3);
		int face = 0xff000000 | rgb;
		int hi = 0xff000000 | Math.min(255, r + 40) << 16 | Math.min(255, g + 40) << 8 | Math.min(255, b + 40);
		double c = 7.5;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double d = Math.hypot(x - c, y - c);
				if (d <= 7.0) {
					int px = d > 5.5 ? rim : (x < 6 && y < 6 ? hi : face);
					img.setRGB(x, y, px);
				}
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "PNG", out);
		return out.toByteArray();
	}
}
