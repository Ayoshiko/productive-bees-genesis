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
	public static final List<String> EXTRA_MACHINES = List.of("productivebeesgenesis:absolute_extra_mek_centrifuge_factory",
			"productivebeesgenesis:supreme_extra_mek_centrifuge_factory", "productivebeesgenesis:cosmic_extra_mek_centrifuge_factory",
			"productivebeesgenesis:infinite_extra_mek_centrifuge_factory");
	public static final List<String> EVOLVED_EXTRA_MACHINES = List.of("productivebeesgenesis:absolute_overclocked_emextra_mek_centrifuge_factory",
			"productivebeesgenesis:supreme_quantum_emextra_mek_centrifuge_factory", "productivebeesgenesis:cosmic_dense_emextra_mek_centrifuge_factory",
			"productivebeesgenesis:infinite_multiversal_emextra_mek_centrifuge_factory");
	public static final List<String> MACHINES = Stream.of(ORDINARY_MACHINES, EVOLVED_MACHINES, EXTRA_MACHINES, EVOLVED_EXTRA_MACHINES)
			.flatMap(List::stream).toList();
	public static int lanes(String machine) {
		int index = EVOLVED_EXTRA_MACHINES.indexOf(machine);
		if (index >= 0) return 12 + index * 2;
		index = EXTRA_MACHINES.indexOf(machine);
		if (index >= 0) return 11 + index * 2;
		index = EVOLVED_MACHINES.indexOf(machine);
		if (index >= 0) return 11 + index * 2;
		index = ORDINARY_MACHINES.indexOf(machine);
		return index < 0 ? 0 : 1 + index * 2;
	}
	private CentrifugeWorkLayout() { }
}
