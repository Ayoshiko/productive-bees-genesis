package com.ayoshiko.productivebeesgenesis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 蜜蜂事件处理器公共逻辑基类
 * <br/>
 * 提供 万象创世 与 无尽·创世 两个事件处理器的共享逻辑：
 * <ol>
 *   <li>蜜蜂类型缓存更新（子类提供排除规则与额外过滤）</li>
 *   <li>离心机追加产出（预分配算法，保证总产出=消耗数且不溢出）</li>
 * </ol>
 * <p>
 * <b>职责拆分（Task 20）</b>：原文件 624 行，已将纯算法逻辑抽取到独立工具类，
 * 本类仅保留与 PB 数据源/配方管理器交互的核心逻辑：
 * <ul>
 *   <li>{@link RandomHoneycombSelector} — 随机蜜脾/蜜脾块选择与分配算法</li>
 *   <li>{@link CombBlockCheckCache} — 离心机空转拦截缓存</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：所有公共方法均为线程安全，使用 {@link CopyOnWriteArrayList}。
 * <p>
 * <b>设计说明</b>：基类不持有任何状态字段，缓存由子类各自持有，确保两个处理器互不干扰。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public abstract class AbstractCombEventHandler {

	/** 缓存更新间隔（tick） */
	protected static final int CACHE_UPDATE_INTERVAL = 20;

	/**
	 * hasCentrifugeRecipe 测试输入复用（ThreadLocal）
	 * <p>
	 * 原静态共享实例在多线程同时调用 {@link #hasCentrifugeRecipe} 时会同时修改
	 * TEST_COMB 的 bee_type 组件和 HANDLER 的槽位，导致竞态。
	 * 改为 ThreadLocal：每线程持有独立的 Handler 与 ItemStack 实例，
	 * 既避免竞态又保持每线程内复用（仅首次分配）。
	 * <p>
	 * Handler 单参构造时 blockEntity=null，onContentsChanged 空操作；
	 * matches() 仅读 getItem，无世界状态依赖，可在线程内复用。
	 */
	private static final ThreadLocal<InventoryHandlerHelper.BlockEntityItemStackHandler> THREAD_LOCAL_HANDLER =
			ThreadLocal.withInitial(() -> new InventoryHandlerHelper.BlockEntityItemStackHandler(2));

	private static final ThreadLocal<ItemStack> THREAD_LOCAL_TEST_COMB =
			ThreadLocal.withInitial(() -> new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get()));

	// ========== 缓存更新 ==========

	/**
	 * 更新蜜蜂类型缓存（原子替换，避免竞态窗口）
	 * <p>
	 * 通用流程：
	 * <ol>
	 *   <li>从PB数据源读取所有蜜蜂类型</li>
	 *   <li>排除子类指定的类型（如自身、避免循环转化的类型）</li>
	 *   <li>排除没有离心配方的蜜蜂</li>
	 *   <li>应用子类提供的额外过滤（如配置文件过滤）</li>
	 * </ol>
	 *
	 * @param level         服务端世界
	 * @param excludedTypes 需要排除的蜜蜂类型集合
	 * @param extraFilter   额外过滤谓词（可为null表示无额外过滤），返回true保留该类型
	 * @return 新的缓存列表
	 */
	protected static CopyOnWriteArrayList<ResourceLocation> buildBeeTypeCache(
			ServerLevel level,
			Set<ResourceLocation> excludedTypes,
			Predicate<ResourceLocation> extraFilter) {
		try {
			Map<ResourceLocation, ?> beeData = BeeReloadListener.INSTANCE.getData();
			if (beeData == null || beeData.isEmpty()) {
				return new CopyOnWriteArrayList<>();
			}

			List<ResourceLocation> newTypes = new ArrayList<>(beeData.size());
			for (ResourceLocation beeType : beeData.keySet()) {
				if (excludedTypes.contains(beeType)) continue;
				if (!hasCentrifugeRecipe(level, beeType)) continue;
				if (extraFilter != null && !extraFilter.test(beeType)) continue;
				newTypes.add(beeType);
			}

			return new CopyOnWriteArrayList<>(newTypes);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("更新蜜蜂类型缓存时发生错误", e);
			return new CopyOnWriteArrayList<>();
		}
	}

	/**
	 * 检查指定蜜蜂类型是否有对应的离心配方
	 * <p>
	 * 检查失败时保守返回true，避免误删有效蜜蜂。
	 *
	 * @param level   服务端世界
	 * @param beeType 蜜蜂类型ID
	 * @return 是否存在离心配方
	 */
	protected static boolean hasCentrifugeRecipe(ServerLevel level, ResourceLocation beeType) {
		try {
			// 每线程独立的 Handler 与 ItemStack，避免多线程竞态
			ItemStack testComb = THREAD_LOCAL_TEST_COMB.get();
			InventoryHandlerHelper.BlockEntityItemStackHandler handler = THREAD_LOCAL_HANDLER.get();
			// 重置 bee_type 组件（线程内复用时上次写入的值仍残留）
			testComb.set(ModDataComponents.BEE_TYPE.get(), beeType);
			handler.setStackInSlot(InventoryHandlerHelper.INPUT_SLOT, testComb);

			RecipeHolder<CentrifugeRecipe> recipe = level.getRecipeManager()
					.getRecipeFor(ModRecipeTypes.CENTRIFUGE_TYPE.get(),
							(RecipeInput) handler, level)
					.orElse(null);
			return recipe != null;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("hasCentrifugeRecipe 检查异常，保守返回 true", e);
			return true;
		}
	}

	// ========== 离心机追加产出 ==========

	/**
	 * 离心机追加随机蜜脾产出（核心机制：转化）
	 * <p>
	 * 设计理念：创世蜜蜂 = 「支付一个创世物品，转化为任意同类型物品」。
	 * 每个输入的创世蜜脾精确转化为1个随机蜜脾（线性缩放）。
	 * <p>
	 * <b>槽位安全策略（预分配）</b>：PB离心机仅9格输出槽，若32个蜜脾随机出>9种则放不下。
	 * 采用预分配算法：
	 * <ol>
	 *   <li>限制种类数K = min(9, totalCount)，确保不超槽位</li>
	 *   <li>从缓存中随机选K种不同蜜蜂类型</li>
	 *   <li>将totalCount均匀分配到K种上（每种至少1个）</li>
	 * </ol>
	 * 保证：总产出数 == 消耗数，且永不溢出。
	 *
	 * @param input              输入物品
	 * @param invHandler         物品处理器
	 * @param random             随机源
	 * @param productivityModifier PB升级倍率
	 * @param isTargetComb       判断是否为目标蜜脾
	 * @param isTargetBlock      判断是否为目标蜜脾块
	 * @param cachedBeeTypes     蜜蜂类型缓存
	 */
	protected static void appendRandomCombsInternal(
			ItemStack input,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			int productivityModifier,
			Predicate<ItemStack> isTargetComb,
			Predicate<ItemStack> isTargetBlock,
			CopyOnWriteArrayList<ResourceLocation> cachedBeeTypes) {
		if (!isTargetComb.test(input) && !isTargetBlock.test(input)) return;
		if (!CombBlockCheckCache.hasOutputSpace(invHandler)) return;

		boolean isCombBlock = isTargetBlock.test(input);
		int totalCount = Math.max(1, productivityModifier);

		int maxTypes = Math.min(9, totalCount);
		List<ResourceLocation> selectedTypes =
				RandomHoneycombSelector.selectDistinctBeeTypes(maxTypes, random, cachedBeeTypes);
		if (selectedTypes.isEmpty()) return;

		Map<ResourceLocation, Integer> allocation =
				RandomHoneycombSelector.allocateEvenly(totalCount, selectedTypes);

		Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();
		if (invHandler instanceof InventoryHandlerHelper.BlockEntityItemStackHandler outputHandler) {
			for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
				try {
					ItemStack output = new ItemStack(baseItem, entry.getValue());
					output.set(ModDataComponents.BEE_TYPE.get(), entry.getKey());
					outputHandler.addOutput(output);
				} catch (Exception e) {
					ProductiveBeesGenesis.LOGGER.warn("追加随机蜜脾产出异常", e);
					break;
				}
			}
		}
	}
}
