package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 标签过滤请求的服务端输入与权限边界。 */
class Ae2TagFilterWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("服务端 handler 校验表达式长度、容器一致性、交互距离与限频")
	void serverHandlerValidatesRequest() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/network/"
				+ "Ae2TagFilterPayloadHandlers.java");
		assertTrue(source.contains("NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH"));
		assertTrue(source.contains("MachineMenuAccess.canAccess"));
		assertTrue(source.contains("PayloadRateLimiter.tryAccept"));
		assertTrue(source.contains("NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ"));
	}

}
