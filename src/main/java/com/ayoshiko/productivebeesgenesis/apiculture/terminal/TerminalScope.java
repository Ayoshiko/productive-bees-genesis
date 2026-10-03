package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

/** 菜单注册类型固定查询范围；客户端不能通过请求切换成员类别。 */
public enum TerminalScope {
	ALL(null), APIARY("productivebeesgenesis:mek_apiary"), CENTRIFUGE("productivebeesgenesis:mek_centrifuge");
	private final String machine;
	TerminalScope(String machine) { this.machine = machine; }
	public String machine() { return machine; }
	public boolean accepts(String candidate) { return machine == null || machine.equals(candidate); }
}
