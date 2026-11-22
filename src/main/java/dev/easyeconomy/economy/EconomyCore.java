package dev.easyeconomy.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Pure economy logic. No minecraft imports, so it can be tested by a plain
// java harness. All methods are synchronized; the game calls them from the
// server main thread, so economy mutations are single-writer by design.
public final class EconomyCore {
	public enum Result {
		OK, INVALID_AMOUNT, INSUFFICIENT_FUNDS, OVER_MAX_BALANCE, SELF_TRANSFER
	}

	private final Map<UUID, Long> balances = new HashMap<>();
	private final long startingBalance;
	private final long maxBalance;
	private final Ledger ledger;

	public EconomyCore(long startingBalance, long maxBalance, Ledger ledger) {
		if (startingBalance < 0 || maxBalance <= 0 || startingBalance > maxBalance) {
			throw new IllegalArgumentException("bad balance bounds");
		}
		this.startingBalance = startingBalance;
		this.maxBalance = maxBalance;
		this.ledger = ledger == null ? Ledger.NOOP : ledger;
	}

	public long startingBalance() {
		return startingBalance;
	}

	public long maxBalance() {
		return maxBalance;
	}

	public synchronized long balance(UUID id) {
		Long bal = balances.get(id);
		return bal == null ? startingBalance : bal;
	}

	public synchronized Result deposit(UUID id, long amount, String reason) {
		if (amount <= 0) {
			return Result.INVALID_AMOUNT;
		}
		long bal = balance(id);
		if (amount > maxBalance - bal) {
			return Result.OVER_MAX_BALANCE;
		}
		long next = bal + amount;
		balances.put(id, next);
		ledger.append("deposit", id, amount, next, reason);
		return Result.OK;
	}

	public synchronized Result withdraw(UUID id, long amount, String reason) {
		if (amount <= 0) {
			return Result.INVALID_AMOUNT;
		}
		long bal = balance(id);
		if (amount > bal) {
			return Result.INSUFFICIENT_FUNDS;
		}
		long next = bal - amount;
		balances.put(id, next);
		ledger.append("withdraw", id, amount, next, reason);
		return Result.OK;
	}

	public synchronized Result transfer(UUID from, UUID to, long amount, String reason) {
		if (from.equals(to)) {
			return Result.SELF_TRANSFER;
		}
		if (amount <= 0) {
			return Result.INVALID_AMOUNT;
		}
		long fromBal = balance(from);
		long toBal = balance(to);
		if (amount > fromBal) {
			return Result.INSUFFICIENT_FUNDS;
		}
		if (amount > maxBalance - toBal) {
			return Result.OVER_MAX_BALANCE;
		}
		balances.put(from, fromBal - amount);
		balances.put(to, toBal + amount);
		ledger.appendTransfer(from, to, amount, fromBal - amount, toBal + amount, reason);
		return Result.OK;
	}

	public synchronized Result set(UUID id, long amount, String reason) {
		if (amount < 0 || amount > maxBalance) {
			return Result.INVALID_AMOUNT;
		}
		balances.put(id, amount);
		ledger.append("set", id, amount, amount, reason);
		return Result.OK;
	}

	public synchronized Map<UUID, Long> snapshot() {
		return new HashMap<>(balances);
	}

	public synchronized void loadAll(Map<UUID, Long> data) {
		balances.clear();
		balances.putAll(data);
	}
}
