package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;

/**
	 * AE2 输入配置窗口的窗口数据实例 — 从 {@link GuiAeInputConfig} 拆分。
	 * <br/>
	 * 静态初始化时通过 mixin 接口设置自定义窗口保存名。
	 */
final class AeInputWindowData {

	static final SelectedWindowData INSTANCE = new SelectedWindowData(WindowType.UNSPECIFIED);

	static {
		// instanceof 先判类型再调用 mixin 注入接口，避免强转失败路径（mixin 未应用时静默丢自定义窗口名）
		if (INSTANCE instanceof CustomWindowData cwd) {
			cwd.productivebeesgenesis$setCustomSaveName("window_ae_input");
		} else {
			ProductiveBeesGenesis.LOGGER.warn("GuiAeInputConfig window position persistence mixin unavailable");
		}
	}

	private AeInputWindowData() {
	}
}
