package dev.easyeconomy.economy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.LongSupplier;

// Append-only audit log, one json object per line. Every balance change in
// the mod lands here, so dupes and exploits leave a trail.
public final class JsonlLedger implements Ledger {
	private final Path file;
	private final LongSupplier clock;
	private BufferedWriter writer;

	public JsonlLedger(Path file, LongSupplier clock) {
		this.file = file;
		this.clock = clock;
	}

	@Override
	public synchronized void append(String action, UUID player, long amount, long balanceAfter, String reason) {
		write("{\"ts\":" + clock.getAsLong()
				+ ",\"action\":\"" + esc(action) + "\""
				+ ",\"player\":\"" + player + "\""
				+ ",\"amount\":" + amount
				+ ",\"balance\":" + balanceAfter
				+ ",\"reason\":\"" + esc(reason) + "\"}");
	}

	@Override
	public synchronized void appendTransfer(UUID from, UUID to, long amount, long fromAfter, long toAfter, String reason) {
		write("{\"ts\":" + clock.getAsLong()
				+ ",\"action\":\"transfer\""
				+ ",\"from\":\"" + from + "\""
				+ ",\"to\":\"" + to + "\""
				+ ",\"amount\":" + amount
				+ ",\"fromBalance\":" + fromAfter
				+ ",\"toBalance\":" + toAfter
				+ ",\"reason\":\"" + esc(reason) + "\"}");
	}

	public synchronized void close() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
			}
			writer = null;
		}
	}

	private void write(String line) {
		try {
			if (writer == null) {
				Files.createDirectories(file.getParent());
				writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			writer.write(line);
			writer.newLine();
			writer.flush();
		} catch (IOException e) {
			// the ledger must never break the economy; the game log still has the failure
			System.err.println("[easyeconomy] ledger write failed: " + e.getMessage());
		}
	}

	private static String esc(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}
}
