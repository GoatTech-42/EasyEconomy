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

	// Bold advance of one code point, from measured vanilla default-font
	// advances: the widest ASCII printable glyphs ('@', '~') are 6px wide,
	// so 6 + 1 spacing + 1 bold = 8; the widest observed unicode-fallback
	// glyph (Æ, U+00C6) measures 9px, so 9 + 1 + 1 = 11. These bounds cover
	// the vanilla providers behind ASCII printable and the unicode pages we
	// measured (latin supplement, cyrillic); they are a policy, not a proof
	// for every code point.
	public static int advance(int codePoint) {
		if (codePoint >= 32 && codePoint < 127) {
			return 8;
		}
		return 11;
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
