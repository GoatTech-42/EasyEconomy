package dev.easyeconomy.challenge;

import java.util.ArrayList;
import java.util.List;

public final class ChallengeConfig {
	public List<Challenge> challenges = new ArrayList<>();

	public static final class Challenge {
		public String id = "";
		public String name = "";
		public String description = "";
		public String icon = "minecraft:paper";
		public long reward = 0;
		// advancement | obtain | kill | mine | craft | playtime
		public String type = "";
		public String target = "";
		public int count = 1;
		public boolean repeatable = false;
		public long cooldownSeconds = 0;
	}
}
