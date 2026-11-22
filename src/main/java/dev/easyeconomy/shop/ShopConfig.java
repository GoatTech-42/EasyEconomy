package dev.easyeconomy.shop;

import java.util.ArrayList;
import java.util.List;

public final class ShopConfig {
	public List<Category> categories = new ArrayList<>();

	public static final class Category {
		public String name = "category";
		public String icon = "minecraft:chest";
		public List<Entry> items = new ArrayList<>();
	}

	public static final class Entry {
		public String item = "";
		public long buyPrice = 0;
		public long sellPrice = 0;
		public BuyLimit buyLimit;

		public String key() {
			return item + "@" + buyPrice;
		}
	}

	public static final class BuyLimit {
		public int count = 0;
		public long seconds = 0;
	}
}
