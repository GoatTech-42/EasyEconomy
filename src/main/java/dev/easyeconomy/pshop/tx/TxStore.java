package dev.easyeconomy.pshop.tx;

import java.util.UUID;

// Everything TxOps needs from the world, in plain java so the whole
// transaction layer is testable without minecraft. Implementations must
// treat every Persist/flush method as durable: return only after the data
// is on disk, throw TxException otherwise.
public interface TxStore {
	enum MoneyMove { OK, INSUFFICIENT, SELLER_FULL }

	// Result of an atomic move+flush. On anything but OK the live and the
	// durable state are both exactly as before the call: the in-memory move
	// is rolled back when the flush fails, so live balances can never claim
	// a payment the disk does not have.
	enum MoneyResult { OK, INSUFFICIENT, SELLER_FULL, WRITE_FAILED }

	// escrow listing store
	boolean escrowHas(UUID id);

	void escrowPutPersist(String listingJson) throws TxException;

	// Remove from the map and persist. On persist failure the implementation
	// must restore the map entry so memory and disk stay consistent.
	void escrowRemovePersist(UUID id, String listingJson) throws TxException;

	// money primitives (kept separate so tests can crash between them)
	MoneyMove moveMoney(UUID from, UUID to, long amount, String reason);

	void flushMoney() throws TxException;

	// current balance, as loaded from disk at startup. Recovery reads this
	// only at startup or before a new operation, when live == durable.
	long balanceOf(UUID player);

	// Atomic move + durable flush. Default composition of the primitives:
	// any failure restores the in-memory balances, so memory never diverges
	// from disk.
	default MoneyResult moveMoneyDurable(UUID from, UUID to, long amount, String reason) {
		MoneyMove moved = moveMoney(from, to, amount, reason);
		if (moved == MoneyMove.INSUFFICIENT) {
			return MoneyResult.INSUFFICIENT;
		}
		if (moved == MoneyMove.SELLER_FULL) {
			return MoneyResult.SELLER_FULL;
		}
		try {
			flushMoney();
			return MoneyResult.OK;
		} catch (TxException e) {
			moveMoney(to, from, amount, reason + ":rollback");
			return MoneyResult.WRITE_FAILED;
		}
	}

	// journal: full rewrite, durable. journalRead returns null when absent.
	void journalWrite(String content) throws TxException;

	String journalRead();

	void journalClear();

	// can the player's inventory take this listing's whole stack right now
	boolean canFit(UUID player, String listingJson);

	// world effects (always after their journal marks are durable)
	void takeHeldFromSeller(UUID player);

	void giveItem(UUID player, String listingJson);

	void deliverItem(UUID player, String listingJson);

	// Force the player's saved data to disk AND verify it by reading the
	// saved file back: vanilla's PlayerDataStorage.save swallows write
	// failures and returns normally, so a void call cannot prove anything.
	// Returns true only when the on-disk inventory matches the live one.
	boolean savePlayer(UUID player);

	void warn(String msg);

	void error(String msg);

	// Tell the player a completed trade may not survive a crash. Default is
	// a no-op so harnesses without a live player still work.
	default void warnPlayer(UUID player, String msg) {
	}
}
