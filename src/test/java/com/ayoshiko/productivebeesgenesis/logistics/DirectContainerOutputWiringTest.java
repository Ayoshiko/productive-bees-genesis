package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 普通容器直通功能的独立网络注册与服务端权限边界。 */
class DirectContainerOutputWiringTest {

	private static final String SRC = "src/main/java/com/ayoshiko/productivebeesgenesis/";

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(SRC + relativePath));
	}

	@Test
	@DisplayName("网络包：非 AE2 功能必须无条件注册，并校验容器/距离/全局开关")
	void payloadIsRegisteredOutsideAe2Guard() throws Exception {
		String payloads = read("network/ModPayloads.java");
		int directIndex = payloads.indexOf("ToggleDirectContainerOutputPayload.TYPE");
		int guardIndex = payloads.indexOf("if (Ae2IntegrationLoader.isAe2Loaded()) {");
		assertTrue(directIndex > 0 && guardIndex > 0 && directIndex < guardIndex,
				"直通开关与 AE2 无关，注册必须在 AE2 守卫之前");

		String handler = read("network/DirectContainerOutputPayloadHandler.java");
		assertTrue(handler.contains("!MachineMenuAccess.canAccess(serverPlayer, payload.pos())"),
				"必须先经统一菜单权限校验拒绝无权请求");
		String access = read("network/MachineMenuAccess.java");
		assertTrue(access.contains("serverPlayer.containerMenu instanceof MekanismTileContainer<?> container")
						&& access.contains("tile.getBlockPos().equals(pos)"),
				"必须校验玩家当前打开的机器与坐标一致");
		assertTrue(access.contains("NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ"),
				"必须校验交互距离");
		assertTrue(handler.contains("ExternalLogisticsSettings.directContainerOutput("
						+ "serverPlayer.level().getGameTime())"),
				"全局关闭时服务端也要拒绝切换");
	}

}
