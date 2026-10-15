package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.util.List;
import java.util.stream.Stream;

/** 固定版本已准入的离心布局；实体入口还须核对真实进程数，不能按类继承放行附属等级。 */
public final class CentrifugeWorkLayout {
	public static final List<String> ORDINARY_MACHINES = List.of("productivebeesgenesis:mek_centrifuge",
			"productivebeesgenesis:basic_mek_centrifuge_factory", "productivebeesgenesis:advanced_mek_centrifuge_factory",
			"productivebeesgenesis:elite_mek_centrifuge_factory", "productivebeesgenesis:ultimate_mek_centrifuge_factory");
	public static final List<String> EVOLVED_MACHINES = List.of("productivebeesgenesis:overclocked_mek_centrifuge_factory",
			"productivebeesgenesis:quantum_mek_centrifuge_factory", "productivebeesgenesis:dense_mek_centrifuge_factory",
			"productivebeesgenesis:multiversal_mek_centrifuge_factory", "productivebeesgenesis:creative_mek_centrifuge_factory");
	public static final List<String> MACHINES = Stream.concat(ORDINARY_MACHINES.stream(), EVOLVED_MACHINES.stream()).toList();
	public static int lanes(String machine) {
		int index = MACHINES.indexOf(machine);
		return index < 0 ? 0 : 1 + index * 2;
	}
	private CentrifugeWorkLayout() { }
}
