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
