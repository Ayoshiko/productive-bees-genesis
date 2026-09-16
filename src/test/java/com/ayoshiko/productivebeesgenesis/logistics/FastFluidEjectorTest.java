package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 流体快速弹出的接线、吞吐与无损回退契约。 */
class FastFluidEjectorTest {

	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	@Test
	void dedicatedComponentOwnsOnlyThisModsFluidEjection() throws Exception {
		String component = read("src/main/java/com/ayoshiko/productivebeesgenesis/logistics/"
				+ "GenesisTileComponentEjector.java");
		assertTrue(component.contains("extends TileComponentEjector implements IFastEjectHost"));
		assertTrue(component.contains("isEjecting(fluidConfig, TransmissionType.FLUID)"));
		assertTrue(component.contains("fluidEjector.tick(tile, fluidConfig, gameTime)"));
		assertTrue(component.contains("tile.getComponents().remove(previous)"),
				"替换父类组件时必须移除旧实例，避免重复同步和序列化状态残留");
	}

	@Test
	void fastPathOffersWholeTanksAndCachesNeighborCapabilities() throws Exception {
		String ejector = read("src/main/java/com/ayoshiko/productivebeesgenesis/logistics/"
				+ "FastFluidEjector.java");
		String targets = read("src/main/java/com/ayoshiko/productivebeesgenesis/logistics/"
				+ "NeighborFluidTargets.java");
		assertTrue(ejector.contains("source.extract(Integer.MAX_VALUE, Action.SIMULATE"),
				"每槽单次必须提供 int 可表达的最大流体量");
		assertTrue(ejector.contains("lastAttemptGameTime == gameTime"),
				"高倍加速下同一真实游戏刻只能扫描一次相邻流体网络");
		assertTrue(ejector.contains("fingerprint == backoffFingerprint"),
				"拒收退避必须在槽内容变化时立即解除");
		assertTrue(targets.contains("BlockCapabilityCache<IFluidHandler"),
				"相邻能力必须缓存，不能逐刻重新查询方块实体能力");
	}

	@Test
	void executeMismatchReturnsFluidToSource() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/logistics/"
				+ "FastFluidEjector.java");
		assertTrue(source.contains("target.fill(available, IFluidHandler.FluidAction.SIMULATE)"));
		assertTrue(source.contains("returnToSource(source, extracted)"),
				"目标实际写入抛异常时也必须先归还已抽取流体");
		assertTrue(source.contains("source.insert(rejected, Action.EXECUTE, AutomationType.INTERNAL)"),
				"目标违反模拟/执行一致性时不得丢失已抽取流体");
	}

	@Test
	void apiaryVanillaFallbackIsAlsoUnlimited() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/apiary/"
				+ "ApiarySideConfigSupport.java");
		assertTrue(source.contains("MekanismConfig.general.chemicalAutoEjectRate, () -> Integer.MAX_VALUE"));
	}
}
