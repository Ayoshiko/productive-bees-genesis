package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

/** 菜单线程消费序号；拒绝请求也不能在稍后重放，不保存历史动作集合。 */
public final class TerminalSequence {
	private long last;
	private boolean busy, closed;
	public boolean begin(long sequence) {
		if (closed || busy || sequence <= last) return false;
		last = sequence; busy = true; return true;
	}
	public void finish() { busy = false; }
	public void close() { closed = true; }
}
