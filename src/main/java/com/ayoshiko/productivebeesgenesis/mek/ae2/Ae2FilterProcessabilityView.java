package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 判定「某个 AE2 输入过滤槽配置的物品，本机到底能不能加工」，供 GUI 灰显提示使用。
 * <p>
 * 与 {@link Ae2CombProcessableCache} 判定同一件事、共用同一个宿主入口
 * {@code productivebeesgenesis$canProcessInput}，但服务对象不同：那个缓存服务于每 tick 的
 * 拉取候选闸门（必须极廉价、按 key 记忆化），本类服务于 GUI 同步包构建
 * （每次配置变更或每秒一次，条目数 ≤ 过滤器容量），因此直接判定、不引入第二份缓存。
 * <p>
 * <b>为什么放在服务端算</b>：判定结果必须与拉取器实际使用的那一个完全一致，否则会出现
 * 「界面说能处理但就是不拉」。客户端虽然也有配方管理器，但万象创世 bee_type 缓存、
 * per-tile 熔炼兼容开关等状态未必与服务端同步，自行判定容易给出假红。
 * <p>
 * <b>未知一律按「可处理」返回</b>（fail-open）：与 {@link Ae2CombProcessableCache} 的异常
 * 语义一致 —— 误标灰会让玩家以为配置坏了，比漏标的代价大。
 */
public final class Ae2FilterProcessabilityView {

	private Ae2FilterProcessabilityView() {
	}

	/**
	 * 判断过滤器某个槽位配置的物品能否被本机加工。
	 *
	 * @param host  离心机宿主（提供本机配方语义）
	 * @param filter 输入过滤器
	 * @param index 槽位全局索引
	 * @return false 仅在「确实还原出了物品且宿主明确判定不可加工」时返回
	 */
	public static boolean canProcess(IAe2InputHost host, Ae2InputFilter filter, int index) {
		if (host == null || filter == null || index < 0) return true;
		ItemStack probe = probeFor(filter, index);
		if (probe.isEmpty()) return true;
		try {
			return host.productivebeesgenesis$canProcessInput(probe);
		} catch (LinkageError | RuntimeException ignored) {
			// 判定失败不影响配置界面可用性，按可处理呈现
			return true;
		}
	}

	/**
	 * 还原槽位对应的探针物品。
	 * <p>
	 * 过滤器有两种条目形态：精确条目只存指纹（由调用方先解析成 {@link AEItemKey}），
	 * 模糊条目只存 {@code bee_type} + 是否蜜脾块。
	 */
	private static ItemStack probeFor(Ae2InputFilter filter, int index) {
		Ae2InputFilter.EntryInfo info = filter.getEntryAt(index);
		if (info == null) return ItemStack.EMPTY;
		if (info.directFingerprint != null) {
			// 精确条目：同步包构建前已跑过一轮指纹解析；仍未解析的视为未知
			AEItemKey key = filter.getResolvedDirectKey(index);
			return key == null ? ItemStack.EMPTY : key.toStack(1);
		}
		if (info.beeType == null) return ItemStack.EMPTY;
		return combStack(info.beeType, info.isBlock);
	}

	/**
	 * 按 bee_type 构造蜜脾/蜜脾块探针。
	 * <p>
	 * 固定蜜脾（ghostly/milky/powdery 与原版/feywild）没有 BEE_TYPE 组件，必须走
	 * {@link CombFuzzyMatcher#getFixedDisplayStack} 的 Item 映射，否则会造出
	 * 「带 bee_type 的可配置蜜脾」这种网络里不存在、配方也匹配不上的键。
	 */
	private static ItemStack combStack(ResourceLocation beeType, boolean isBlock) {
		ItemStack fixed = CombFuzzyMatcher.getFixedDisplayStack(beeType, isBlock);
		if (!fixed.isEmpty()) return fixed;
		Item item = isBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();
		if (item == null) return ItemStack.EMPTY;
		ItemStack stack = new ItemStack(item);
		stack.set(ModDataComponents.BEE_TYPE.get(), beeType);
		return stack;
	}
}
