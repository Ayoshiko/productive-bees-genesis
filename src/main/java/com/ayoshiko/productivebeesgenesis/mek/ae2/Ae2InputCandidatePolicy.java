package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.level.Level;

/**
 * Classifies AE2 item keys that may enter a centrifuge through network pulling.
 * <p>
 * Productive Bees comb semantics remain owned by {@link CombFuzzyMatcher}; ordinary
 * Mekanism SMELTING inputs are admitted only while the centrifuge compatibility mode
 * is enabled. Comb classification deliberately runs first so broad SMELTING tag recipes
 * such as {@code c:honeycombs} cannot steal Productive Bees inputs.
 * <p>
 * 标签门同时约束两类候选，避免蜜脾或蜜脾块分支绕过白名单/黑名单；
 * 具体的“是否标记”仍由 {@link Ae2InputFilter} 的槽位白/黑名单负责。
 * <p>
 * 蜜脾候选另经 {@link CombProcessGate}（本机可处理性）：整合包里大量蜜脾只有蜂、
 * 没有 PB 离心配方（需其它模组机器处理），它们靠 Item 引用判定仍是「蜜脾」，
 * 若放进候选就会每轮反复拉取/探测却永远插不进槽，形成「卡但 TPS 不掉」的卡顿。
 */
final class Ae2InputCandidatePolicy {

	/**
	 * 候选物品的标签准入抽象（DIP + ISP）。
	 * <br/>
	 * 只暴露一个判定方法，使分类逻辑不依赖具体的标签缓存实现，便于单测替换。
	 */
	@FunctionalInterface
	interface SmeltingTagGate {

		/** 全部放行的门（未配置标签过滤时使用，零开销）。 */
		SmeltingTagGate ALLOW_ALL = key -> true;

		boolean allows(AEItemKey key);
	}

	/**
	 * 蜜脾候选的「本机可处理性」准入抽象（DIP + ISP）。
	 * <br/>
	 * 与 {@link SmeltingTagGate} 分离：标签门是玩家配置（可收窄 AE2 输入候选），
	 * 本门是机器能力（本机有无对应离心配方），两者语义与生命周期都不同。
	 */
	@FunctionalInterface
	interface CombProcessGate {

		/** 全部放行的门（无宿主上下文时使用，保持旧行为）。 */
		CombProcessGate ALLOW_ALL = key -> true;

		boolean canProcess(AEItemKey key);
	}

	enum CandidateKind {
		REJECTED,
		COMB,
		SMELTING;

		boolean isAllowed() {
			return this != REJECTED;
		}

		boolean isSmelting() {
			return this == SMELTING;
		}
	}

	private Ae2InputCandidatePolicy() {
	}

	/**
	 * 分类候选键。判定顺序刻意保持不变：先蜜脾能力、再 SMELTING 配方、最后标签门。
	 * 标签门在两类候选上统一执行，避免蜜脾分支绕过白名单/黑名单。
	 * <p>
	 * 蜜脾被 {@link CombProcessGate} 拒绝时<b>直接 REJECTED，绝不下落 SMELTING 分支</b>：
	 * 整合包（modularbees 等）会为 {@code c:honeycombs} 注册熔炼配方，一旦下落，
	 * 本机无离心配方的蜜脾会以 SMELTING 身份重新被拉进来并产出错误结果，
	 * 正是历史上已修过的「c:honeycombs 抢占 PB 输入」缺陷。
	 * <p>
	 * <b>返回值必须被调用方保留</b>：分类结果只由候选身份决定，与后续排序、配额分配无关。
	 * 拉取器把它写入 {@code PullEntry.smelting} 随条目传递，绝不在排序阶段重新调用本方法 ——
	 * 那会让每次拉取额外穿过 typeCount 次 SMELTING 配方缓存与标签缓存
	 * （spark BkTP3d9oSc 中整条分类链路占服务端主线程 784ms / 1.31%）。
	 */
	static CandidateKind classify(Level level, AEItemKey key, boolean smeltingEnabled,
			Ae2SmeltingInputCache smeltingCache, SmeltingTagGate tagGate, CombProcessGate combGate) {
		if (key == null) return CandidateKind.REJECTED;
		boolean comb = CombFuzzyMatcher.isCombItem(key);
		if (comb) {
			// 本机无法加工的蜜脾（只有蜂、无离心配方，需其它模组机器）必须在此终止：
			// 否则它会占满候选类型窗口并每轮空转探测槽位，永远无法插入。
			if (combGate != null && !combGate.canProcess(key)) return CandidateKind.REJECTED;
		} else {
			if (!smeltingEnabled || level == null || smeltingCache == null) return CandidateKind.REJECTED;
			if (!smeltingCache.contains(level, key)) return CandidateKind.REJECTED;
		}
		if (tagGate != null && !tagGate.allows(key)) return CandidateKind.REJECTED;
		return comb ? CandidateKind.COMB : CandidateKind.SMELTING;
	}
}
