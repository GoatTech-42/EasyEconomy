package dev.easyeconomy.pshop.tx;

import java.util.UUID;

// Everything TxOps needs from the world, in plain java so the whole
// transaction layer is testable without minecraft. Implementations must
// treat every Persist/flush method as durable: return only after the data
// is on disk, throw TxException otherwise.
public interface TxStore {
	enum MoneyMove { OK, INSUFFICIENT, SELLER_FULL }

	// escrow listing store
	boolean escrowHas(UUID id);

	void escrowPutPersist(String listingJson) throws TxException;

	// Remove from the map and persist. On persist failure the implementation
	// must restore the map entry so memory and disk stay consistent.
	void escrowRemovePersist(UUID id, String listingJson) throws TxException;

	// current balance as recovery will read it (loaded from disk at startup)
	long balanceOf(UUID player);

	// money: move from -> to atomically in memory. INSUFFICIENT means the
	// sender could not pay and nothing moved. SELLER_FULL means the sender
	// was debited and immediately refunded, so nothing moved either way.
	MoneyMove moveMoney(UUID from, UUID to, long amount, String reason);

	void flushMoney() throws TxException;

	// journal: full rewrite, durable. journalRead returns null when absent.
	void journalWrite(String content) throws TxException;

	String journalRead();

	void journalClear();

	// world effects (never durable, always last in a transaction)
	void takeHeldFromSeller(UUID seller);

	void giveItem(UUID player, String listingJson);

	void deliverItem(UUID player, String listingJson);

	// Take back a previously delivered item (memory effect). Used when the
	// journal cannot record that delivery started, so recovery will refund.
	void takeItem(UUID player, String listingJson);

	// Force the player's saved data to disk. Vanilla saves inventories on
	// its own schedule; without this a crash can roll a hand-clear or a
	// delivery back and turn it into a dupe or an unexplained loss.
	// Best-effort: implementations log and continue on failure.
	void savePlayer(UUID player);

	void warn(String msg);

	void error(String msg);
}
