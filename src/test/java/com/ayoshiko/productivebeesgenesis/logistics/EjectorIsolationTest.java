package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	void onlyAddonMachinesInstallDedicatedComponent() throws Exception {
		String component = read("src/main/java/com/ayoshiko/productivebeesgenesis/logistics/"
				+ "GenesisTileComponentEjector.java");
		assertTrue(component.contains("extends TileComponentEjector implements IFastEjectHost"));
		assertTrue(component.contains("new FastItemEjector(tile)"));
		assertTrue(component.contains("new FastFluidEjector(tile)"));

		assertTrue(read("src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiarySideConfigSupport.java")
				.contains("GenesisTileComponentEjector.replace(tile, tile.ejectorComponent"));
		assertTrue(read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/TileEntityMekCentrifuge.java")
				.contains("GenesisTileComponentEjector.replace(this, ejectorComponent"));
		assertTrue(read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/MekCentrifugeIoConfigHelper.java")
				.contains("GenesisTileComponentEjector.replace(factory, previous"));
	}

	@Test
	void transporterUsesPublicRoutingWithoutVanillaFallbackMutation() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/logistics/FastItemEjector.java");
		assertTrue(source.contains("request.eject("));
		assertTrue(source.contains("ignored -> ejector.getOutputColor()"));
		assertTrue(source.indexOf("response.getSendingAmount()") < source.indexOf("response.useAll()"),
				"发送量必须在 useAll 改写请求状态前读取");
		assertTrue(source.contains("response.useAll()"));
		assertFalse(source.contains("needsVanillaFallback"));
		assertFalse(source.contains("setTickDelay"));
	}
}
