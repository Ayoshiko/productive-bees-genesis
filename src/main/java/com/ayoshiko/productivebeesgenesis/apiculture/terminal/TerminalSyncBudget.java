package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

/** 每个服务器共享的订阅字节预算；普通资产命令仍使用原来的玩家限流。 */
public final class TerminalSyncBudget {
	public static final int BYTES_PER_TICK = 256 * 1024, BYTES_PER_WINDOW = 2 * 1024 * 1024;
	private long tick = Long.MIN_VALUE, window = Long.MIN_VALUE;
	private int tickBytes, windowBytes;
	public boolean acquire(long now, int bytes) {
		if (now < 0 || bytes < 0 || bytes > TerminalLiveUpdate.MAX_BYTES) return false;
		if (now != tick) { tick = now; tickBytes = 0; }
		if (window == Long.MIN_VALUE || now < window || now - window >= 20) { window = now; windowBytes = 0; }
		if (bytes > BYTES_PER_TICK - tickBytes || bytes > BYTES_PER_WINDOW - windowBytes) return false;
		tickBytes += bytes; windowBytes += bytes; return true;
	}
}
