package dev.easyeconomy.economy;

import java.util.UUID;

public interface Ledger {
	void append(String action, UUID player, long amount, long balanceAfter, String reason);

	void appendTransfer(UUID from, UUID to, long amount, long fromAfter, long toAfter, String reason);

	Ledger NOOP = new Ledger() {
		@Override
		public void append(String action, UUID player, long amount, long balanceAfter, String reason) {
		}

		@Override
		public void appendTransfer(UUID from, UUID to, long amount, long fromAfter, long toAfter, String reason) {
		}
	};
}
