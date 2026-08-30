package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import cy.jdkdigital.productivebees.init.ModBlocks;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
	 * <b>线程安全</b>：全部方法无共享可变状态（只读静态引用 + 入参），天然线程安全。
	 */
public final class CombFuzzyMatcher {

	/** 原版蜜脾固定保留键（无 BEE_TYPE 组件，使用此常量作为过滤键） */
	public static final ResourceLocation VANILLA_HONEYCOMB_TYPE = ResourceLocation.parse("minecraft:honeycomb");
	/** Feywild 蜜脾由 PB 自带离心配方直接处理，物品 ID 同时作为无 BEE_TYPE 组件的过滤键。 */
	public static final ResourceLocation FEYWILD_HONEYCOMB_TYPE = ResourceLocation.fromNamespaceAndPath(
			ExternalCentrifugeCombIds.FEYWILD_NAMESPACE, ExternalCentrifugeCombIds.FEYWILD_HONEYCOMB_PATH);
	private static final ResourceLocation GHOSTLY_TYPE = ResourceLocation.parse("productivebees:ghostly");
	private static final ResourceLocation MILKY_TYPE = ResourceLocation.parse("productivebees:milky");
	private static final ResourceLocation POWDERY_TYPE = ResourceLocation.parse("productivebees:powdery");

	/**
	 * Registered item references are stable for the lifetime of a server. Resolve
	 * the DeferredHolders once on first matcher use instead of paying the holder
	 * lookup cost for every key in every AE2 inventory scan.
	 */
	private static final class ItemRefs {
		private static final Item CONFIGURABLE_HONEYCOMB = ModItems.CONFIGURABLE_HONEYCOMB.get();
		private static final Item CONFIGURABLE_COMB_BLOCK = ModItems.CONFIGURABLE_COMB_BLOCK.get();
		private static final Item HONEYCOMB_GHOSTLY = ModItems.HONEYCOMB_GHOSTLY.get();
		private static final Item HONEYCOMB_MILKY = ModItems.HONEYCOMB_MILKY.get();
		private static final Item HONEYCOMB_POWDERY = ModItems.HONEYCOMB_POWDERY.get();
		private static final Item COMB_GHOSTLY = ModBlocks.COMB_GHOSTLY.get().asItem();
		private static final Item COMB_MILKY = ModBlocks.COMB_MILKY.get().asItem();
		private static final Item COMB_POWDERY = ModBlocks.COMB_POWDERY.get().asItem();

		private ItemRefs() {
		}
	}

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
		return item == ItemRefs.CONFIGURABLE_HONEYCOMB
				|| item == ItemRefs.CONFIGURABLE_COMB_BLOCK
				|| isFixedComb(item)
				|| isFixedCombBlock(item)
				|| item == Items.HONEYCOMB
				|| item == Items.HONEYCOMB_BLOCK
				|| isExternalCentrifugeComb(item);
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
		return key != null && (key.getItem() == ItemRefs.CONFIGURABLE_COMB_BLOCK
				|| isFixedCombBlock(key.getItem()) || key.getItem() == Items.HONEYCOMB_BLOCK);
	}

	/**
	 * 判断 ItemStack 是否为蜜脾块（用于 GUI 精确模式区分蜜脾和蜜脾块）
	 *
	 * @param stack 物品栈
	 * @return true 表示该 stack 对应的是蜜脾块
	 */
	public static boolean isCombBlock(ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& (stack.getItem() == ItemRefs.CONFIGURABLE_COMB_BLOCK
						|| isFixedCombBlock(stack.getItem()) || stack.getItem() == Items.HONEYCOMB_BLOCK);
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
		ResourceLocation fixed = fixedBeeType(item);
		if (fixed != null) return fixed;
		if (item == ItemRefs.CONFIGURABLE_HONEYCOMB
				|| item == ItemRefs.CONFIGURABLE_COMB_BLOCK) {
			return stack.get(ModDataComponents.BEE_TYPE.get());
		}
		return null;
	}

	/** 返回无 BEE_TYPE 组件的固定离心输入所对应的真实 GUI 图标。 */
	public static ItemStack getFixedDisplayStack(ResourceLocation filterType, boolean block) {
		if (VANILLA_HONEYCOMB_TYPE.equals(filterType)) {
			return new ItemStack(block ? Items.HONEYCOMB_BLOCK : Items.HONEYCOMB);
		}
		if (GHOSTLY_TYPE.equals(filterType)) {
			return new ItemStack(block ? ItemRefs.COMB_GHOSTLY : ItemRefs.HONEYCOMB_GHOSTLY);
		}
		if (MILKY_TYPE.equals(filterType)) {
			return new ItemStack(block ? ItemRefs.COMB_MILKY : ItemRefs.HONEYCOMB_MILKY);
		}
		if (POWDERY_TYPE.equals(filterType)) {
			return new ItemStack(block ? ItemRefs.COMB_POWDERY : ItemRefs.HONEYCOMB_POWDERY);
		}
		if (!block && FEYWILD_HONEYCOMB_TYPE.equals(filterType)
				&& ExternalCentrifugeCombItems.FEYWILD_HONEYCOMB != null) {
			return new ItemStack(ExternalCentrifugeCombItems.FEYWILD_HONEYCOMB);
		}
		return ItemStack.EMPTY;
	}

	private static boolean isFixedComb(Item item) {
		return item == ItemRefs.HONEYCOMB_GHOSTLY
				|| item == ItemRefs.HONEYCOMB_MILKY
				|| item == ItemRefs.HONEYCOMB_POWDERY;
	}

	private static boolean isFixedCombBlock(Item item) {
		return item == ItemRefs.COMB_GHOSTLY
				|| item == ItemRefs.COMB_MILKY
				|| item == ItemRefs.COMB_POWDERY;
	}

	private static boolean isExternalCentrifugeComb(Item item) {
		return ExternalCentrifugeCombItems.FEYWILD_HONEYCOMB != null
				&& item == ExternalCentrifugeCombItems.FEYWILD_HONEYCOMB;
	}

	/**
	 * PB 13.13.5 内置离心配方中唯一使用外部模组物品作为直接输入的蜜脾。
	 * 保持精确白名单，避免把仅由其他模组机器处理的蜜脾误加入 AE 输入过滤。
	 */
	static boolean isExternalCentrifugeCombId(ResourceLocation itemId) {
		return ExternalCentrifugeCombIds.contains(itemId.getNamespace(), itemId.getPath());
	}

	/** 首次实际匹配时解析可选模组物品，后续 AE 扫描只进行 Item 引用比较。 */
	private static final class ExternalCentrifugeCombItems {
		private static final Item FEYWILD_HONEYCOMB = resolve(FEYWILD_HONEYCOMB_TYPE);

		private static Item resolve(ResourceLocation itemId) {
			if (!isExternalCentrifugeCombId(itemId)) return null;
			return BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
		}
	}

	/**
	 * 从 AEItemKey 提取蜜蜂类型 ID（无缓存，直读组件）
	 * <br/>
	 * <b>为什么去掉缓存</b>：本方法在拉取热路径上按「候选 key × 过滤槽位」的量级调用，
	 * 旧实现用 {@code ConcurrentHashMap<AEItemKey, ResourceLocation>} 记忆结果，
	 * 但缓存查找本身比重新计算更贵 —— AEItemKey 的 hash 桶命中后必须走 equals，
	 * 而 {@code AEItemKey.equals} 会调用 {@code ItemStack.isSameItemSameComponents}
	 * （在装有 geckolib 的环境还被其 mixin 包裹），spark 报告 vVh8WfPCN3 实测
	 * {@code ConcurrentHashMap.computeIfAbsent} 自身 self 时间就达 508ms。
	 * <p>
	 * 现在的实现只做固定 Item 引用比较；仅「可配置蜜脾/蜜脾块」这一种情况才读一次
	 * BEE_TYPE 组件，且经 {@link AEItemKey#get} 直读内部栈，不再 {@code toStack(1)} 拷贝。
	 * 结果不再驻留任何静态映射，同时消除了旧缓存无界增长的内存风险。
	 *
	 * @param key AE2 物品键
	 * @return 蜜蜂类型 ID，或 null（非蜜脾物品）
	 */
	public static ResourceLocation getBeeType(AEItemKey key) {
		if (key == null) return null;
		Item item = key.getItem();
		ResourceLocation fixed = fixedBeeType(item);
		if (fixed != null) return fixed;
		if (item == ItemRefs.CONFIGURABLE_HONEYCOMB || item == ItemRefs.CONFIGURABLE_COMB_BLOCK) {
			// AEItemKey.get 直读内部只读栈的组件表，无 ItemStack 拷贝
			return key.get(ModDataComponents.BEE_TYPE.get());
		}
		return null;
	}

	/** 无 BEE_TYPE 组件的固定蜜脾/蜜脾块 → 保留类型键；非固定蜜脾返回 null。 */
	private static ResourceLocation fixedBeeType(Item item) {
		if (item == Items.HONEYCOMB || item == Items.HONEYCOMB_BLOCK) return VANILLA_HONEYCOMB_TYPE;
		if (isExternalCentrifugeComb(item)) return FEYWILD_HONEYCOMB_TYPE;
		if (item == ItemRefs.HONEYCOMB_GHOSTLY || item == ItemRefs.COMB_GHOSTLY) return GHOSTLY_TYPE;
		if (item == ItemRefs.HONEYCOMB_MILKY || item == ItemRefs.COMB_MILKY) return MILKY_TYPE;
		if (item == ItemRefs.HONEYCOMB_POWDERY || item == ItemRefs.COMB_POWDERY) return POWDERY_TYPE;
		return null;
	}
}

/** 纯 Java 的外部蜜脾 ID 白名单，供无 Minecraft 运行时的单元测试直接覆盖。 */
final class ExternalCentrifugeCombIds {
	static final String FEYWILD_NAMESPACE = "feywild";
	static final String FEYWILD_HONEYCOMB_PATH = "honeycomb";

	private ExternalCentrifugeCombIds() {
	}

	static boolean contains(String namespace, String path) {
		return FEYWILD_NAMESPACE.equals(namespace) && FEYWILD_HONEYCOMB_PATH.equals(path);
	}
}
