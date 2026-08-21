package com.ayoshiko.productivebeesgenesis.util;

import mekanism.api.MekanismItemAbilities;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Set;

/**
	 * 扳手判定工具 — 多重检测确保 AE2 / OmniTools 等通用扳手能拆解 MEK 机器
	 * <br/>
	 * 检测顺序（短路返回）：
	 * <ol>
	 *   <li>{@link MekanismUtils#canUseAsWrench} — MEK 配置器优先</li>
	 *   <li>{@code c:tools/wrench} 通用扳手标签</li>
	 *   <li>{@link MekanismItemAbilities#WRENCH_DISMANTLE} ItemAbility</li>
	 *   <li>显式物品 ID 兜底（AE2 / OmniTools）</li>
	 * </ol>
	 * 模块 2 修复背景：AE2 扳手虽在 {@code c:tools/wrench} 与 MEK {@code configurators} 标签中，
	 * 但 MEK 内部 canUseAsWrench 会因 AE2 扳手暴露 {@code wrench_rotate} 等动作而排除之，
	 * 故需要多重兜底以覆盖更多通用扳手。
	 * <p>
	 * 设计原则：SRP（仅负责扳手判定）、线程安全（无状态）、DIP（依赖 ItemAbility 抽象）。
	 *
	 * @since 1.0.0
	 */
public final class WrenchCapabilityHelper {

	/** 通用扳手标签 c:tools/wrench */
	private static final TagKey<Item> WRENCH_TAG = TagKey.create(
			Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

	/** 兜底匹配的扳手物品 ID（前 3 步判定失败时的最终保险） */
	private static final Set<ResourceLocation> EXPLICIT_WRENCH_IDS = Set.of(
			ResourceLocation.fromNamespaceAndPath("ae2", "certus_quartz_wrench"),
			ResourceLocation.fromNamespaceAndPath("ae2", "nether_quartz_wrench"),
			ResourceLocation.fromNamespaceAndPath("ae2", "network_tool"),
			ResourceLocation.fromNamespaceAndPath("omnitools", "omni_wrench"),
			ResourceLocation.fromNamespaceAndPath("omnitools", "omni_vajra"));

	/** OmniTools 在 ME 光束兼容模式下写入 ItemStack 的自定义数据键。 */
	private static final String OMNITOOLS_MODE_TAG = "ToolMode";
	private static final String OMNITOOLS_LINK_MODE = "link";
	private static final ResourceLocation OMNITOOLS_WRENCH_ID =
			ResourceLocation.fromNamespaceAndPath("omnitools", "omni_wrench");

	private WrenchCapabilityHelper() {
		// 工具类禁止实例化
	}

	/**
	 * 判定物品栈是否可作为扳手用于机器拆卸
	 *
	 * @param stack 物品栈
	 * @return true 表示可作扳手拆卸
	 */
	public static boolean canUseAsWrench(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		// 1. MEK 配置器优先
		if (MekanismUtils.canUseAsWrench(stack)) {
			return true;
		}
		// 2. 通用扳手标签
		if (stack.is(WRENCH_TAG)) {
			return true;
		}
		// 3. WRENCH_DISMANTLE ItemAbility 检测
		if (stack.canPerformAction(MekanismItemAbilities.WRENCH_DISMANTLE)) {
			return true;
		}
		// 4. 显式物品 ID 兜底（AE2 / OmniTools 等）
		return EXPLICIT_WRENCH_IDS.contains(
				BuiltInRegistries.ITEM.getKey(stack.getItem()));
	}

	/**
	 * 判断 OmniTools 扳手当前是否处于 ME 光束绑定模式。
	 * <p>
	 * OmniTools 的 ME 光束兼容 handler 会在该模式下把右键转发给
	 * {@code LaserBindingTool}。调用方必须放行这个交互，否则本模组的潜行扳手
	 * 拆卸事件会先一步拆掉机器，导致无线塔永远无法绑定。
	 *
	 * @param stack 交互时手持的物品
	 * @return true 表示应让 OmniTools/ME 光束优先处理
	 */
	public static boolean isOmniToolsLinkMode(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
		if (!OMNITOOLS_WRENCH_ID.equals(itemId)) {
			return false;
		}
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return OMNITOOLS_LINK_MODE.equals(customData.copyTag().getString(OMNITOOLS_MODE_TAG));
	}
}
