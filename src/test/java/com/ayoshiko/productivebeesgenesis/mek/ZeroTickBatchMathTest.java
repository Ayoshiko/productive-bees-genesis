package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 零耗时配方（CREATIVE 升级）合并推进的数学与接线回归。
 * <p>
 * 背景：CREATIVE 让 {@code getTicksRequired()} 返回 0，Mekanism 每个配方 tick 恰好
 * 完成一个完整周期，原批量快速路径必然第一次推进就退出 ——
 * 等价于每虚拟刻一次完整 calculateOperationsThisTick + 一次输出槽 insertItem
 * （spark XnLugba3Cw：1940ms + 1208ms）。
 */
class ZeroTickBatchMathTest {

	private static final Path SRC = Path.of("src", "main", "java", "com", "ayoshiko",
			"productivebeesgenesis");

	@Test
	void onlyZeroOrSingleTickRecipesCoalesce() {
		assertTrue(ZeroTickBatchMath.isCoalescible(0), "CREATIVE 的 ticksRequired=0 必须合并");
		assertTrue(ZeroTickBatchMath.isCoalescible(1), "单刻配方也无中间状态可复用");
		assertFalse(ZeroTickBatchMath.isCoalescible(2),
				"多刻配方交给原有 operatingTicks 快速路径，不得放大并行上限");
	}

	@Test
	void baselineIsRaisedByFactorAndSaturates() {
		assertEquals(8, ZeroTickBatchMath.raisedBaseline(8, 1), "未合并时上限原样返回");
		assertEquals(8, ZeroTickBatchMath.raisedBaseline(8, 0), "非法倍数按未合并处理");
		assertEquals(8192, ZeroTickBatchMath.raisedBaseline(8, 1024));
		assertEquals(Integer.MAX_VALUE,
				ZeroTickBatchMath.raisedBaseline(Integer.MAX_VALUE, 1024),
				"放大必须饱和，不能溢出为负");
	}

	@Test
	void virtualTicksAreDerivedFromApprovedOperations() {
		// 获批 8192 = 8 单刻上限 × 1024 刻
		assertEquals(1024, ZeroTickBatchMath.virtualTicksFor(8192, 8));
		// 输入/输出裁剪后只获批 20：向上取整 3 刻（偏保守，宁多扣预算不重复产出）
		assertEquals(3, ZeroTickBatchMath.virtualTicksFor(20, 8));
		assertEquals(1, ZeroTickBatchMath.virtualTicksFor(1, 8));
		assertEquals(0, ZeroTickBatchMath.virtualTicksFor(0, 8), "未获批不消耗虚拟刻");
	}

	@Test
	void operationsPerVirtualTickRoundsUp() {
		assertEquals(8, ZeroTickBatchMath.operationsPerVirtualTick(8192, 1024));
		assertEquals(7, ZeroTickBatchMath.operationsPerVirtualTick(20, 3));
		assertEquals(0, ZeroTickBatchMath.operationsPerVirtualTick(0, 5));
		assertEquals(0, ZeroTickBatchMath.operationsPerVirtualTick(10, 0));
	}

	/**
	 * 关键语义：边际计费曲线是次线性的，必须先算单刻可承担量再乘回倍数。
	 * 若直接把 ops×factor 交给曲线，1024 倍加速几乎不耗电。
	 */
	@Test
	void coalescedAffordabilityStaysPerTickPriced() {
		long energyPerOperation = 100L;
		int baseOps = 64;
		int factor = 16;
		long perTickCost = MekCentrifugeEnergyScaling.batchEnergyCost(energyPerOperation, baseOps, 1);
		long stored = perTickCost * factor;

		int coalesced = ZeroTickBatchMath.affordableCoalescedOperations(
				energyPerOperation, baseOps, stored, factor);
		assertEquals(baseOps * factor, coalesced,
				"恰好够 factor 个满刻时应放行 baseOps × factor 个操作");

		int naive = MekCentrifugeEnergyScaling.affordableOperations(
				energyPerOperation, baseOps * factor, stored);
		assertTrue(naive >= coalesced,
				"直接对总操作数套次线性曲线只会更宽松，这正是必须按刻定价的原因: naive="
						+ naive + " coalesced=" + coalesced);
	}

	@Test
	void singleFactorMatchesUncoalescedPricing() {
		long energyPerOperation = 250L;
		int baseOps = 32;
		long stored = 12_345L;
		assertEquals(
				MekCentrifugeEnergyScaling.affordableOperations(energyPerOperation, baseOps, stored),
				ZeroTickBatchMath.affordableCoalescedOperations(energyPerOperation, baseOps, stored, 1),
				"factor<=1 时必须与原路径逐位一致");
	}

	@Test
	void perVirtualTickBudgetSplitsStoredEnergy() {
		assertEquals(1000L, ZeroTickBatchMath.perVirtualTickEnergyBudget(1000L, 1));
		assertEquals(100L, ZeroTickBatchMath.perVirtualTickEnergyBudget(1000L, 10));
		assertEquals(0L, ZeroTickBatchMath.perVirtualTickEnergyBudget(0L, 10));
	}

	/** 合并窗口未激活时并行上限必须与原供应商完全一致（常态零影响）。 */
	@Test
	void coalesceStateIsTransparentWhenInactive() {
		int[] base = {7};
		ZeroTickCoalesceState state = new ZeroTickCoalesceState(() -> base[0]);
		assertEquals(7, state.getAsInt());
		assertFalse(state.isActive());
		base[0] = 9;
		assertEquals(9, state.getAsInt(), "未激活时必须实时透传，不得缓存旧值");
	}

	@Test
	void coalesceStateRaisesBaselineInsideWindow() {
		ZeroTickCoalesceState state = new ZeroTickCoalesceState(() -> 8);
		state.begin(1024);
		assertTrue(state.isActive());
		assertEquals(1024, state.factor());
		assertEquals(8, state.base(), "窗口内 base 必须是快照的单刻上限，不能是放大值");
		assertEquals(8192, state.getAsInt());
		state.end();
		assertEquals(8, state.getAsInt(), "窗口关闭后恢复单刻上限");
		assertFalse(state.isActive());
	}

	/**
	 * 批内快照：一个补调批次落在同一 gameTick 内，单刻上限只应向供应商取一次。
	 * <p>
	 * 供应商是 {@code operationsPerTick()}，内部按 gameTick 记忆化但仍要先读
	 * {@code level.getGameTime()}；零耗时配方每虚拟刻开窗一次，会把它放大到
	 * 每真实刻上千次（spark gUqyZmn5q6 中 {@code Level.getGameTime} 自耗 464ms / 1.55%）。
	 */
	@Test
	void batchSnapshotQueriesBaselineSupplierOnce() {
		int[] calls = {0};
		int[] value = {8};
		ZeroTickCoalesceState state = new ZeroTickCoalesceState(() -> {
			calls[0]++;
			return value[0];
		});

		state.beginBatch();
		assertEquals(1, calls[0], "批次开始取一次快照");
		for (int i = 0; i < 100; i++) {
			state.begin(4);
			state.end();
		}
		assertEquals(1, calls[0], "批内开窗必须复用快照，不得重复查询供应商");
		state.begin(4);
		assertEquals(8, state.base());
		assertEquals(32, state.getAsInt());
		state.end();

		// 批次结束后丢弃快照：下个批次（可能已跨 gameTick、升级数量已变）必须重新取
		state.endBatch();
		value[0] = 16;
		state.begin(4);
		assertEquals(2, calls[0], "批次外开窗必须重新查询供应商");
		assertEquals(16, state.base());
		assertEquals(64, state.getAsInt());
	}

	/** 未激活窗口时 getAsInt 必须实时透传供应商（升级即时生效）。 */
	@Test
	void inactiveWindowAlwaysReadsSupplier() {
		int[] value = {5};
		ZeroTickCoalesceState state = new ZeroTickCoalesceState(() -> value[0]);
		state.beginBatch();
		assertEquals(5, state.getAsInt());
		value[0] = 11;
		assertEquals(11, state.getAsInt(), "窗口未开时不得返回批内快照");
	}

	/** 接线断言：两条 CachedRecipe 装配路径都必须绑定合并窗口，否则优化静默失效。 */
	@Test
	void bothRecipeFactoriesBindCoalesceWindow() throws Exception {
		String factory = Files.readString(SRC.resolve("mek/CentrifugeFactoryCommonLogic.java"));
		assertTrue(factory.contains("coalesceState == null")
				&& factory.contains("new ZeroTickCoalesceState(operationsPerTick)"),
				"工厂路径必须支持建立默认合并窗口");
		assertTrue(factory.contains("ZeroTickCoalesceState coalesce ="),
				"工厂路径必须解析可复用的合并窗口");
		assertTrue(factory.contains(".setBaselineMaxOperations(coalesce)"),
				"工厂路径的并行上限供应商必须换成合并窗口");
		assertTrue(factory.contains("accel.productivebeesgenesis$bindZeroTickCoalesce(coalesce)"),
				"工厂路径必须把窗口绑定到 Mixin");

		String basic = Files.readString(SRC.resolve("mek/MekCentrifugeUpgradeOps.java"));
		assertTrue(basic.contains("new ZeroTickCoalesceState(tile::getOperationsPerTick)"),
				"基础机路径必须建立合并窗口");
		assertTrue(basic.contains(".setBaselineMaxOperations(coalesce)"),
				"基础机路径的并行上限供应商必须换成合并窗口");
		assertTrue(basic.contains("accel.productivebeesgenesis$bindZeroTickCoalesce(coalesce)"),
				"基础机路径必须把窗口绑定到 Mixin");
	}

	/** 接线断言：Mixin 必须在完整计算前开窗、在 process 返回时闭窗。 */
	@Test
	void mixinOpensAndClosesWindowAroundFullCalculation() throws Exception {
		String mixin = Files.readString(SRC.resolve("mixin/mek/CachedRecipeBatchAccelMixin.java"));
		assertTrue(mixin.contains("productivebeesgenesis$beginZeroTickCoalesce();"),
				"需要完整重算的分支必须开窗");
		assertTrue(mixin.contains("productivebeesgenesis$endZeroTickCoalesce();"),
				"process 返回时必须闭窗，否则窗口跨 tick 泄漏");
		assertTrue(mixin.contains("ZeroTickBatchMath.virtualTicksFor(ops,"),
				"必须按获批操作数反推消耗的虚拟刻，否则剩余预算会重复整批产出");
		assertTrue(mixin.contains("ZeroTickBatchMath.affordableCoalescedOperations("),
				"合并调用的能量可承担量必须按刻定价");
		assertTrue(mixin.contains("state.beginBatch();"),
				"startBatch 必须快照本 gameTick 的单刻上限，否则每虚拟刻都要读 level.getGameTime()");
		assertTrue(mixin.contains("state.endBatch();"),
				"批次结束必须丢弃快照，否则跨 gameTick 会沿用旧升级数量");
	}
}
