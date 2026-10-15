package com.ayoshiko.productivebeesgenesis.apiculture.capacity;

/** 已接入的固定蜂箱布局；保存恢复不依赖注册表或可选工厂类。 */
public record ApiaryWorkLayout(int bees, int feeders) {
	public static final int MAX_BEES = 20;
	public ApiaryWorkLayout {
		if (!((bees == 3 || bees == 5) && feeders == 9
				|| (bees == 10 || bees == 15 || bees == 20) && feeders == bees))
			throw new IllegalArgumentException("Unsupported apiary work layout");
	}
	public static ApiaryWorkLayout forMachine(String machine) {
		return switch (machine) {
			case "productivebeesgenesis:mek_apiary" -> new ApiaryWorkLayout(3, 9);
			case "productivebeesgenesis:basic_mek_apiary_factory" -> new ApiaryWorkLayout(5, 9);
			case "productivebeesgenesis:advanced_mek_apiary_factory" -> new ApiaryWorkLayout(10, 10);
			case "productivebeesgenesis:elite_mek_apiary_factory" -> new ApiaryWorkLayout(15, 15);
			case "productivebeesgenesis:ultimate_mek_apiary_factory" -> new ApiaryWorkLayout(20, 20);
			default -> null;
		};
	}
}
