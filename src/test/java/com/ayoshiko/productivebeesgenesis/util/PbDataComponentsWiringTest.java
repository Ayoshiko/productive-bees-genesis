package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** bee_type 数据组件的注册时机边界。 */
class PbDataComponentsWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("类初始化不提前解析尚未绑定的组件")
	void accessorDoesNotResolveDuringClassInitialization() throws Exception {
		String source = read(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/PbDataComponents.java");
		assertFalse(source.contains("static {"),
				"不得在类初始化时解析：注册完成前调用 DeferredHolder.get 会抛异常");
	}

	@Test
	@DisplayName("注册期与展示期的组件写入保持原样，不引入无谓间接层")
	void registrationPathsKeepDirectHolder() throws Exception {
		// ModItems 在注册期构造默认组件，此时 DeferredHolder 尚未绑定，必须保留原调用形式
		String modItems = read(
				"src/main/java/com/ayoshiko/productivebeesgenesis/init/ModItems.java");
		assertTrue(modItems.contains("ModDataComponents.BEE_TYPE.get()"),
				"注册期路径应保留 DeferredHolder，不走缓存访问器");
	}

}
