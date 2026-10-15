package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

import java.util.List;
import java.util.stream.Stream;

/** 已接入的固定蜂箱布局；保存恢复不依赖注册表或可选工厂类。 */
public record ApiaryWorkLayout(int bees, int feeders) {
	public static final int MAX_BEES = 60;
	public static final List<String> ORDINARY_MACHINES = List.of("productivebeesgenesis:mek_apiary",
			"productivebeesgenesis:basic_mek_apiary_factory", "productivebeesgenesis:advanced_mek_apiary_factory",
			"productivebeesgenesis:elite_mek_apiary_factory", "productivebeesgenesis:ultimate_mek_apiary_factory");
	public static final List<String> EVOLVED_MACHINES = List.of("productivebeesgenesis:overclocked_mek_apiary_factory",
			"productivebeesgenesis:quantum_mek_apiary_factory", "productivebeesgenesis:dense_mek_apiary_factory",
			"productivebeesgenesis:multiversal_mek_apiary_factory", "productivebeesgenesis:creative_mek_apiary_factory");
	public static final List<String> EXTRA_MACHINES = List.of("productivebeesgenesis:absolute_extra_mek_apiary_factory",
			"productivebeesgenesis:supreme_extra_mek_apiary_factory", "productivebeesgenesis:cosmic_extra_mek_apiary_factory",
			"productivebeesgenesis:infinite_extra_mek_apiary_factory");
	public static final List<String> EVOLVED_EXTRA_MACHINES = List.of("productivebeesgenesis:absolute_overclocked_emextra_mek_apiary_factory",
			"productivebeesgenesis:supreme_quantum_emextra_mek_apiary_factory", "productivebeesgenesis:cosmic_dense_emextra_mek_apiary_factory",
			"productivebeesgenesis:infinite_multiversal_emextra_mek_apiary_factory");
	public static final List<String> MACHINES = Stream.of(ORDINARY_MACHINES, EVOLVED_MACHINES, EXTRA_MACHINES, EVOLVED_EXTRA_MACHINES)
			.flatMap(List::stream).toList();
	public ApiaryWorkLayout {
		if (!((bees == 3 || bees == 5) && feeders == 9
				|| (bees == 10 || bees == 15 || bees == 20 || bees == 30 || bees == 45 || bees == 55 || bees == 60) && feeders == bees
				|| bees == 26 && feeders == 30 || bees == 36 && feeders == 40 || bees == 42 && feeders == 45 || bees == 51 && feeders == 55))
			throw new IllegalArgumentException("Unsupported apiary work layout");
	}
	public static ApiaryWorkLayout forMachine(String machine) {
		return switch (machine) {
			case "productivebeesgenesis:mek_apiary" -> new ApiaryWorkLayout(3, 9);
			case "productivebeesgenesis:basic_mek_apiary_factory" -> new ApiaryWorkLayout(5, 9);
			case "productivebeesgenesis:advanced_mek_apiary_factory" -> new ApiaryWorkLayout(10, 10);
			case "productivebeesgenesis:elite_mek_apiary_factory" -> new ApiaryWorkLayout(15, 15);
			case "productivebeesgenesis:ultimate_mek_apiary_factory" -> new ApiaryWorkLayout(20, 20);
			case "productivebeesgenesis:overclocked_mek_apiary_factory" -> new ApiaryWorkLayout(26, 30);
			case "productivebeesgenesis:quantum_mek_apiary_factory" -> new ApiaryWorkLayout(30, 30);
			case "productivebeesgenesis:dense_mek_apiary_factory" -> new ApiaryWorkLayout(36, 40);
			case "productivebeesgenesis:multiversal_mek_apiary_factory" -> new ApiaryWorkLayout(42, 45);
			case "productivebeesgenesis:creative_mek_apiary_factory" -> new ApiaryWorkLayout(45, 45);
			case "productivebeesgenesis:absolute_extra_mek_apiary_factory" -> new ApiaryWorkLayout(26, 30);
			case "productivebeesgenesis:supreme_extra_mek_apiary_factory" -> new ApiaryWorkLayout(30, 30);
			case "productivebeesgenesis:cosmic_extra_mek_apiary_factory" -> new ApiaryWorkLayout(36, 40);
			case "productivebeesgenesis:infinite_extra_mek_apiary_factory" -> new ApiaryWorkLayout(42, 45);
			case "productivebeesgenesis:absolute_overclocked_emextra_mek_apiary_factory" -> new ApiaryWorkLayout(45, 45);
			case "productivebeesgenesis:supreme_quantum_emextra_mek_apiary_factory" -> new ApiaryWorkLayout(51, 55);
			case "productivebeesgenesis:cosmic_dense_emextra_mek_apiary_factory" -> new ApiaryWorkLayout(55, 55);
			case "productivebeesgenesis:infinite_multiversal_emextra_mek_apiary_factory" -> new ApiaryWorkLayout(60, 60);
			default -> null;
		};
	}
}
