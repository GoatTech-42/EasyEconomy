package dev.easyeconomy.config;

public final class EeConfig {
	public String currencySingular = "coin";
	public String currencyPlural = "coins";
	public long startingBalance = 50;
	public long maxBalance = 1_000_000_000L;
	public long minPayAmount = 1;
	public int autosaveMinutes = 5;
	public String currencyBaseItem = "minecraft:paper";
	public java.util.List<Long> denominations = java.util.List.of(1L, 5L, 10L, 50L, 100L);
	public int maxListingsPerPlayer = 10;
	public PackServer packServer = new PackServer();

	public static final class PackServer {
		public boolean enabled = false;
		public String bind = "127.0.0.1";
		public int port = 8180;
	}
}
