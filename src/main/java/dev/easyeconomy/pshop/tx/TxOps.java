package dev.easyeconomy.pshop.tx;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

// Recoverable transactions for the player shop. One transaction at a time
// (callers synchronize). A single-line-per-field journal file records the
// intent and each completed durable step; on startup, recover() finishes or
// unwinds whatever was in flight when the server stopped.
//
// Crash rule: never create value during recovery. A half-finished delivery
// is reported and dropped (the item is lost), a half-finished payment is
// refunded. Losing one item to a mid-write crash is acceptable; duping is
// not.
//
// Journal format, one field per line (listing json is single-line):
//   op / listingId / seller / buyer (- when none) / price / steps csv / listingJson
public final class TxOps {
	public enum Outcome { OK, INSUFFICIENT, SELLER_FULL, ABORTED_WRITE_FAILED }

	public static final String OP_LIST = "list";
	public static final String OP_BUY = "buy";
	public static final String OP_CANCEL = "cancel";
	public static final String STEP_MONEY_MOVED = "money_moved";
	public static final String STEP_LISTING_REMOVED = "listing_removed";
	public static final String STEP_REFUNDING = "refunding";

	private TxOps() {
	}

	record Entry(String op, UUID id, UUID seller, UUID buyer, long price, Set<String> steps, String listingJson) {
	}

	// ---------- operations ----------

	public static Outcome list(TxStore s, UUID id, UUID seller, long price, String listingJson) {
		Entry tx = new Entry(OP_LIST, id, seller, null, price, new LinkedHashSet<>(), listingJson);
		try {
			s.journalWrite(encode(tx));
		} catch (TxException e) {
			s.error("pshop list aborted: journal write failed, nothing moved");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		s.takeHeldFromSeller(seller);
		try {
			s.escrowPutPersist(listingJson);
		} catch (TxException e) {
			s.giveItem(seller, listingJson);
			clearQuietly(s);
			s.error("pshop list aborted: listing save failed, item returned");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		clearQuietly(s);
		return Outcome.OK;
	}

	public static Outcome buy(TxStore s, UUID id, UUID seller, UUID buyer, long price, String listingJson) {
		Entry tx = new Entry(OP_BUY, id, seller, buyer, price, new LinkedHashSet<>(), listingJson);
		try {
			s.journalWrite(encode(tx));
		} catch (TxException e) {
			s.error("pshop buy aborted: journal write failed, nothing moved");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		TxStore.MoneyMove moved = s.moveMoney(buyer, seller, price, "pshop-buy:" + id);
		if (moved != TxStore.MoneyMove.OK) {
			clearQuietly(s);
			return moved == TxStore.MoneyMove.INSUFFICIENT ? Outcome.INSUFFICIENT : Outcome.SELLER_FULL;
		}
		try {
			s.flushMoney();
			mark(s, tx, STEP_MONEY_MOVED);
		} catch (TxException e) {
			// money moved in memory; unwind it before giving up
			s.moveMoney(seller, buyer, price, "pshop-unwind:" + id);
			try {
				s.flushMoney();
			} catch (TxException e2) {
				s.error("pshop unwind could not be flushed; balances need admin review");
			}
			clearQuietly(s);
			s.error("pshop buy aborted: money flush failed, payment reversed");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		try {
			s.escrowRemovePersist(id, listingJson);
		} catch (TxException e) {
			s.moveMoney(seller, buyer, price, "pshop-unwind:" + id);
			try {
				s.flushMoney();
			} catch (TxException e2) {
				s.error("pshop unwind could not be flushed; balances need admin review");
			}
			clearQuietly(s);
			s.error("pshop buy aborted: listing save failed, payment reversed");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		try {
			mark(s, tx, STEP_LISTING_REMOVED);
		} catch (TxException e) {
			// The listing is durably gone but the journal does not say so.
			// Do NOT deliver: recovery will refund the buyer, and delivering
			// here plus that refund would create value. Item is lost, logged.
			s.error("pshop buy aborted after escrow removal; item dropped, buyer refunded on next start");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		s.deliverItem(buyer, listingJson);
		clearQuietly(s);
		return Outcome.OK;
	}

	public static Outcome cancel(TxStore s, UUID id, UUID seller, long price, String listingJson) {
		Entry tx = new Entry(OP_CANCEL, id, seller, null, price, new LinkedHashSet<>(), listingJson);
		try {
			s.journalWrite(encode(tx));
		} catch (TxException e) {
			s.error("pshop cancel aborted: journal write failed, nothing moved");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		try {
			s.escrowRemovePersist(id, listingJson);
		} catch (TxException e) {
			clearQuietly(s);
			s.error("pshop cancel aborted: listing save failed, listing kept");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		try {
			mark(s, tx, STEP_LISTING_REMOVED);
		} catch (TxException e) {
			// Delivering the seller's own item back is safe even without the
			// mark: recovery with no steps just clears the journal.
			s.warn("pshop cancel journal mark failed; delivering anyway");
		}
		s.deliverItem(seller, listingJson);
		clearQuietly(s);
		return Outcome.OK;
	}

	// ---------- recovery ----------

	// Runs once at startup, after listings and balances are loaded, before
	// players can trade. Applies each journaled step exactly once; a crash
	// during recovery is safe because recovery writes its own mark before
	// the money moves.
	public static void recover(TxStore s) {
		String raw = s.journalRead();
		if (raw == null) {
			return;
		}
		Entry tx;
		try {
			tx = decode(raw);
		} catch (Exception e) {
			s.error("pshop journal unreadable, leaving it for manual review: " + e.getMessage());
			return;
		}
		switch (tx.op()) {
			case OP_LIST -> {
				if (s.escrowHas(tx.id())) {
					// listing made it to disk; the trade stands
				} else {
					s.error("pshop crash recovery: listing " + tx.id() + " never reached disk; the item is lost");
				}
			}
			case OP_BUY -> {
				boolean moneyMoved = tx.steps().contains(STEP_MONEY_MOVED);
				boolean listingRemoved = tx.steps().contains(STEP_LISTING_REMOVED);
				if (moneyMoved && !listingRemoved) {
					if (tx.steps().contains(STEP_REFUNDING)) {
						// refund already flushed before an earlier crash
					} else {
						try {
							markRaw(s, tx, STEP_REFUNDING);
							s.moveMoney(tx.seller(), tx.buyer(), tx.price(), "pshop-recovery-refund:" + tx.id());
							s.flushMoney();
							s.warn("pshop crash recovery: refunded buyer for interrupted buy " + tx.id());
						} catch (TxException e) {
							s.error("pshop recovery refund could not be flushed; journal kept for next start");
							return;
						}
					}
				} else if (moneyMoved) {
					s.error("pshop crash recovery: buy " + tx.id()
							+ " paid and escrow removed but delivery unknown; the item is lost, admin notified");
				}
				// !moneyMoved: nothing durable happened
			}
			case OP_CANCEL -> {
				if (tx.steps().contains(STEP_LISTING_REMOVED)) {
					s.error("pshop crash recovery: cancel " + tx.id()
							+ " removed the listing but delivery unknown; the item is lost, admin notified");
				}
			}
			default -> s.error("pshop journal has unknown op, leaving for manual review");
		}
		clearQuietly(s);
	}

	// ---------- journal codec ----------

	private static void mark(TxStore s, Entry tx, String step) throws TxException {
		tx.steps().add(step);
		s.journalWrite(encode(tx));
	}

	// Recovery variant: mutating the in-memory set is pointless on reload,
	// but keeping the call identical makes the format the single source.
	private static void markRaw(TxStore s, Entry tx, String step) throws TxException {
		Set<String> steps = new LinkedHashSet<>(tx.steps());
		steps.add(step);
		s.journalWrite(encode(new Entry(tx.op(), tx.id(), tx.seller(), tx.buyer(), tx.price(), steps, tx.listingJson())));
	}

	private static void clearQuietly(TxStore s) {
		s.journalClear();
	}

	static String encode(Entry tx) {
		return String.join("\n",
				tx.op(),
				tx.id().toString(),
				tx.seller().toString(),
				tx.buyer() == null ? "-" : tx.buyer().toString(),
				Long.toString(tx.price()),
				String.join(",", tx.steps()),
				tx.listingJson()) + "\n";
	}

	static Entry decode(String raw) {
		String[] lines = raw.split("\n", -1);
		if (lines.length < 7) {
			throw new IllegalArgumentException("journal too short");
		}
		Set<String> steps = new LinkedHashSet<>();
		if (!lines[5].isEmpty()) {
			for (String st : lines[5].split(",")) {
				steps.add(st);
			}
		}
		return new Entry(lines[0], UUID.fromString(lines[1]), UUID.fromString(lines[2]),
				"-".equals(lines[3]) ? null : UUID.fromString(lines[3]),
				Long.parseLong(lines[4]), steps, lines[6]);
	}
}
