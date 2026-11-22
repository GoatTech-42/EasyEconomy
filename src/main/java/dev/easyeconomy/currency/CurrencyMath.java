package dev.easyeconomy.currency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Pure denomination splitting for physical currency. Denominations must
// contain 1 so every amount splits exactly.
public final class CurrencyMath {

	// Splits amount into the fewest currency items, biggest first.
	// Returns denomination -> count, insertion order biggest to smallest.
	public static Map<Long, Long> split(long amount, List<Long> denominations) {
		if (amount < 0) {
			throw new IllegalArgumentException("negative amount");
		}
		List<Long> sorted = validate(denominations);
		Map<Long, Long> out = new LinkedHashMap<>();
		long rest = amount;
		for (int i = sorted.size() - 1; i >= 0; i--) {
			long d = sorted.get(i);
			long n = rest / d;
			if (n > 0) {
				out.put(d, n);
				rest -= n * d;
			}
		}
		return out;
	}

	// Sorted unique denominations, ascending. Throws unless 1 is present
	// and every value is positive.
	public static List<Long> validate(List<Long> denominations) {
		if (denominations == null || denominations.isEmpty()) {
			throw new IllegalArgumentException("no denominations");
		}
		List<Long> sorted = new ArrayList<>(denominations);
		if (sorted.contains(null)) {
			throw new IllegalArgumentException("null denomination");
		}
		Collections.sort(sorted);
		long prev = 0;
		for (long d : sorted) {
			if (d <= 0) {
				throw new IllegalArgumentException("non-positive denomination");
			}
			if (d == prev) {
				throw new IllegalArgumentException("duplicate denomination");
			}
			prev = d;
		}
		if (sorted.get(0) != 1) {
			throw new IllegalArgumentException("denominations must contain 1");
		}
		return sorted;
	}

	// Total value of a denomination -> count map, overflow-safe against max.
	public static long total(Map<Long, Long> items, long max) {
		long sum = 0;
		for (Map.Entry<Long, Long> e : items.entrySet()) {
			if (e.getKey() <= 0 || e.getValue() < 0) {
				throw new IllegalArgumentException("bad item entry");
			}
			if (e.getValue() > 0 && e.getKey() > (max - sum) / e.getValue()) {
				throw new ArithmeticException("total exceeds max");
			}
			sum += e.getKey() * e.getValue();
		}
		return sum;
	}
}
