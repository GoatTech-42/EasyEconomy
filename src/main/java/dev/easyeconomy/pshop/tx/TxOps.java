package dev.easyeconomy.pshop.tx;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

// Recoverable transactions for the player shop. One transaction at a time
// (callers synchronize). A one-field-per-line journal records the intent,
// including both balances before a buy, and marks for each durable step.
//
// Crash rule: never create value during recovery. Recovery decides from
// ground truth (the durable balance, the escrow contents), and every money
// move is durable-or-rolled-back, so a balance always tells the truth
// about what happened. Delivery happens only AFTER the delivering mark is
// durable: a refund therefore can never meet an item that was handed out,
// because an unmarked delivery does not exist. The price is a narrow
// window (crash between the delivering mark and the inventory save) where
// a paid item is lost; that is logged. Losing one item to a mid-write
// crash is acceptable; duping is not.
//
// Journal format, one field per line (listing json is single-line):
//   op / listingId / seller / buyer (- when none) / price /
//   buyerBefore (- when none) / sellerBefore (- when none) /
//   steps csv / listingJson
public final class TxOps {
	public enum Outcome { OK, INSUFFICIENT, SELLER_FULL, NO_ROOM, ABORTED_WRITE_FAILED }

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
		// the empty hand must be verifiably on disk before the listing may
		// reach disk, or a crash could roll the hand back and dupe the item
		if (!s.savePlayer(seller)) {
			s.giveItem(seller, listingJson);
			s.savePlayer(seller);
			clearQuietly(s);
			s.error("pshop list aborted: player save could not be verified, item returned");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		try {
			s.escrowPutPersist(listingJson);
		} catch (TxException e) {
			s.giveItem(seller, listingJson);
			if (!s.savePlayer(seller)) {
				s.error("pshop list abort: item returned in memory but the save is unverified; a crash may lose it");
			}
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
		if (!s.canFit(buyer, listingJson)) {
			return Outcome.NO_ROOM;
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
		switch (s.moveMoneyDurable(buyer, seller, price, "pshop-buy:" + id)) {
			case OK -> {
			}
			case INSUFFICIENT -> {
				clearQuietly(s);
				return Outcome.INSUFFICIENT;
			}
			case SELLER_FULL -> {
				clearQuietly(s);
				return Outcome.SELLER_FULL;
			}
			default -> {
				clearQuietly(s);
				s.error("pshop buy aborted: money flush failed, nothing moved");
				return Outcome.ABORTED_WRITE_FAILED;
			}
		}
		try {
			mark(s, tx, STEP_MONEY_MOVED);
			s.escrowRemovePersist(id, listingJson);
			mark(s, tx, STEP_LISTING_REMOVED);
		} catch (TxException e) {
			// the payment is durable; reverse it the same durable way. If
			// even that fails, keep the journal: recovery will refund from
			// ground truth on the next start or operation.
			if (unwindMoney(s, tx)) {
				clearQuietly(s);
			}
			s.error("pshop buy aborted mid-trade; payment reversed");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		try {
			mark(s, tx, STEP_DELIVERING);
		} catch (TxException e) {
			// nothing delivered yet, and nothing will be: without the mark,
			// recovery refunds. The item is lost with the escrow removal.
			s.error("pshop buy aborted: delivery mark failed before delivery; buyer refunded on next start");
			return Outcome.ABORTED_WRITE_FAILED;
		}
		s.deliverItem(buyer, listingJson);
		if (!s.savePlayer(buyer)) {
			// the delivering mark is durable, so this trade counts as
			// delivered; a crash now can lose the item. Loss-biased, logged.
			s.error("pshop buy: delivery save unverified for " + buyer + "; a crash now may lose the item (already logged as delivered)");
		}
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
		// a cancel moves no money, so a missing mark cannot turn into a
		// refund dupe: the item goes back to its owner either way. Mark
		// failures degrade to warnings.
		try {
			mark(s, tx, STEP_LISTING_REMOVED);
			mark(s, tx, STEP_DELIVERING);
		} catch (TxException e) {
			s.warn("pshop cancel journal mark failed; delivering anyway");
		}
		s.deliverItem(seller, listingJson);
		if (!s.savePlayer(seller)) {
			s.warn("pshop cancel: return save unverified for " + seller + "; a crash now may lose the returned item");
			s.warnPlayer(seller,
					"listing cancelled, but the returned item could not be saved safely; a crash may lose it - tell an admin if it vanishes");
		}
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

	// Applies whatever ground truth proves is needed. Idempotent: a crash
	// during recovery leaves a state the next recovery reads the same way,
	// because a refund is durable-or-rolled-back - a half-refund cannot
	// exist, so a refunded buy reads exactly as "money not moved".
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
			moneyMoved = false; // never paid, or already durably refunded
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
			// delivery was marked before it started; treat as delivered. If
			// the crash hit before the inventory save, the item is lost -
			// refunding instead could dupe an item that did make it out.
			s.warn("pshop crash recovery: buy " + tx.id() + " marked delivering; treated as delivered");
			clearQuietly(s);
			return;
		}
		// paid but not delivered: refund, durably or not at all. The journal
		// stays until the refund is actually on disk - including when the
		// seller can no longer cover it, which needs an admin.
		if (!escrowHas) {
			s.error("pshop crash recovery: buy " + tx.id()
					+ " paid and escrow removed before delivery; refunding, the item is lost");
		}
		TxStore.MoneyResult refund = s.moveMoneyDurable(tx.seller(), tx.buyer(), tx.price(), "pshop-recovery-refund:" + tx.id());
		switch (refund) {
			case OK -> {
				s.warn("pshop crash recovery: refunded buyer for interrupted buy " + tx.id());
				clearQuietly(s);
			}
			case INSUFFICIENT -> s.error("pshop crash recovery: seller " + tx.seller()
					+ " cannot cover the refund for " + tx.id() + "; journal kept, needs an admin");
			default -> s.error("pshop recovery refund could not be flushed; journal kept for next start");
		}
	}

	// Reverse a durable payment, durably. Returns false (journal kept) when
	// the reversal itself could not be committed; recovery will pick it up.
	private static boolean unwindMoney(TxStore s, Entry tx) {
		if (s.moveMoneyDurable(tx.seller(), tx.buyer(), tx.price(), "pshop-unwind:" + tx.id())
				!= TxStore.MoneyResult.OK) {
			s.error("pshop unwind could not be committed; journal kept, recovery will refund");
			return false;
		}
		return true;
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
