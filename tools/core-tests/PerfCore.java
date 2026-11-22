import dev.easyeconomy.currency.CurrencyMath;
import dev.easyeconomy.economy.EconomyCore;
import dev.easyeconomy.economy.JsonlLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

// Performance measurement for the economy core, run manually via
// `gradle perfCore`. Prints timings; exits nonzero only when something is
// so slow it would matter on a real server (generous bounds, this is a
// regression tripwire not a benchmark gate).
public class PerfCore {
	public static void main(String[] args) throws Exception {
		Path dir = Files.createTempDirectory("ee-perf");
		boolean ok = true;

		// 1. transfer throughput with a live ledger
		EconomyCore core = new EconomyCore(0, 10_000_000_000L, new JsonlLedger(dir.resolve("ledger.jsonl"), () -> 1L));
		UUID[] ids = new UUID[1000];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = UUID.randomUUID();
			core.deposit(ids[i], 1_000_000, "seed");
		}
		int ops = 300_000;
		long t0 = System.nanoTime();
		for (int i = 0; i < ops; i++) {
			core.transfer(ids[i % ids.length], ids[(i * 7 + 1) % ids.length], 1, "perf");
		}
		long elapsed = System.nanoTime() - t0;
		double perSec = ops / (elapsed / 1e9);
		System.out.printf("transfers: %,d ops in %.2fs = %,.0f ops/sec (with ledger append)%n", ops, elapsed / 1e9, perSec);
		ok &= report("transfer throughput > 20k ops/sec", perSec > 20_000);

		// 2. full save cost: snapshot + json + fsync write, 1k and 10k accounts
		for (int accounts : new int[]{1_000, 10_000}) {
			EconomyCore big = new EconomyCore(0, 10_000_000_000L, new JsonlLedger(dir.resolve("l" + accounts + ".jsonl"), () -> 1L));
			for (int i = 0; i < accounts; i++) {
				big.deposit(UUID.randomUUID(), 12345, "seed");
			}
			Path file = dir.resolve("accounts-" + accounts + ".json");
			long best = Long.MAX_VALUE;
			for (int rep = 0; rep < 5; rep++) {
				long s = System.nanoTime();
				Map<String, Long> raw = new TreeMap<>();
				for (Map.Entry<UUID, Long> e : big.snapshot().entrySet()) {
					raw.put(e.getKey().toString(), e.getValue());
				}
				StringBuilder json = new StringBuilder(raw.size() * 60);
				json.append('{');
				boolean first = true;
				for (Map.Entry<String, Long> e : raw.entrySet()) {
					if (!first) {
						json.append(',');
					}
					first = false;
					json.append('"').append(e.getKey()).append("\":").append(e.getValue());
				}
				json.append('}');
				dev.easyeconomy.util.AtomicFiles.writeAtomic(file, json.toString());
				best = Math.min(best, System.nanoTime() - s);
			}
			System.out.printf("full save (%,d accounts, fsync): %.1f ms best of 5%n", accounts, best / 1e6);
			ok &= report("save " + accounts + " accounts < 1000ms", best < 1_000_000_000L);
		}

		// 3. currency split throughput
		List<Long> denoms = CurrencyMath.validate(List.of(1L, 5L, 10L, 50L, 100L, 1000L, 10000L));
		int splits = 1_000_000;
		t0 = System.nanoTime();
		long total = 0;
		for (int i = 0; i < splits; i++) {
			total += CurrencyMath.total(CurrencyMath.split(i % 100_000, denoms), 10_000_000);
		}
		elapsed = System.nanoTime() - t0;
		System.out.printf("currency split: %,d splits in %.2fs = %,.0f/sec (checksum %d)%n", splits, elapsed / 1e9, splits / (elapsed / 1e9), total % 997);
		ok &= report("split throughput > 100k/sec", splits / (elapsed / 1e9) > 100_000);

		System.out.println(ok ? "PERF OK" : "PERF REGRESSION");
		System.exit(ok ? 0 : 1);
	}

	private static boolean report(String name, boolean pass) {
		System.out.println((pass ? "  ok: " : "  SLOW: ") + name);
		return pass;
	}
}
