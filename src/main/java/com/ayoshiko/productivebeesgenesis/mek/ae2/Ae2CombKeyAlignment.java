package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

/**
 * 把蜜脾精确条目的键对齐到 ME 网络里真实存在的那一个组件变体。
 * <p>
 * <b>为什么需要</b>：模糊条目升级（{@link Ae2LegacyCombEntryUpgrade}）只能按 bee_type 现场构造一个
 * 蜜脾栈，如果网络里的同种蜜脾还带别的数据组件（被改过名、别的模组附加了组件），构造出来的键
 * 与网络里的键不相等。拉取仍然正常 —— 非精确模式按 bee_type 分组匹配 —— 但界面下方那行
 * 「可见库存」会读成 0，输出槽点击也会指向一个网络里并不存在的键。
 * <p>
 * <b>为什么改写是安全的</b>：只在非精确模式下对蜜脾条目生效。此时匹配本就按 bee_type 分组，
 * 换成同蜂种的另一个变体不改变任何准入/配额判定；精确模式与熔炼物品条目一律不动 ——
 * 那里"键完全相等"本身就是玩家要表达的语义。
 * <p>
 * <b>成本</b>：{@link KeyCounter#findFuzzy} 按主键（Item）取子索引，只遍历该物品的组件变体
 * （通常 1~2 个），不扫描整个网络；且只有「配置键当前库存为 0」的蜜脾条目才会发起这次查询。
 */
public final class Ae2CombKeyAlignment {

	private Ae2CombKeyAlignment() {
		// 工具类禁止实例化
	}

	/**
	 * 把过滤器里所有「网络里不存在」的蜜脾精确条目改指向同蜂种的真实键。
	 * <p>
	 * 逐槽设置（拉取量 / 保留量 / 无限拉取 / 库存模式）全部保留，只换键
	 * （{@link Ae2InputFilter#repointDirectEntryAt}）。尚未解析出键的条目本轮跳过：
	 * 同步包构建时会统一解析，下一次轮询即可对齐。
	 *
	 * @return 改写的条目数；0 表示无需落盘
	 */
	public static int realign(Ae2InputFilter filter, KeyCounter inventory,
			HolderLookup.Provider registries, int maxFingerprintLength) {
		if (filter == null || inventory == null || registries == null
				|| !Ae2CombVariantPolicy.allowsRealign(filter.isPreciseMode())) return 0;
		int changed = 0;
		for (int index = 0; index < filter.getCapacity(); index++) {
			if (!filter.isDirectEntry(index)) continue;
			AEItemKey replacement = findReplacement(inventory, filter.getResolvedDirectKey(index));
			if (replacement == null) continue;
			String fingerprint = Ae2ItemFingerprint.encodeBounded(replacement, registries, maxFingerprintLength);
			if (fingerprint == null) continue;
			if (filter.repointDirectEntryAt(index, fingerprint, replacement)) changed++;
		}
		return changed;
	}

	/**
	 * 配置键在网络里查不到时，返回同蜂种、库存最多的真实键；否则返回 null。
	 * <p>
	 * 升级路径也用它：那时的「配置键」是刚按 bee_type 构造出来的猜测值，网络快照是事实来源。
	 */
	static AEItemKey findReplacement(KeyCounter inventory, AEItemKey configured) {
		if (inventory == null || configured == null) return null;
		ResourceLocation beeType = CombFuzzyMatcher.getBeeType(configured);
		if (!Ae2CombVariantPolicy.needsRealign(CombFuzzyMatcher.isCombItem(configured),
				beeType != null, inventory.get(configured))) return null;
		return findNetworkVariant(inventory, configured, beeType);
	}

	/** 网络里同物品、同蜂种、库存最多的另一个键；没有可用候选时返回 null。 */
	private static AEItemKey findNetworkVariant(KeyCounter inventory, AEItemKey configured,
			ResourceLocation beeType) {
		AEItemKey best = null;
		long bestAmount = 0L;
		// findFuzzy + IGNORE_ALL：同一 Item 的全部组件变体，AE2 按主键取子索引，不遍历全网络
		for (Object2LongMap.Entry<AEKey> entry : inventory.findFuzzy(configured, FuzzyMode.IGNORE_ALL)) {
			if (!(entry.getKey() instanceof AEItemKey candidate) || candidate.equals(configured)) continue;
			if (!Ae2CombVariantPolicy.isBetterVariant(bestAmount, entry.getLongValue())) continue;
			// 同 Item 不代表同蜂种：可配置蜜脾靠 bee_type 组件区分种类，必须逐个核对
			if (!beeType.equals(CombFuzzyMatcher.getBeeType(candidate))) continue;
			best = candidate;
			bestAmount = entry.getLongValue();
		}
		return best;
	}
}
