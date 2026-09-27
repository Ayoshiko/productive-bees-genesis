package com.ayoshiko.productivebeesgenesis.util;

import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.world.item.Item;

/**
 * 本模组「可配置蜜脾 / 蜜脾块」两个物品单例的<b>惰性缓存引用</b>。
 *
 * <p><b>为什么：</b>spark 实测热点里 {@code DeferredHolder.value}（注册项解析）占比可观——因为配方校验热路径
 * （{@code containsRecipe → isMyriadCreationsItem / isPbCombInput}、{@code inputProducesOutput} 的槽位指纹）
 * 每次都调用 {@code ModItems.CONFIGURABLE_*.get()}，而在样板发配（尤其逐份）+ 数十台高并行离心机同刻工作时，
 * 该调用频次极高。物品是<b>整服生命周期内不变的注册单例</b>（配方会重载，物品不会），因此首次解析后缓存到
 * 普通静态字段、后续以字段读替代 {@code DeferredHolder.value}，消除热路径上的重复解析开销。</p>
 *
 * <p><b>安全性：</b>惰性解析（首次调用时，必在注册完成之后）避免类加载时序问题；仅缓存引用、不改变任何语义。</p>
 */
public final class PbCombItemRefs {

	private static Item honeycomb;
	private static Item combBlock;

	private PbCombItemRefs() {
	}

	/** 可配置蜜脾物品单例（惰性缓存）。 */
	public static Item honeycomb() {
		Item cached = honeycomb;
		if (cached == null) {
			cached = ModItems.CONFIGURABLE_HONEYCOMB.get();
			honeycomb = cached;
		}
		return cached;
	}

	/** 可配置蜜脾块物品单例（惰性缓存）。 */
	public static Item combBlock() {
		Item cached = combBlock;
		if (cached == null) {
			cached = ModItems.CONFIGURABLE_COMB_BLOCK.get();
			combBlock = cached;
		}
		return cached;
	}
}
