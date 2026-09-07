package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * 把模糊蜜脾条目（{@code bee_type} / {@code bee_type#block}）就地升级为精确指纹条目。
 * <p>
 * <b>为什么需要</b>：1.0.6~1.0.7 期间蜜脾标记被强制存成模糊条目，而逐槽齿轮
 * （拉取数量 / 库存保留量 / 无限拉取 / 库存模式）与下方网络库存行只对精确指纹条目渲染，
 * 结果玩家的蜜脾标记退回成「只有一个图标」的旧样式。存量存档里的这些条目不会因为
 * 客户端修好就自动变形，若不升级就只能让玩家把每个格子重新拖一遍。
 * <p>
 * <b>为什么升级是等价的</b>：非精确模式下 {@link Ae2FilterEntryMatcher#matchesDirect} 对
 * 「双方都有 bee_type」的情况按 bee_type 分组判定，蜜脾与蜜脾块仍共用同一份配额，
 * 与模糊条目的匹配结果一致；差别只在于精确条目额外拥有逐槽数量/库存语义。
 * <p>
 * <b>线程安全</b>：自身无状态，全部写入都经 {@link Ae2InputFilter} 的 synchronized 方法。
 * 必须在服务端调用（需要 registryAccess 编码指纹），且调用点已由 AE2 装载守卫保护。
 */
public final class Ae2LegacyCombEntryUpgrade {

	private Ae2LegacyCombEntryUpgrade() {
		// 工具类禁止实例化
	}

	/**
	 * 升级过滤器内全部模糊蜜脾条目。
	 *
	 * @param filter               目标过滤器（null 时直接返回）
	 * @param inventory            ME 网络快照，用于把构造键对齐到真实存在的组件变体；可为 null
	 * @param registries           指纹编码所需的注册表视图（服务端 registryAccess）
	 * @param maxFingerprintLength 指纹长度上限，超长条目保持模糊形态（避免同步包被客户端拒收）
	 * @return 实际升级的条目数；0 表示无需改动（调用方据此决定是否 markForSave）
	 */
	public static int upgrade(Ae2InputFilter filter, KeyCounter inventory,
			HolderLookup.Provider registries, int maxFingerprintLength) {
		if (filter == null || registries == null || !filter.hasFuzzyEntries()) return 0;
		int upgraded = 0;
		for (int index = 0; index < filter.getCapacity(); index++) {
			AEItemKey key = fuzzyCombKeyAt(filter, index);
			if (key == null) continue;
			// 构造键只是按 bee_type 的猜测：网络里真有同种蜜脾时以网络里那一个为准，
			// 否则「可见库存」会读成 0、输出槽也会指向不存在的键（见 Ae2CombKeyAlignment）
			AEItemKey aligned = Ae2CombKeyAlignment.findReplacement(inventory, key);
			if (aligned != null) key = aligned;
			String fingerprint = Ae2ItemFingerprint.encodeBounded(key, registries, maxFingerprintLength);
			if (fingerprint == null) continue;
			filter.setDirectEntryFingerprintAt(index, fingerprint);
			// 立即回填已知键，省掉同步包构建时的一次指纹解析
			filter.resolveDirectKey(index, key);
			upgraded++;
		}
		if (upgraded > 0) {
			ProductiveBeesGenesis.LOGGER.debug("AE2 输入过滤器升级了 {} 条模糊蜜脾条目为精确条目", upgraded);
		}
		return upgraded;
	}

	/** 模糊蜜脾条目对应的 AE 键；已是精确条目或还原不出物品时返回 null。 */
	private static AEItemKey fuzzyCombKeyAt(Ae2InputFilter filter, int index) {
		Ae2InputFilter.EntryInfo info = filter.getEntryAt(index);
		if (info == null || info.directFingerprint != null || info.beeType == null) return null;
		ItemStack stack = CombFuzzyMatcher.createCombStack(info.beeType, info.isBlock);
		return stack.isEmpty() ? null : AEItemKey.of(stack);
	}
}
