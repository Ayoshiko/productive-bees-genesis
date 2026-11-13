package com.ayoshiko.productivebeesgenesis.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Issue #8 可选依赖类加载与网络注册检查。 */
class CentrifugeInputReturnCompatibilityTest {

	@Test
	@DisplayName("AE2 不在测试运行时类路径时核心返还类仍可完成 JVM 加载验证")
	void coreClassesLoadWithoutAe2() throws Exception {
		Path mergedNeoForge;
		try (var artifacts = Files.list(Path.of("build/moddev/artifacts"))) {
			String version = Files.readAllLines(Path.of("gradle.properties")).stream()
					.filter(line -> line.startsWith("neo_version=")).findFirst().orElseThrow().split("=", 2)[1];
			mergedNeoForge = artifacts
					.filter(path -> path.getFileName().toString().equals("neoforge-" + version + "-merged.jar"))
					.findFirst()
					.orElseThrow();
		}
		try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
				Path.of("build/classes/java/main").toUri().toURL(),
				mergedNeoForge.toUri().toURL(),
				Path.of("libs/Mekanism-1.21.1-10.7.19.85.jar").toUri().toURL()
		}, null)) {
			try (URLClassLoader installed = new URLClassLoader(new java.net.URL[] {
					Path.of("libs/appliedenergistics2-1.21.1-19.2.17.jar").toUri().toURL()
			}, loader)) {
				Class.forName("appeng.api.networking.IGrid", false, installed);
			}
			assertThrows(ClassNotFoundException.class,
					() -> Class.forName("appeng.api.networking.IGrid", false, loader),
					"隔离类加载器不得包含 AE2");
			for (String className : List.of(
					"com.ayoshiko.productivebeesgenesis.network.CentrifugeInputReturnPayloadHandler",
					"com.ayoshiko.productivebeesgenesis.network.CentrifugeAeReturnResult",
					"com.ayoshiko.productivebeesgenesis.network.CentrifugeConfiguredOutputService",
					"com.ayoshiko.productivebeesgenesis.network.ReturnCentrifugeInputPayload")) {
				Class.forName(className, false, loader);
			}
		}
	}

}
