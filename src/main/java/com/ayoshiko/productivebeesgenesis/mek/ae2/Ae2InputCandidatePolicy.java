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
 * SMELTING 候选额外经过 {@link SmeltingTagGate}（标签表达式过滤）。蜜脾候选不走该门，
 * 因为蜜脾种类由 {@link Ae2InputFilter} 的槽位白/黑名单负责，两套配置互不干扰。
 * <p>
 * 蜜脾候选另经 {@link CombProcessGate}（本机可处理性）：整合包里大量蜜脾只有蜂、
 * 没有 PB 离心配方（需其它模组机器处理），它们靠 Item 引用判定仍是「蜜脾」，
 * 若放进候选就会每轮反复拉取/探测却永远插不进槽，形成「卡但 TPS 不掉」的卡顿。
 */
final class Ae2InputCandidatePolicy {

	/**
	 * SMELTING 候选的标签准入抽象（DIP + ISP）。
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
	 * 与 {@link SmeltingTagGate} 分离：标签门是玩家配置（可收窄 smelt 范围），
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

	/** Classifies a key using the current per-pull switch snapshot and per-host recipe cache. */
	static CandidateKind classify(Level level, AEItemKey key, boolean smeltingEnabled,
			Ae2SmeltingInputCache smeltingCache) {
		return classify(level, key, smeltingEnabled, smeltingCache, SmeltingTagGate.ALLOW_ALL);
	}

	/**
	 * 带标签门的分类（未接可处理性门，等价于全部蜜脾放行）。
	 */
	static CandidateKind classify(Level level, AEItemKey key, boolean smeltingEnabled,
			Ae2SmeltingInputCache smeltingCache, SmeltingTagGate tagGate) {
		return classify(level, key, smeltingEnabled, smeltingCache, tagGate, CombProcessGate.ALLOW_ALL);
	}

	/**
	 * 完整分类。判定顺序刻意保持不变：先蜜脾、再 SMELTING、最后标签门，
	 * 使标签表达式只能收窄 smelt 输入范围，不会影响蜜脾拉取。
	 * <p>
	 * 蜜脾被 {@link CombProcessGate} 拒绝时<b>直接 REJECTED，绝不下落 SMELTING 分支</b>：
	 * 整合包（modularbees 等）会为 {@code c:honeycombs} 注册熔炼配方，一旦下落，
	 * 本机无离心配方的蜜脾会以 SMELTING 身份重新被拉进来并产出错误结果，
	 * 正是历史上已修过的「c:honeycombs 抢占 PB 输入」缺陷。
	 */
	static CandidateKind classify(Level level, AEItemKey key, boolean smeltingEnabled,
			Ae2SmeltingInputCache smeltingCache, SmeltingTagGate tagGate, CombProcessGate combGate) {
		if (key == null) return CandidateKind.REJECTED;
		if (CombFuzzyMatcher.isCombItem(key)) {
			// 本机无法加工的蜜脾（只有蜂、无离心配方，需其它模组机器）必须在此终止：
			// 否则它会占满候选类型窗口并每轮空转探测槽位，永远无法插入。
			return combGate == null || combGate.canProcess(key)
					? CandidateKind.COMB : CandidateKind.REJECTED;
		}
		if (!smeltingEnabled || level == null || smeltingCache == null) return CandidateKind.REJECTED;
		if (!smeltingCache.contains(level, key)) return CandidateKind.REJECTED;
		return tagGate == null || tagGate.allows(key) ? CandidateKind.SMELTING : CandidateKind.REJECTED;
	}
}
