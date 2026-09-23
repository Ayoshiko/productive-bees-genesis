package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

/** 每玩家共享窗口：每 tick 至多两次、每 20 tick 至多八次，换菜单不重置。 */
public final class TerminalRateBudget {
	private long window = -1, tick = -1;
	private int windowCount, tickCount;
	public boolean accept(long now) {
		if (now < 0) return false;
		if (window < 0 || now < window || now - window >= 20) { window = now; windowCount = 0; }
		if (now != tick) { tick = now; tickCount = 0; }
		if (windowCount >= 8 || tickCount >= 2) return false;
		windowCount++; tickCount++; return true;
	}
}
