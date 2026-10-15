package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.util.List;

/** 固定版本已准入的离心布局；实体入口还须核对真实进程数，不能按类继承放行附属等级。 */
public final class CentrifugeWorkLayout {
	public static final List<String> MACHINES = List.of("productivebeesgenesis:mek_centrifuge",
			"productivebeesgenesis:basic_mek_centrifuge_factory", "productivebeesgenesis:advanced_mek_centrifuge_factory",
			"productivebeesgenesis:elite_mek_centrifuge_factory", "productivebeesgenesis:ultimate_mek_centrifuge_factory");
	public static int lanes(String machine) {
		int index = MACHINES.indexOf(machine);
		return index < 0 ? 0 : 1 + index * 2;
	}
	private CentrifugeWorkLayout() { }
}
