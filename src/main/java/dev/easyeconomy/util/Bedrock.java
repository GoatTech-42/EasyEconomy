package dev.easyeconomy.util;

import net.fabricmc.loader.api.FabricLoader;

import java.util.UUID;

// Geyser/Floodgate detection. Everything works for Bedrock players without
// this mod installed; this only lets us tell them apart, for logging and
// admin info. Floodgate classes are compile-only, so they are referenced
// from a holder that only loads when the floodgate mod is present.
public final class Bedrock {
	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("floodgate");

	private Bedrock() {
	}

	public static boolean available() {
		return PRESENT;
	}

	public static boolean isBedrock(UUID id) {
		return PRESENT && Holder.is(id);
	}

	// Keeping this in its own class means the floodgate linkage is never
	// touched when the mod is absent.
	private static final class Holder {
		private static boolean is(UUID id) {
			return org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(id);
		}
	}
}
