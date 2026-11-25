import dev.easyeconomy.economy.EconomyCore;
import dev.easyeconomy.economy.JsonlLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Plain java harness for the economy core. No minecraft, no framework:
// prints PASS and exits 0, or prints the failure and exits 1.
public final class TestEconomyCore {
	private static int checks;

	public static void main(String[] args) throws Exception {
		Path dir = Files.createTempDirectory("ee-core-test");
		Path ledgerFile = dir.resolve("ledger.jsonl");
		JsonlLedger ledger = new JsonlLedger(ledgerFile, () -> 1000L);

		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();

		EconomyCore core = new EconomyCore(50, 1000, ledger);

		check("new player starts at starting balance", core.balance(a) == 50);

		check("deposit ok", core.deposit(a, 25, "test") == EconomyCore.Result.OK);
		check("deposit adds", core.balance(a) == 75);

		check("zero deposit invalid", core.deposit(a, 0, "test") == EconomyCore.Result.INVALID_AMOUNT);
		check("negative deposit invalid", core.deposit(a, -5, "test") == EconomyCore.Result.INVALID_AMOUNT);
		check("balance unchanged after invalid", core.balance(a) == 75);

		check("deposit over max rejected", core.deposit(a, 926, "test") == EconomyCore.Result.OVER_MAX_BALANCE);
		check("deposit to exactly max ok", core.deposit(a, 925, "test") == EconomyCore.Result.OK);
		check("at max", core.balance(a) == 1000);
		check("huge deposit cannot overflow long", core.deposit(b, Long.MAX_VALUE, "test") == EconomyCore.Result.OVER_MAX_BALANCE);
		check("huge deposit leaves starting balance", core.balance(b) == 50);

		check("withdraw over balance rejected", core.withdraw(b, 51, "test") == EconomyCore.Result.INSUFFICIENT_FUNDS);
		check("withdraw exact ok", core.withdraw(b, 50, "test") == EconomyCore.Result.OK);
		check("withdraw to zero", core.balance(b) == 0);
		check("withdraw at zero rejected", core.withdraw(b, 1, "test") == EconomyCore.Result.INSUFFICIENT_FUNDS);

		check("transfer ok", core.transfer(a, b, 100, "test") == EconomyCore.Result.OK);
		check("transfer debits sender", core.balance(a) == 900);
		check("transfer credits recipient", core.balance(b) == 100);
		check("self transfer rejected", core.transfer(a, a, 1, "test") == EconomyCore.Result.SELF_TRANSFER);
		check("transfer over funds rejected", core.transfer(b, a, 101, "test") == EconomyCore.Result.INSUFFICIENT_FUNDS);
		check("failed transfer leaves both balances", core.balance(a) == 900 && core.balance(b) == 100);
		check("set to max for cap test", core.set(a, 1000, "test") == EconomyCore.Result.OK);
		check("transfer onto nearly-maxed target rejected", core.transfer(a, b, 901, "test") == EconomyCore.Result.OVER_MAX_BALANCE);
		check("sender not debited on capped transfer", core.balance(a) == 1000);
		check("set a back", core.set(a, 900, "test") == EconomyCore.Result.OK);

		check("set ok", core.set(b, 7, "test") == EconomyCore.Result.OK);
		check("set applies", core.balance(b) == 7);
		check("set above max rejected", core.set(b, 1001, "test") == EconomyCore.Result.INVALID_AMOUNT);
		check("set negative rejected", core.set(b, -1, "test") == EconomyCore.Result.INVALID_AMOUNT);

		Map<UUID, Long> snap = core.snapshot();
		EconomyCore reloaded = new EconomyCore(50, 1000, null);
		reloaded.loadAll(snap);
		check("snapshot round trip", reloaded.balance(a) == 900 && reloaded.balance(b) == 7);

		ledger.close();
		List<String> lines = Files.readAllLines(ledgerFile);
		check("ledger has one line per change", lines.size() == 7);
		boolean hasTransfer = false;
		boolean hasReason = true;
		for (String line : lines) {
			if (line.contains("\"action\":\"transfer\"")) {
				hasTransfer = true;
			}
			if (!line.contains("\"reason\":\"test\"")) {
				hasReason = false;
			}
		}
		check("ledger records transfers", hasTransfer);
		check("ledger records reasons", hasReason);


		// currency denomination splitting
		java.util.List<Long> denoms = java.util.List.of(1L, 5L, 10L, 50L, 100L);
		java.util.Map<Long, Long> split = dev.easyeconomy.currency.CurrencyMath.split(0, denoms);
		check("zero splits to nothing", split.isEmpty());
		split = dev.easyeconomy.currency.CurrencyMath.split(187, denoms);
		check("187 splits fewest first", split.toString().equals("{100=1, 50=1, 10=3, 5=1, 1=2}"));
		check("split totals back", dev.easyeconomy.currency.CurrencyMath.total(split, 1000000) == 187);
		split = dev.easyeconomy.currency.CurrencyMath.split(4, denoms);
		check("small amount uses ones", split.toString().equals("{1=4}"));
		boolean threw = false;
		try {
			dev.easyeconomy.currency.CurrencyMath.validate(java.util.List.of(5L, 10L));
		} catch (IllegalArgumentException e) {
			threw = true;
		}
		check("denominations without 1 rejected", threw);
		threw = false;
		try {
			dev.easyeconomy.currency.CurrencyMath.split(-1, denoms);
		} catch (IllegalArgumentException e) {
			threw = true;
		}
		check("negative split rejected", threw);
		threw = false;
		try {
			java.util.Map<Long, Long> huge = java.util.Map.of(1000000000L, 1000000000L);
			dev.easyeconomy.currency.CurrencyMath.total(huge, 1000000);
		} catch (ArithmeticException e) {
			threw = true;
		}
		check("total overflow rejected", threw);

		// race test: many threads transferring random amounts between the same
		// accounts. total money in the system must never change, no matter how
		// the operations interleave. this catches lost-update dupes.
		EconomyCore race = new EconomyCore(1000, 1000000, new dev.easyeconomy.economy.JsonlLedger(dir.resolve("race.jsonl"), () -> 2000L));
		java.util.List<UUID> racers = new java.util.ArrayList<>();
		for (int i = 0; i < 8; i++) {
			UUID u = UUID.randomUUID();
			racers.add(u);
			race.deposit(u, 9000, "seed");
		}
		long seededTotal = racers.stream().mapToLong(race::balance).sum();
		check("seeded balances", seededTotal == 8 * 10000);
		java.util.List<Thread> threads = new java.util.ArrayList<>();
		java.util.concurrent.atomic.AtomicInteger ops = new java.util.concurrent.atomic.AtomicInteger();
		for (int t = 0; t < 8; t++) {
			int me = t;
			Thread th = new Thread(() -> {
				java.util.Random rng = new java.util.Random(me);
				for (int i = 0; i < 500; i++) {
					UUID other = racers.get(rng.nextInt(racers.size()));
					if (other == racers.get(me)) {
						continue;
					}
					long amount = 1 + rng.nextInt(500);
					race.transfer(racers.get(me), other, amount, "race");
					ops.incrementAndGet();
				}
			});
			th.start();
			threads.add(th);
		}
		for (Thread th : threads) {
			th.join();
		}
		long finalTotal = racers.stream().mapToLong(race::balance).sum();
		check("no money created or destroyed under race", finalTotal == seededTotal);
		check("race did real work", ops.get() > 3000);

		// compact titles: exact under 1000 with singular/plural, one trimmed
		// decimal above, always plural once abbreviated
		check("compact zero", EconomyCore.compact(0, "coin", "coins").equals("0 coins"));
		check("compact singular", EconomyCore.compact(1, "coin", "coins").equals("1 coin"));
		check("compact under 1000", EconomyCore.compact(999, "coin", "coins").equals("999 coins"));
		check("compact exact k", EconomyCore.compact(1_000, "coin", "coins").equals("1k coins"));
		check("compact k decimal", EconomyCore.compact(12_400, "coin", "coins").equals("12.4k coins"));
		check("compact k rounds down", EconomyCore.compact(12_449, "coin", "coins").equals("12.4k coins"));
		check("compact exact M", EconomyCore.compact(1_000_000, "coin", "coins").equals("1M coins"));
		check("compact M decimal", EconomyCore.compact(3_200_000, "coin", "coins").equals("3.2M coins"));
		check("compact B", EconomyCore.compact(1_000_000_000L, "coin", "coins").equals("1B coins"));

		System.out.println("PASS " + checks + " assertions");

	}

	private static void check(String name, boolean ok) {
		checks++;
		if (!ok) {
			System.out.println("FAIL: " + name);
			System.exit(1);
		}
	}
}
