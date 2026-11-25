package dev.easyeconomy.gui;

// Pixel-accurate fitting for chest-menu titles under the vanilla default
// font. Server-side we cannot measure glyphs, so this uses a conservative
// supported-glyph policy with per-code-point advances and truncates by code
// points, never splitting a surrogate pair.
public final class TitleFit {

	// Title text starts at x8 and the coin motif sits at x161; painted
	// pixels must stop 2px short of it, so the budget is 161 - 8 - 2 = 151.
	public static final int TITLE_BUDGET_PX = 151;

	public static final String ELLIPSIS = "…";

	private TitleFit() {
	}

	// Bold advance of one code point. ASCII printable glyphs are at most
	// 5px wide: 5 + 1 spacing + 1 bold = 7. Anything outside ASCII printable
	// falls back to the unicode pages, whose glyphs reach 8px wide (Æ is
	// ~8px): 8 + 1 spacing + 1 bold = 10. Narrow ASCII ('i', 'l', 't')
	// overpays, which only shortens a title, never overflows it.
	public static int advance(int codePoint) {
		if (codePoint >= 32 && codePoint < 127) {
			return 7;
		}
		return 10;
	}

	public static int width(String s) {
		return s.codePoints().map(TitleFit::advance).sum();
	}

	// base + " " + tail, truncating base with an ellipsis until the whole
	// title fits the pixel budget.
	public static String fit(String base, String tail) {
		String full = base + " " + tail;
		if (width(full) <= TITLE_BUDGET_PX) {
			return full;
		}
		int[] cps = base.codePoints().toArray();
		int tailW = width(ELLIPSIS + " " + tail);
		int used = 0;
		int keep = 0;
		while (keep < cps.length && used + advance(cps[keep]) + tailW <= TITLE_BUDGET_PX) {
			used += advance(cps[keep]);
			keep++;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < keep; i++) {
			sb.appendCodePoint(cps[i]);
		}
		return sb.append(ELLIPSIS).append(' ').append(tail).toString();
	}
}
