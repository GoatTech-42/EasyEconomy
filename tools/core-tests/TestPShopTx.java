import dev.easyeconomy.pshop.EscrowStore;
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
		// the REAL production escrow logic; the flush here only injects faults
		// and snapshots what would be on disk
		EscrowStore store = new EscrowStore(this::flushEscrow);
		java.util.LinkedHashMap<UUID, String> escrowDisk = new java.util.LinkedHashMap<>();
		Map<UUID, Long> balances = new HashMap<>();
		Map<UUID, Long> moneyDisk = new HashMap<>();
		// world state: live (memory) vs durable (last forced player save)
		Map<UUID, String> hands = new HashMap<>();
		Map<UUID, String> handsDisk = new HashMap<>();
		List<String> deliveries = new ArrayList<>();
		List<String> deliveriesDisk = new ArrayList<>();
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

		private void flushEscrow(List<String> listingJsons) throws TxException {
			if (writeFails()) {
				throw new TxException("injected", null);
			}
			escrowDisk.clear();
			for (String json : listingJsons) {
				escrowDisk.put(listingId(json), json);
			}
		}

		@Override
		public boolean escrowHas(UUID id) {
			return store.has(id);
		}

		@Override
		public void escrowPutPersist(String listingJson) throws TxException {
			checkpoint();
			store.putPersist(listingId(listingJson), listingJson);
		}

		@Override
		public void escrowRemovePersist(UUID id, String listingJson) throws TxException {
			checkpoint();
			store.removePersist(id);
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
		public void takeItem(UUID player, String listingJson) {
			checkpoint();
			deliveries.remove(listingJson);
		}

		@Override
		public long balanceOf(UUID player) {
			return balances.getOrDefault(player, 0L);
		}

		@Override
		public void savePlayer(UUID player) {
			checkpoint();
			// the forced save makes the whole world snapshot durable, like
			// vanilla's PlayerList.saveAll persists every player's data
			handsDisk = new HashMap<>(hands);
			deliveriesDisk = new ArrayList<>(deliveries);
		}

		@Override
		public void warn(String msg) {
			logs.add("warn:" + msg);
		}

		@Override
		public void error(String msg) {
			logs.add("error:" + msg);
		}

		// a fresh post-crash world: service memory comes from disk, and the
		// physical world rolls back to the last forced player save, which is
		// the worst vanilla can do to inventories
		MockStore afterCrash() {
			MockStore next = new MockStore();
			next.store.loadAll(escrowDisk.values(), TestPShopTx::listingId);
			next.escrowDisk = new java.util.LinkedHashMap<>(escrowDisk);
			next.balances = new HashMap<>(moneyDisk);
			next.moneyDisk = new HashMap<>(moneyDisk);
			next.journal = journal;
			next.hands = new HashMap<>(handsDisk);
			next.handsDisk = new HashMap<>(handsDisk);
			next.deliveries = new ArrayList<>(deliveriesDisk);
			next.deliveriesDisk = new ArrayList<>(deliveriesDisk);
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
			for (String json : store.all()) {
				if (json.equals(listingJson)) {
					n++;
				}
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

		// ---- EscrowStore rollback, the exact production code ----
		EscrowStore direct = new EscrowStore(jsons -> {
			throw new TxException("disk dead", null);
		});
		try {
			direct.putPersist(listingId, item);
			check("putPersist threw", false);
		} catch (TxException expected) {
		}
		check("failed put leaves no listing in memory", !direct.has(listingId));
		EscrowStore direct2 = new EscrowStore(jsons -> {
			if (jsons.isEmpty()) {
				throw new TxException("disk dead", null);
			}
		});
		try {
			direct2.putPersist(listingId, item);
			direct2.removePersist(listingId);
			check("removePersist threw", false);
		} catch (TxException expected) {
		}
		check("failed remove keeps the listing in memory", direct2.has(listingId));

		// ---- crash matrix ----
		// list steps: [journalWrite, takeHand, savePlayer, escrowPut, clear]
		long[][] listExpect = {
				// copies, escrowHas, handHas
				{1, 0, 1}, {1, 0, 1}, {1, 0, 1}, {0, 0, 0}, {1, 1, 0}, {1, 1, 0}};
		for (int ks = 0; ks <= 5; ks++) {
			MockStore s = new MockStore();
			s.balances.put(seller, 0L);
			s.moneyDisk.put(seller, 0L);
			s.hands.put(seller, item);
			s.handsDisk.put(seller, item);
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
		}

		// buy steps: [journalWrite, moveMoney, flushMoney, mark money_moved,
		//   escrowRemove, mark listing_removed, deliver, mark delivering,
		//   savePlayer, clear]
		long[][] buyExpect = {
				// buyer, seller, copies, escrowHas
				{100, 0, 1, 1}, // ks0: nothing ran
				{100, 0, 1, 1}, // ks1: intent only, no money moved
				{100, 0, 1, 1}, // ks2: money moved in memory, rolled back
				{100, 0, 1, 1}, // ks3: money durable, escrow intact: refund
				{100, 0, 1, 1}, // ks4: money_moved marked: refund
				{100, 0, 0, 0}, // ks5: escrow removed: refund, item lost
				{100, 0, 0, 0}, // ks6: listing_removed marked: refund, lost
				{100, 0, 0, 0}, // ks7: delivered in memory, rolled back: refund
				{70, 30, 0, 0},  // ks8: delivering marked: treated delivered, item lost
				{70, 30, 1, 0},  // ks9: delivery saved
				{70, 30, 1, 0}}; // ks10: complete
		for (int ks = 0; ks <= 10; ks++) {
			MockStore s = buySetup(buyer, seller, listingId, item);
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

		// cancel steps: [journalWrite, escrowRemove, mark listing_removed,
		//   deliver, mark delivering, savePlayer, clear]
		long[][] cancelExpect = {{1, 1}, {1, 1}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {1, 0}, {1, 0}};
		for (int ks = 0; ks <= 7; ks++) {
			MockStore s = new MockStore();
			s.store.loadAll(List.of(item), TestPShopTx::listingId);
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

		// ---- recovery crashing mid-refund: strict oracle ----
		// crash the first buy after the money_moved mark (escrow intact),
		// then crash the recovery itself at each of its steps:
		// [moveMoney, flushMoney, journalClear]. However often recovery is
		// interrupted, the final state must be the exact promised refund:
		// buyer back at 100, seller at 0, the item still in escrow.
		for (int ks2 = 0; ks2 <= 3; ks2++) {
			MockStore s = buySetup(buyer, seller, listingId, item);
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
			check("double recovery ks2=" + ks2 + " exact refund",
					w2.balances.get(buyer) == 100 && w2.balances.get(seller) == 0);
			check("double recovery ks2=" + ks2 + " item in escrow",
					w2.itemCopies(item) == 1 && w2.escrowHas(listingId));
			check("double recovery ks2=" + ks2 + " journal closed", w2.journal == null);
		}

		// ---- write failure aborts ----
		MockStore f1 = buySetup(buyer, seller, listingId, item);
		f1.failAtStep = 0;
		check("buy fail journal", TxOps.buy(f1, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("buy fail journal state", f1.balances.get(buyer) == 100 && f1.balances.get(seller) == 0
				&& f1.escrowHas(listingId) && f1.itemCopies(item) == 1 && f1.journal == null);

		MockStore f2 = buySetup(buyer, seller, listingId, item);
		f2.failAtStep = 2;
		check("buy fail flush", TxOps.buy(f2, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("buy fail flush refunded", f2.balances.get(buyer) == 100 && f2.balances.get(seller) == 0);
		check("buy fail flush state", f2.escrowHas(listingId) && f2.itemCopies(item) == 1 && f2.journal == null);

		MockStore f3 = buySetup(buyer, seller, listingId, item);
		f3.failAtStep = 4;
		check("buy fail remove", TxOps.buy(f3, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("buy fail remove refunded", f3.balances.get(buyer) == 100 && f3.balances.get(seller) == 0);
		check("buy fail remove state", f3.escrowHas(listingId) && f3.itemCopies(item) == 1 && f3.journal == null);

		// list abort: the QA blocker. the real EscrowStore must roll back, the
		// item goes back to the seller, and no listing survives in memory
		MockStore f4 = new MockStore();
		f4.hands.put(seller, item);
		f4.handsDisk.put(seller, item);
		f4.failAtStep = 3; // escrowPut's flush
		check("list fail put", TxOps.list(f4, listingId, seller, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("list fail put: item returned", f4.hands.containsValue(item));
		check("list fail put: no live listing", !f4.escrowHas(listingId));
		check("list fail put: nothing on disk", f4.escrowDisk.isEmpty());
		check("list fail put: journal closed", f4.journal == null);
		// and a crash right after that abort still finds exactly one item
		MockStore f4w = f4.afterCrash();
		TxOps.recover(f4w);
		check("list abort + crash: copies", f4w.itemCopies(item) == 1);

		MockStore f5 = new MockStore();
		f5.store.loadAll(List.of(item), TestPShopTx::listingId);
		f5.escrowDisk.put(listingId, item);
		f5.failAtStep = 1;
		check("cancel fail remove", TxOps.cancel(f5, listingId, seller, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("cancel fail remove state", f5.escrowHas(listingId) && f5.itemCopies(item) == 1 && f5.journal == null);

		// buy: the listing_removed mark fails after the escrow removal is
		// durable. No delivery may happen; recovery refunds from ground truth.
		MockStore f6 = buySetup(buyer, seller, listingId, item);
		f6.failAtStep = 5; // journalWrite(mark listing_removed)
		check("buy fail lr-mark", TxOps.buy(f6, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("buy fail lr-mark: not delivered", f6.deliveries.isEmpty());
		check("buy fail lr-mark: journal kept", f6.journal != null);
		MockStore f6w = f6.afterCrash();
		TxOps.recover(f6w);
		check("buy fail lr-mark: recovered refund", f6w.balances.get(buyer) == 100 && f6w.balances.get(seller) == 0);
		check("buy fail lr-mark: item lost exactly once", f6w.itemCopies(item) == 0 && !f6w.escrowHas(listingId));
		check("buy fail lr-mark: journal closed", f6w.journal == null);

		// buy: the delivering mark fails after the in-memory delivery. The
		// item must be recalled so a later autosave cannot dupe it against
		// the recovery refund.
		MockStore f7 = buySetup(buyer, seller, listingId, item);
		f7.failAtStep = 7; // journalWrite(mark delivering)
		check("buy fail dlv-mark", TxOps.buy(f7, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("buy fail dlv-mark: item recalled", f7.deliveries.isEmpty());
		MockStore f7w = f7.afterCrash();
		TxOps.recover(f7w);
		check("buy fail dlv-mark: recovered refund", f7w.balances.get(buyer) == 100 && f7w.balances.get(seller) == 0);
		check("buy fail dlv-mark: no dupe", f7w.itemCopies(item) == 0);

		// ---- pending-transaction gate ----
		// an unreadable journal blocks new operations entirely
		MockStore g1 = buySetup(buyer, seller, listingId, item);
		g1.journal = "this is not\na valid journal";
		check("gate: junk journal blocks", TxOps.buy(g1, listingId, seller, buyer, price, item) == TxOps.Outcome.ABORTED_WRITE_FAILED);
		check("gate: junk journal kept", g1.journal != null);
		check("gate: junk journal untouched", g1.balances.get(buyer) == 100 && g1.itemCopies(item) == 1);

		// a leftover crashed-buy journal is recovered by the next operation,
		// which then proceeds normally
		MockStore g2pre = buySetup(buyer, seller, listingId, item);
		g2pre.killAtStep = 4; // crash after money_moved mark
		try {
			TxOps.buy(g2pre, listingId, seller, buyer, price, item);
		} catch (Crash ignored) {
		}
		MockStore g2 = g2pre.afterCrash();
		UUID item2id = UUID.randomUUID();
		String item2 = "item:" + item2id + ":emerald";
		g2.hands.put(seller, item2);
		g2.handsDisk.put(seller, item2);
		check("gate: auto-recover + list ok", TxOps.list(g2, item2id, seller, 5, item2) == TxOps.Outcome.OK);
		check("gate: auto-recover refunded", g2.balances.get(buyer) == 100 && g2.balances.get(seller) == 0);
		check("gate: auto-recover listed", g2.escrowHas(item2id));
		check("gate: auto-recover old listing intact", g2.escrowHas(listingId));

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

		MockStore okl = new MockStore();
		okl.hands.put(seller, item);
		okl.handsDisk.put(seller, item);
		check("list ok", TxOps.list(okl, listingId, seller, price, item) == TxOps.Outcome.OK);
		check("list ok state", okl.escrowHas(listingId) && okl.itemCopies(item) == 1 && okl.journal == null);

		MockStore okc = new MockStore();
		okc.store.loadAll(List.of(item), TestPShopTx::listingId);
		okc.escrowDisk.put(listingId, item);
		check("cancel ok", TxOps.cancel(okc, listingId, seller, price, item) == TxOps.Outcome.OK);
		check("cancel ok state", okc.itemCopies(item) == 1 && okc.deliveries.size() == 1 && !okc.escrowHas(listingId) && okc.journal == null);

		System.out.println("PASS " + checks + " assertions");
	}

	private static MockStore buySetup(UUID buyer, UUID seller, UUID listingId, String item) {
		MockStore s = new MockStore();
		s.balances.put(buyer, 100L);
		s.balances.put(seller, 0L);
		s.moneyDisk.put(buyer, 100L);
		s.moneyDisk.put(seller, 0L);
		s.store.loadAll(List.of(item), TestPShopTx::listingId);
		s.escrowDisk.put(listingId, item);
		return s;
	}
}
