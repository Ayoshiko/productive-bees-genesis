package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 快速弹出不得修改 Mekanism 或其它附属机器的结构边界。 */
class EjectorIsolationTest {

	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	@Test
	void globalMekanismEjectorHasNoMixinOrAccessor() throws Exception {
		String mixinConfig = read("src/main/resources/productivebeesgenesis.mixins.json");
		assertFalse(mixinConfig.contains("TileComponentEjectorFastPathMixin"));
		assertFalse(mixinConfig.contains("TileEntityEjectorAccessor"));

		try (var files = Files.walk(Path.of("src/main/java"))) {
			List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
			for (Path path : javaFiles) {
				String source = Files.readString(path);
				assertFalse(source.contains("@Mixin(TileComponentEjector.class)")
							|| source.contains("@Mixin(value = TileComponentEjector.class"),
						"禁止全局修改 Mekanism 弹出器: " + path);
			}
		}
	}

}
