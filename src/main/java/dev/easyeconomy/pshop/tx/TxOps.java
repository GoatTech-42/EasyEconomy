package dev.easyeconomy.pshop.tx;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

// Recoverable transactions for the player shop. One transaction at a time
// (callers synchronize). A one-field-per-line journal records the intent,
// including both balances before a buy, and marks for each durable step.
// On startup, and before every new operation, recover() finishes or
// unwinds whatever was in flight.
//
// Crash rule: never create value during recovery. Recovery decides from
// ground truth (the durable balance, the escrow contents), never from
// journal marks alone: a crash can leave any mark unwritten, so a mark can
// only say "this may have happened". A half-finished delivery is reported
// and dropped (the item is lost), a half-finished payment is refunded.
// Losing one item to a mid-write crash is acceptable; duping is not.
//
// Journal format, one field per line (listing json is single-line):
//   op / listingId / seller / buyer (- when none) / price /
//   buyerBefore (- when none) / sellerBefore (- when none) /
//   steps csv / listingJson
public final class TxOps {
	public enum Outcome { OK, INSUFFICIENT, SELLER_FULL, ABORTED_WRITE_FAILED }

	public static final String OP_LIST = "list";
	public static final String OP_BUY = "buy";
	public static final String OP_CANCEL = "cancel";
	public static final String STEP_MONEY_MOVED = "money_moved";
	public static final String STEP_LISTING_REMOVED = "listing_removed";
	public static final String STEP_DELIVERING = "delivering";

	private TxOps() {
	}

	record Entry(String op, UUID id, UUID seller, UUID buyer, long price,
			long buyerBefore, long sellerBefore, Set<String> steps, String listingJson) {
	}

	// ---------- operations ----------

	public static Outcome list(TxStore s, UUID id, UUID seller, long price, String listingJson) {
		if (!gate(s)) {
			return Outcome.ABORTED_WRITE_FAILED;
		}
		Entry tx = new Entry(OP_LIST, id, seller, null, price, -1, -1, new LinkedHashSet<>(), listingJson);
		try {
			s.journalWrite(encode(tx));
		} catch (TxException e) {
			s.error("pshop list aborted: journal write failed, nothing moved");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		s.takeHeldFromSeller(seller);
		// make the hand-clear durable before the listing reaches disk, so a
		// crash cannot roll the inventory back and resurrect the item
		s.savePlayer(seller);
		try {
			s.escrowPutPersist(listingJson);
		} catch (TxException e) {
			s.giveItem(seller, listingJson);
			// the empty hand was made durable above; make the return durable
			// too, or a crash would roll the world back to no item at all
			s.savePlayer(seller);
			clearQuietly(s);
			s.error("pshop list aborted: listing save failed, item returned");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		clearQuietly(s);
		return Outcome.OK;
	}

	public static Outcome buy(TxStore s, UUID id, UUID seller, UUID buyer, long price, String listingJson) {
		if (!gate(s)) {
			return Outcome.ABORTED_WRITE_FAILED;
		}
		long buyerBefore = s.balanceOf(buyer);
		long sellerBefore = s.balanceOf(seller);
		Entry tx = new Entry(OP_BUY, id, seller, buyer, price, buyerBefore, sellerBefore, new LinkedHashSet<>(), listingJson);
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
			unwindMoney(s, tx);
			clearQuietly(s);
			s.error("pshop buy aborted: money flush failed, payment reversed");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		try {
			s.escrowRemovePersist(id, listingJson);
		} catch (TxException e) {
			unwindMoney(s, tx);
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
		try {
			mark(s, tx, STEP_DELIVERING);
		} catch (TxException e) {
			// The journal cannot prove delivery started. Take the item back
			// and let recovery refund the buyer; the item is lost either
			// way, a dupe is not.
			s.takeItem(buyer, listingJson);
			s.savePlayer(buyer);
			s.error("pshop buy aborted: delivery mark failed, item recalled, buyer refunded on next start");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		s.savePlayer(buyer);
		clearQuietly(s);
		return Outcome.OK;
	}

	public static Outcome cancel(TxStore s, UUID id, UUID seller, long price, String listingJson) {
		if (!gate(s)) {
			return Outcome.ABORTED_WRITE_FAILED;
		}
		Entry tx = new Entry(OP_CANCEL, id, seller, null, price, -1, -1, new LinkedHashSet<>(), listingJson);
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
		// A cancel moves no money, so a missing journal mark cannot turn
		// into a refund dupe: the item goes back to its owner either way.
		// Mark failures degrade to warnings; delivery still happens once.
		boolean delivered = false;
		try {
			mark(s, tx, STEP_LISTING_REMOVED);
		} catch (TxException e) {
			s.warn("pshop cancel journal mark failed; delivering anyway");
		}
		s.deliverItem(seller, listingJson);
		delivered = true;
		try {
			mark(s, tx, STEP_DELIVERING);
		} catch (TxException e) {
			s.warn("pshop cancel delivery mark failed; item already returned");
		}
		if (!delivered) {
			s.error("pshop cancel: unreachable state, item not returned: " + tx.id());
		}
		s.savePlayer(seller);
		clearQuietly(s);
		return Outcome.OK;
	}

	// ---------- recovery ----------

	// Before any new operation: a leftover journal means a crash (or a bug)
	// interrupted the previous transaction. Recover it first; if recovery
	// cannot finish, block new trades rather than pile a second transaction
	// onto an unresolved one.
	private static boolean gate(TxStore s) {
		if (s.journalRead() == null) {
			return true;
		}
		recover(s);
		if (s.journalRead() != null) {
			s.error("pshop: unresolved journal blocks new trades; check the log and the journal file");
			return false;
		}
		return true;
	}

	// Applies whatever the journal proves is needed, decided from ground
	// truth: the durable balances and the escrow contents. Idempotent - a
	// crash during recovery leaves a state the next recovery reads the
	// same way (a refunded buy shows the buyer back at buyerBefore, which
	// reads as "money not moved" and just clears).
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
				if (!s.escrowHas(tx.id())) {
					s.error("pshop crash recovery: listing " + tx.id() + " never reached disk; the item is lost");
				}
				clearQuietly(s);
			}
			case OP_BUY -> recoverBuy(s, tx);
			case OP_CANCEL -> {
				if (s.escrowHas(tx.id())) {
					// removal never reached disk; the listing stands
				} else if (!tx.steps().contains(STEP_DELIVERING)) {
					s.error("pshop crash recovery: cancel " + tx.id()
							+ " removed the listing before delivery; the item is lost, admin notified");
				}
				clearQuietly(s);
			}
			default -> s.error("pshop journal has unknown op, leaving for manual review");
		}
	}

	private static void recoverBuy(TxStore s, Entry tx) {
		if (tx.buyer() == null || tx.buyerBefore() < 0 || tx.sellerBefore() < 0) {
			s.error("pshop journal buy entry missing balances, leaving for manual review");
			return;
		}
		long buyerNow = s.balanceOf(tx.buyer());
		boolean moneyMoved;
		if (buyerNow == tx.buyerBefore() - tx.price()) {
			moneyMoved = true;
		} else if (buyerNow == tx.buyerBefore()) {
			moneyMoved = false; // never paid, or already refunded
		} else {
			s.error("pshop journal balance does not match intent (buyer " + tx.buyer()
					+ " expected " + tx.buyerBefore() + " or " + (tx.buyerBefore() - tx.price())
					+ ", found " + buyerNow + "); leaving journal for manual review");
			return;
		}
		if (!moneyMoved) {
			clearQuietly(s);
			return;
		}
		boolean escrowHas = s.escrowHas(tx.id());
		if (tx.steps().contains(STEP_DELIVERING) && !escrowHas) {
			// delivery started and the listing is gone; treat as delivered.
			// If the crash hit before the inventory save, the item is lost -
			// refunding instead could dupe an item that did make it to disk.
			s.warn("pshop crash recovery: buy " + tx.id() + " marked delivering; treated as delivered");
			clearQuietly(s);
			return;
		}
		// paid but not delivered: refund from ground truth. If the listing
		// is gone, the item is lost; if it is still in escrow the trade
		// simply unwinds.
		if (!escrowHas) {
			s.error("pshop crash recovery: buy " + tx.id()
					+ " paid and escrow removed before delivery; refunding, the item is lost");
		}
		s.moveMoney(tx.seller(), tx.buyer(), tx.price(), "pshop-recovery-refund:" + tx.id());
		try {
			s.flushMoney();
		} catch (TxException e) {
			s.error("pshop recovery refund could not be flushed; journal kept for next start");
			return;
		}
		s.warn("pshop crash recovery: refunded buyer for interrupted buy " + tx.id());
		clearQuietly(s);
	}

	private static void unwindMoney(TxStore s, Entry tx) {
		s.moveMoney(tx.seller(), tx.buyer(), tx.price(), "pshop-unwind:" + tx.id());
		try {
			s.flushMoney();
		} catch (TxException e2) {
			s.error("pshop unwind could not be flushed; balances need admin review");
		}
	}

	// ---------- journal codec ----------

	private static void mark(TxStore s, Entry tx, String step) throws TxException {
		tx.steps().add(step);
		s.journalWrite(encode(tx));
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
				tx.buyerBefore() < 0 ? "-" : Long.toString(tx.buyerBefore()),
				tx.sellerBefore() < 0 ? "-" : Long.toString(tx.sellerBefore()),
				String.join(",", tx.steps()),
				tx.listingJson()) + "\n";
	}

	static Entry decode(String raw) {
		String[] lines = raw.split("\n", -1);
		if (lines.length < 9) {
			throw new IllegalArgumentException("journal too short");
		}
		Set<String> steps = new LinkedHashSet<>();
		if (!lines[7].isEmpty()) {
			for (String st : lines[7].split(",")) {
				steps.add(st);
			}
		}
		return new Entry(lines[0], UUID.fromString(lines[1]), UUID.fromString(lines[2]),
				"-".equals(lines[3]) ? null : UUID.fromString(lines[3]),
				Long.parseLong(lines[4]),
				"-".equals(lines[5]) ? -1 : Long.parseLong(lines[5]),
				"-".equals(lines[6]) ? -1 : Long.parseLong(lines[6]),
				steps, lines[8]);
	}
}
