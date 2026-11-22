import dev.easyeconomy.pshop.tx.TxOps;
import dev.easyeconomy.pshop.tx.TxException;
import dev.easyeconomy.pshop.tx.TxStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Crash and write-failure fault tests for the player-shop transaction
// journal. A mock world records what is durable (disk maps) and what is
// not (memory maps). A crash freezes memory; the post-crash world is
// seeded from disk only, recovery runs, and we assert the invariants:
// money is never created or destroyed, an item never exists twice, and
// every half-finished operation ends in its documented loss-biased state.
public class TestPShopTx {
	private static int checks = 0;

	private static final class Crash extends RuntimeException {
	}

	private static final class MockStore implements TxStore {
		Map<UUID, String> escrow = new HashMap<>();
		Map<UUID, String> escrowDisk = new HashMap<>();
		Map<UUID, Long> balances = new HashMap<>();
		Map<UUID, Long> moneyDisk = new HashMap<>();
		Map<UUID, String> hands = new HashMap<>();
		List<String> deliveries = new ArrayList<>();
		List<String> logs = new ArrayList<>();
		String journal;
		int step = 0;
		int killAtStep = -1;
		int failAtStep = -1;

		private void checkpoint() {
			if (killAtStep == step) {
				throw new Crash();
			}
			step++;
		}

		private boolean writeFails() {
			return failAtStep == step - 1;
		}

		@Override
		public boolean escrowHas(UUID id) {
			return escrow.containsKey(id);
		}

		@Override
		public void escrowPutPersist(String listingJson) throws TxException {
			checkpoint();
			UUID id = listingId(listingJson);
			escrow.put(id, listingJson);
			if (writeFails()) {
				escrow.remove(id);
				throw new TxException("injected", null);
			}
			escrowDisk = new HashMap<>(escrow);
		}

		@Override
		public void escrowRemovePersist(UUID id, String listingJson) throws TxException {
			checkpoint();
			String removed = escrow.remove(id);
			if (writeFails()) {
				if (removed != null) {
					escrow.put(id, removed);
				}
				throw new TxException("injected", null);
			}
			escrowDisk = new HashMap<>(escrow);
		}

		@Override
		public MoneyMove moveMoney(UUID from, UUID to, long amount, String reason) {
			checkpoint();
			long fromBal = balances.getOrDefault(from, 0L);
			if (fromBal < amount) {
				return MoneyMove.INSUFFICIENT;
			}
			balances.put(from, fromBal - amount);
			long toBal = balances.getOrDefault(to, 0L);
			if (toBal + amount > 1000) {
				balances.put(from, balances.get(from) + amount); // refund
				return MoneyMove.SELLER_FULL;
			}
			balances.put(to, toBal + amount);
			return MoneyMove.OK;
		}

		@Override
		public void flushMoney() throws TxException {
			checkpoint();
			if (writeFails()) {
				throw new TxException("injected", null);
			}
			moneyDisk = new HashMap<>(balances);
		}

		@Override
		public void journalWrite(String content) throws TxException {
			checkpoint();
			if (writeFails()) {
				throw new TxException("injected", null);
			}
			journal = content;
		}

		@Override
		public String journalRead() {
			return journal;
		}

		@Override
		public void journalClear() {
			checkpoint();
			journal = null;
		}

		@Override
		public void takeHeldFromSeller(UUID seller) {
			checkpoint();
			hands.remove(seller);
		}

		@Override
		public void giveItem(UUID player, String listingJson) {
			checkpoint();
			hands.put(player, listingJson);
		}

		@Override
		public void deliverItem(UUID player, String listingJson) {
			checkpoint();
			deliveries.add(listingJson);
		}

		@Override
		public void warn(String msg) {
			logs.add("warn:" + msg);
		}

		@Override
		public void error(String msg) {
			logs.add("error:" + msg);
		}

		// a fresh post-crash world: memory comes from disk, the journal and
		// the physical world (hands, deliveries) survive as they were
		MockStore afterCrash() {
			MockStore next = new MockStore();
			next.escrow = new HashMap<>(escrowDisk);
			next.escrowDisk = new HashMap<>(escrowDisk);
			next.balances = new HashMap<>(moneyDisk);
			next.moneyDisk = new HashMap<>(moneyDisk);
			next.journal = journal;
			next.hands = hands;
			next.deliveries = deliveries;
			next.logs = logs;
			return next;
		}

		long totalMoney() {
			return balances.values().stream().mapToLong(Long::longValue).sum();
		}

		long itemCopies(String listingJson) {
			long n = deliveries.stream().filter(d -> d.equals(listingJson)).count();
			if (hands.containsValue(listingJson)) {
				n++;
			}
			if (escrow.containsValue(listingJson)) {
				n++;
			}
			return n;
		}
	}

	private static UUID listingId(String listingJson) {
		// mock token format "item:<uuid>:<label>"
		return UUID.fromString(listingJson.split(":")[1]);
	}

	private static void check(String name, boolean ok) {
		checks++;
		if (!ok) {
			System.out.println("FAIL: " + name);
			System.exit(1);
		}
	}

	public static void main(String[] args) {
		UUID seller = UUID.randomUUID();
		UUID buyer = UUID.randomUUID();
		UUID listingId = UUID.randomUUID();
		String item = "item:" + listingId + ":diamond_sword";
		long price = 30;

		// ---- crash matrix ----
		// list: [journalWrite, takeHand, escrowPut, clear]
		long[][] listExpect = {
				// copies, escrowHas(1/0), handHas(1/0)
				{1, 0, 1}, {1, 0, 1}, {0, 0, 0}, {1, 1, 0}, {1, 1, 0}};
		for (int ks = 0; ks <= 4; ks++) {
			MockStore s = base();
			s.balances.put(seller, 0L);
			s.moneyDisk.put(seller, 0L);
			s.hands.put(seller, item);
			s.killAtStep = ks;
			try {
				TxOps.list(s, listingId, seller, price, item);
			} catch (Crash ignored) {
			}
			MockStore w = s.afterCrash();
			TxOps.recover(w);
			check("list ks=" + ks + " copies", w.itemCopies(item) == listExpect[ks][0]);
			check("list ks=" + ks + " escrow", w.escrowHas(listingId) == (listExpect[ks][1] == 1));
			check("list ks=" + ks + " hand", w.hands.containsValue(item) == (listExpect[ks][2] == 1));
			check("list ks=" + ks + " money", w.totalMoney() == 0);
			check("list ks=" + ks + " journal closed", w.journal == null || ks == 0);
		}

		// buy: [journalWrite, moveMoney, flushMoney, mark, escrowRemove, mark, deliver, clear]
		long[][] buyExpect = {
				// buyer, seller, copies, escrowHas(1/0)
				{100, 0, 1, 1}, {100, 0, 1, 1}, {100, 0, 1, 1}, {70, 30, 1, 1},
				{100, 0, 1, 1}, {100, 0, 0, 0}, {70, 30, 0, 0}, {70, 30, 1, 0}, {70, 30, 1, 0}};
		for (int ks = 0; ks <= 8; ks++) {
			MockStore s = base();
			s.balances.put(buyer, 100L);
			s.balances.put(seller, 0L);
			s.moneyDisk.put(buyer, 100L);
			s.moneyDisk.put(seller, 0L);
			s.escrow.put(listingId, item);
			s.escrowDisk.put(listingId, item);
			s.killAtStep = ks;
			try {
				TxOps.buy(s, listingId, seller, buyer, price, item);
			} catch (Crash ignored) {
			}
			MockStore w = s.afterCrash();
			TxOps.recover(w);
			check("buy ks=" + ks + " buyer", w.balances.get(buyer) == buyExpect[ks][0]);
			check("buy ks=" + ks + " seller", w.balances.get(seller) == buyExpect[ks][1]);
			check("buy ks=" + ks + " copies", w.itemCopies(item) == buyExpect[ks][2]);
			check("buy ks=" + ks + " escrow", w.escrowHas(listingId) == (buyExpect[ks][3] == 1));
			check("buy ks=" + ks + " money conserved", w.totalMoney() == 100);
		}

		// cancel: [journalWrite, escrowRemove, mark, deliver, clear]
		long[][] cancelExpect = {{1, 1}, {1, 1}, {0, 0}, {0, 0}, {1, 0}, {1, 0}};
		for (int ks = 0; ks <= 5; ks++) {
			MockStore s = base();
			s.escrow.put(listingId, item);
			s.escrowDisk.put(listingId, item);
			s.killAtStep = ks;
			try {
				TxOps.cancel(s, listingId, seller, price, item);
			} catch (Crash ignored) {
			}
			MockStore w = s.afterCrash();
			TxOps.recover(w);
			check("cancel ks=" + ks + " copies", w.itemCopies(item) == cancelExpect[ks][0]);
			check("cancel ks=" + ks + " escrow", w.escrowHas(listingId) == (cancelExpect[ks][1] == 1));
		}

		// ---- recovery crashing mid-refund never refunds twice ----
		for (int ks2 = 0; ks2 <= 4; ks2++) {
			MockStore s = base();
			s.balances.put(buyer, 100L);
			s.balances.put(seller, 0L);
			s.moneyDisk.put(buyer, 100L);
			s.moneyDisk.put(seller, 0L);
			s.escrow.put(listingId, item);
			s.escrowDisk.put(listingId, item);
			s.killAtStep = 4; // crash after money_moved mark: refund path
			try {
				TxOps.buy(s, listingId, seller, buyer, price, item);
			} catch (Crash ignored) {
			}
			MockStore w = s.afterCrash();
			w.killAtStep = ks2; // crash the recovery itself
			try {
				TxOps.recover(w);
			} catch (Crash ignored) {
			}
			MockStore w2 = w.afterCrash();
			TxOps.recover(w2);
			check("double recovery ks2=" + ks2 + " conserved", w2.totalMoney() == 100);
			long b = w2.balances.get(buyer);
			long se = w2.balances.get(seller);
			check("double recovery ks2=" + ks2 + " valid end state",
					(b == 100 && se == 0) || (b == 70 && se == 30));
			check("double recovery ks2=" + ks2 + " copies<=1", w2.itemCopies(item) <= 1);
		}

		// ---- write failure aborts ----
		// buy: journal write fails -> nothing moved
		MockStore f1 = buySetup(buyer, seller, listingId, item);
		f1.failAtStep = 0;
		check("buy fail journal", TxOps.buy(f1, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("buy fail journal state", f1.balances.get(buyer) == 100 && f1.balances.get(seller) == 0
				&& f1.escrowHas(listingId) && f1.itemCopies(item) == 1 && f1.journal == null);

		// buy: money flush fails -> payment reversed
		MockStore f2 = buySetup(buyer, seller, listingId, item);
		f2.failAtStep = 2;
		check("buy fail flush", TxOps.buy(f2, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("buy fail flush refunded", f2.balances.get(buyer) == 100 && f2.balances.get(seller) == 0);
		check("buy fail flush state", f2.escrowHas(listingId) && f2.itemCopies(item) == 1 && f2.journal == null);

		// buy: escrow remove fails -> payment reversed, listing kept
		MockStore f3 = buySetup(buyer, seller, listingId, item);
		f3.failAtStep = 4;
		check("buy fail remove", TxOps.buy(f3, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("buy fail remove refunded", f3.balances.get(buyer) == 100 && f3.balances.get(seller) == 0);
		check("buy fail remove state", f3.escrowHas(listingId) && f3.itemCopies(item) == 1 && f3.journal == null);

		// list: escrow put fails -> item returned
		MockStore f4 = base();
		f4.hands.put(seller, item);
		f4.failAtStep = 2;
		check("list fail put", TxOps.list(f4, listingId, seller, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("list fail put state", f4.hands.containsValue(item) && !f4.escrowHas(listingId) && f4.journal == null);

		// cancel: escrow remove fails -> listing kept
		MockStore f5 = base();
		f5.escrow.put(listingId, item);
		f5.escrowDisk.put(listingId, item);
		f5.failAtStep = 1;
		check("cancel fail remove", TxOps.cancel(f5, listingId, seller, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("cancel fail remove state", f5.escrowHas(listingId) && f5.itemCopies(item) == 1 && f5.journal == null);

		// ---- money guard rails ----
		MockStore poor = buySetup(buyer, seller, listingId, item);
		poor.balances.put(buyer, 10L);
		poor.moneyDisk.put(buyer, 10L);
		check("buy insufficient", TxOps.buy(poor, listingId, seller, buyer, price, item) == TxOps.Outcome.INSUFFICIENT);
		check("buy insufficient unchanged", poor.totalMoney() == 10 && poor.itemCopies(item) == 1 && poor.journal == null);

		MockStore full = buySetup(buyer, seller, listingId, item);
		full.balances.put(seller, 990L);
		full.moneyDisk.put(seller, 990L);
		check("buy seller full", TxOps.buy(full, listingId, seller, buyer, price, item) == TxOps.Outcome.SELLER_FULL);
		check("buy seller full unchanged", full.totalMoney() == 1090 && full.balances.get(buyer) == 100
				&& full.balances.get(seller) == 990 && full.itemCopies(item) == 1 && full.journal == null);

		// happy paths
		MockStore ok = buySetup(buyer, seller, listingId, item);
		check("buy ok", TxOps.buy(ok, listingId, seller, buyer, price, item) == TxOps.Outcome.OK);
		check("buy ok state", ok.balances.get(buyer) == 70 && ok.balances.get(seller) == 30
				&& ok.itemCopies(item) == 1 && ok.deliveries.size() == 1 && !ok.escrowHas(listingId) && ok.journal == null);

		MockStore okl = base();
		okl.hands.put(seller, item);
		check("list ok", TxOps.list(okl, listingId, seller, price, item) == TxOps.Outcome.OK);
		check("list ok state", okl.escrowHas(listingId) && okl.itemCopies(item) == 1 && okl.journal == null);

		MockStore okc = base();
		okc.escrow.put(listingId, item);
		okc.escrowDisk.put(listingId, item);
		check("cancel ok", TxOps.cancel(okc, listingId, seller, price, item) == TxOps.Outcome.OK);
		check("cancel ok state", okc.itemCopies(item) == 1 && okc.deliveries.size() == 1 && !okc.escrowHas(listingId) && okc.journal == null);

		System.out.println("PASS " + checks + " assertions");
	}

	private static MockStore base() {
		return new MockStore();
	}

	private static MockStore buySetup(UUID buyer, UUID seller, UUID listingId, String item) {
		MockStore s = new MockStore();
		s.balances.put(buyer, 100L);
		s.balances.put(seller, 0L);
		s.moneyDisk.put(buyer, 100L);
		s.moneyDisk.put(seller, 0L);
		s.escrow.put(listingId, item);
		s.escrowDisk.put(listingId, item);
		return s;
	}
}
