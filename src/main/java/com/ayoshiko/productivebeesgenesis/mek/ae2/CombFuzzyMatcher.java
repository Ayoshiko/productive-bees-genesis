package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import appeng.api.stacks.AEItemKey;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;

/**
 * 蜜脾模糊匹配器
 * <br/>
 * 在 AE2 输入拉取场景下封装蜜脾类物品的识别与 BEE_TYPE 提取逻辑。
 * <ol>
 *   <li>{@link #isCombItem(AEItemKey)}：按物品类型判定是否为蜜脾（NBT 忽略开启路径）</li>
 *   <li>{@link #getBeeType(ItemStack)} / {@link #getBeeType(AEItemKey)}：提取蜜蜂类型 ID</li>
 *   <li>{@link #isCombBlock(AEItemKey)}：判定是否为蜜脾块（用于拉取排序优先级）</li>
 * </ol>
 * <p>
 * <b>可选依赖说明</b>：本类直接 import appeng 类（AEItemKey），编译期需要 AE2 API。
 * 调用方必须通过 {@code Ae2IntegrationLoader.isAe2Loaded()} 守卫，确保 AE2 未加载时不触发类加载。
 * <p>
 * <b>线程安全</b>：AEItemKey → BEE_TYPE 缓存使用 {@link ConcurrentHashMap}，防御性保证并发遍历安全。
 * 实际由离心机服务端 tick 线程独占访问，参考 {@link AeItemKeyCache} 的线程模型。
 */
public final class CombFuzzyMatcher {

	/** 原版蜜脾固定保留键（无 BEE_TYPE 组件，使用此常量作为过滤键） */
	public static final ResourceLocation VANILLA_HONEYCOMB_TYPE = ResourceLocation.parse("minecraft:honeycomb");

	/**
	 * AEItemKey → BEE_TYPE 映射缓存
	 * <br/>
	 * 避免每次遍历 MEStorage 可用栈时重复调用 {@link ItemStack#get} 读取 BEE_TYPE 组件。
	 * 键为 AEItemKey（其 equals/hashCode 基于物品+组件快照，值稳定），值为蜜蜂类型 ID。
	 * <p>
	 * 注意：{@link ConcurrentHashMap#computeIfAbsent} 不存储 null 值，故非蜜脾物品不会进入缓存。
	 * 调用方应先通过 {@link #isCombItem(AEItemKey)} 过滤，避免对非蜜脾 key 重复计算。
	 */
	private static final ConcurrentHashMap<AEItemKey, ResourceLocation> aeItemKeyToBeeTypeCache = new ConcurrentHashMap<>();

	private CombFuzzyMatcher() {
		// 工具类，禁止实例化
	}

	/**
	 * 判断 AEItemKey 是否为蜜脾类物品（NBT 忽略开启时使用）
	 * <br/>
	 * 按物品类型匹配：
	 * <ul>
	 *   <li>{@code ModItems.CONFIGURABLE_HONEYCOMB}（可配置蜜脾，通过 BEE_TYPE 组件区分种类）</li>
	 *   <li>{@code ModItems.CONFIGURABLE_COMB_BLOCK}（可配置蜜脾块，通过 BEE_TYPE 组件区分种类）</li>
	 *   <li>{@link Items#HONEYCOMB}（原版蜜脾，无 BEE_TYPE 组件）</li>
	 * </ul>
	 * 比较 Item 引用（{@code ==}）：DeferredHolder.get() 返回的 Item 实例在全游戏周期固定，
	 * 与 {@link cy.jdkdigital.productivebeesgenesis.util.RecipeCacheManager} 等现有代码的判定方式一致。
	 *
	 * @param key AE2 物品键
	 * @return true 表示该 key 对应的是蜜脾类物品
	 */
	public static boolean isCombItem(AEItemKey key) {
		if (key == null) return false;
		Item item = key.getItem();
		return item == ModItems.CONFIGURABLE_HONEYCOMB.get()
				|| item == ModItems.CONFIGURABLE_COMB_BLOCK.get()
				|| item == Items.HONEYCOMB;
	}

	/**
	 * 判断 AEItemKey 是否为蜜脾块（用于拉取排序优先级）
	 * <br/>
	 * 蜜脾块因产物倍率高（4×）应优先拉取，避免高价值原料在网络中堆积。
	 *
	 * @param key AE2 物品键
	 * @return true 表示该 key 对应的是蜜脾块
	 */
	public static boolean isCombBlock(AEItemKey key) {
		return key != null && key.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get();
	}

	/**
	 * 判断 ItemStack 是否为蜜脾块（用于 GUI 精确模式区分蜜脾和蜜脾块）
	 *
	 * @param stack 物品栈
	 * @return true 表示该 stack 对应的是蜜脾块
	 */
	public static boolean isCombBlock(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get();
	}

	/**
	 * 从 ItemStack 提取蜜蜂类型 ID
	 * <br/>
	 * 通过 {@link ModDataComponents#BEE_TYPE} 数据组件读取：
	 * <ul>
	 *   <li>可配置蜜脾/蜜脾块：返回 BEE_TYPE 组件值（如 productivebees:iron）</li>
	 *   <li>原版蜜脾（无 BEE_TYPE）：返回固定保留键 {@link #VANILLA_HONEYCOMB_TYPE}</li>
	 *   <li>非蜜脾物品：返回 null</li>
	 * </ul>
	 * 与 PB 配方系统兼容：拉取的栈保留原始 BEE_TYPE 组件，{@code poweredExtraction} 提取的栈
	 * 组件完整，离心机配方系统（{@link cy.jdkdigital.productivebeesgenesis.mek.PbRecipeFinder}）
	 * 无需任何改动即可正确识别蜜脾种类。
	 *
	 * @param stack 物品栈
	 * @return 蜜蜂类型 ID，或 null（非蜜脾物品）
	 */
	public static ResourceLocation getBeeType(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Item item = stack.getItem();
		if (item == Items.HONEYCOMB) {
			return VANILLA_HONEYCOMB_TYPE;
		}
		if (item == ModItems.CONFIGURABLE_HONEYCOMB.get()
				|| item == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
			return stack.get(ModDataComponents.BEE_TYPE.get());
		}
		return null;
	}

	/**
	 * 从 AEItemKey 提取蜜蜂类型 ID（带缓存）
	 * <br/>
	 * AEItemKey 内部包含 ItemStack 的组件快照，通过 {@link AEItemKey#toStack(int)} 获取
	 * ItemStack 后委托 {@link #getBeeType(ItemStack)}。
	 * <p>
	 * <b>性能优化</b>：使用 {@link ConcurrentHashMap} 缓存 AEItemKey → BEE_TYPE 映射，
	 * 避免每次遍历 MEStorage 可用栈时重复调用 {@link ItemStack#get} 读取组件。
	 * <p>
	 * <b>调用约定</b>：调用方应先通过 {@link #isCombItem(AEItemKey)} 过滤非蜜脾 key，
	 * 避免对非蜜脾 key 重复触发 toStack + getBeeType（ConcurrentHashMap 不缓存 null 值）。
	 *
	 * @param key AE2 物品键
	 * @return 蜜蜂类型 ID，或 null（非蜜脾物品）
	 */
	public static ResourceLocation getBeeType(AEItemKey key) {
		if (key == null) return null;
		return aeItemKeyToBeeTypeCache.computeIfAbsent(key, k -> {
			// AEItemKey.toStack(int) 创建包含完整组件快照的 ItemStack
			ItemStack stack = k.toStack(1);
			return getBeeType(stack);
		});
	}

	/**
	 * 清空 AEItemKey → BEE_TYPE 缓存
	 * <br/>
	 * 当前为预留方法，未在节点销毁时调用。AEItemKey → BEE_TYPE 映射是全局通用的
	 * （不依赖具体离心机节点），故全局缓存不需要在节点销毁时清空。
	 * 缓存条目通过 {@link ConcurrentHashMap#computeIfAbsent} 懒初始化，
	 * 非蜜脾物品不会进入缓存（调用方应先通过 {@link #isCombItem(AEItemKey)} 过滤）。
	 * 若未来需要强制刷新缓存（如配置变更），可调用此方法。
	 */
	public static void clearCache() {
		aeItemKeyToBeeTypeCache.clear();
	}
}
