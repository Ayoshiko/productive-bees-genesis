package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.WeightedAllocation;
import com.ayoshiko.productivebeesgenesis.mek.WeightedTypeSelector;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 万象创世蜜蜂产出预聚合器
 * <br/>
 * 将万象创世蜜蜂的随机产出预聚合为 ≤9 个聚合 ItemStack（每种 bee_type 1 个 stack，
 * count &gt; 64 时按 64 拆分），替代原 576 个独立 ItemStack 设计，
 * 将 {@code BeeProduceProcessor.distributeToOutput} 迭代次数从 576 降为 ≤9。
 * <p>
 * <b>设计要点</b>：
 * <ul>
 *   <li>蜂箱视为单进程工厂，调用 {@link WeightedTypeSelector#selectForProcess}
 *       选取 3 种 bee_type（{@code processIndex=0, processCount=1}）</li>
 *   <li>调用 {@link WeightedAllocation#allocateByWeight} 按动态权重分配 totalCount</li>
 *   <li>使用 {@link MyriadBeeTypeCache.BeeTypeCacheSnapshot#honeycombTemplateByType} /
 *       {@link MyriadBeeTypeCache.BeeTypeCacheSnapshot#combBlockTemplateByType}
 *       O(1) 查找预构建模板，{@link ItemStack#copyWithCount} 构造聚合 stack</li>
 *   <li>蜜脾块路径按 4× 缩放 totalCount（与原 Mixin 单次产出 4 个蜜脾块语义一致）</li>
 * </ul>
 * <p>
 * <b>降级路径</b>：任一异常时退化为 {@link MyriadCreationsEventHandler#getAggregatedRandomHoneycombs} /
 * {@link MyriadCreationsEventHandler#getAggregatedRandomCombBlocks}（原 576 路径），
 * 通过 {@link LogThrottle} 节流警告日志，避免高频异常刷屏。
 * 二次异常时返回 {@link List#of()}，确保不影响 flush 主流程。
 * <p>
 * <b>线程安全</b>：无状态对象，依赖 {@link WeightedTypeSelector} 与 {@link MyriadBeeTypeCache}
 * 的线程安全保证。服务端单线程 tick 场景下无需额外同步。
 *
 * @since 1.0.0
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class MyriadAggregatedStacksBuilder {

	/** 蜂箱视为单进程工厂 — processIndex */
	private static final int PROCESS_INDEX = 0;

	/** 蜂箱视为单进程工厂 — processCount */
	private static final int PROCESS_COUNT = 1;

	/** 蜜脾块 4× 缩放系数（与原 Mixin 单次产出 4 个蜜脾块语义一致） */
	private static final int COMB_BLOCK_SCALE = 4;

	/** 单个 ItemStack 堆叠上限 */
	private static final int MAX_STACK_SIZE = 64;

	/** 降级日志冷却器（ms 模式，5 秒冷却，避免高频异常刷屏） */
	private final LogThrottle degradeThrottle = new LogThrottle();

	/** 默认构造 — 无状态对象 */
	public MyriadAggregatedStacksBuilder() {
	}

	/**
	 * 构建聚合蜜脾 ItemStack 列表
	 * <br/>
	 * 内部流程：
	 * <ol>
	 *   <li>从 {@link MyriadBeeTypeCache#cachedBeeTypes()} 读取 allTypes（可能为空）</li>
	 *   <li>调用 {@link WeightedTypeSelector#selectForProcess} 选 3 种 bee_type</li>
	 *   <li>调用 {@link WeightedTypeSelector#getWeightsFor} 获取权重</li>
	 *   <li>调用 {@link WeightedAllocation#allocateByWeight} 分配 totalCount</li>
	 *   <li>用 {@code honeycombTemplateByType.get(beeType).copyWithCount(count)} 构造聚合 stack</li>
	 * </ol>
	 *
	 * @param totalCount  蜜脾总数（受 {@code MYRIAD_RANDOM_CAP=576} 限制，保护输出槽总容量）
	 * @param level       世界（用于权重表刷新与降级路径的随机源）
	 * @param factoryKey  工厂实例（作为 {@link WeightedTypeSelector} 工厂级 tick 缓存的 key，
	 *                    {@code null} 时跳过 tick 缓存，每次都触发实际选型）
	 * @return 聚合后的 ItemStack 列表（≤9 个），异常时退化为原版随机路径
	 */
	public List<ItemStack> buildAggregatedHoneycombs(int totalCount, Level level, Object factoryKey) {
		if (totalCount <= 0) {
			return List.of();
		}
		List<ResourceLocation> allTypes = MyriadBeeTypeCache.cachedBeeTypes();
		if (allTypes == null || allTypes.isEmpty()) {
			return List.of();
		}

		try {
			List<ResourceLocation> selectedTypes = WeightedTypeSelector.getInstance()
					.selectForProcess(PROCESS_INDEX, PROCESS_COUNT, level, allTypes, factoryKey);
			if (selectedTypes.isEmpty()) {
				return List.of();
			}
			double[] weights = WeightedTypeSelector.getInstance().getWeightsFor(selectedTypes);
			Map<ResourceLocation, Integer> allocation = WeightedAllocation.allocateByWeight(
					totalCount, selectedTypes, weights);

			MyriadBeeTypeCache.BeeTypeCacheSnapshot snapshot = MyriadBeeTypeCache.snapshot();
			return buildStacksFromAllocation(allocation, snapshot.honeycombTemplateByType());
		} catch (Exception e) {
			return degradeToHoneycombs(totalCount, level, e);
		}
	}

	/**
	 * 构建聚合蜜脾块 ItemStack 列表（4× 缩放）
	 * <br/>
	 * 与 {@link #buildAggregatedHoneycombs} 流程一致，差异：
	 * <ul>
	 *   <li>totalCount 按 {@value #COMB_BLOCK_SCALE}× 缩放（与原 Mixin 单次产出 4 个蜜脾块语义一致）</li>
	 *   <li>使用 {@code combBlockTemplateByType} 模板（而非 honeycombTemplateByType）</li>
	 * </ul>
	 *
	 * @param totalCount  蜜脾块等价总数（未缩放，方法内部按 4× 放大）
	 * @param level       世界
	 * @param factoryKey  工厂实例
	 * @return 聚合后的 ItemStack 列表（蜜脾块路径因 4× 缩放最坏可达 ≤36 个：3 类型 × ceil(576×4/3/64)=12），异常时退化为原版随机路径
	 */
	public List<ItemStack> buildAggregatedCombBlocks(int totalCount, Level level, Object factoryKey) {
		if (totalCount <= 0) {
			return List.of();
		}
		List<ResourceLocation> allTypes = MyriadBeeTypeCache.cachedBeeTypes();
		if (allTypes == null || allTypes.isEmpty()) {
			return List.of();
		}

		try {
			int scaledTotal = totalCount * COMB_BLOCK_SCALE;
			List<ResourceLocation> selectedTypes = WeightedTypeSelector.getInstance()
					.selectForProcess(PROCESS_INDEX, PROCESS_COUNT, level, allTypes, factoryKey);
			if (selectedTypes.isEmpty()) {
				return List.of();
			}
			double[] weights = WeightedTypeSelector.getInstance().getWeightsFor(selectedTypes);
			Map<ResourceLocation, Integer> allocation = WeightedAllocation.allocateByWeight(
					scaledTotal, selectedTypes, weights);

			MyriadBeeTypeCache.BeeTypeCacheSnapshot snapshot = MyriadBeeTypeCache.snapshot();
			return buildStacksFromAllocation(allocation, snapshot.combBlockTemplateByType());
		} catch (Exception e) {
			return degradeToCombBlocks(totalCount, level, e);
		}
	}

	/**
	 * 从分配映射构造聚合 ItemStack 列表
	 * <br/>
	 * 每种 bee_type 按 {@code ceil(count / 64)} 拆分为多个 stack，单个 stack 数量 ≤ {@value #MAX_STACK_SIZE}。
	 * 在 {@code totalCount ≤ 576, selectedTypes.size() = 3} 的最坏场景下，
	 * 总 stack 数 ≤ 3 × ceil(192 / 64) = 9，符合 spec 的 "≤9 个聚合 ItemStack" 约束。
	 *
	 * @param allocation      类型→数量映射（来自 {@link WeightedAllocation#allocateByWeight}）
	 * @param templateByType  模板 Map（ResourceLocation → ItemStack），不可变快照
	 * @return 聚合后的 ItemStack 列表，空分配返回 {@link List#of()}
	 */
	private List<ItemStack> buildStacksFromAllocation(
			Map<ResourceLocation, Integer> allocation,
			Map<ResourceLocation, ItemStack> templateByType) {
		if (allocation == null || allocation.isEmpty()) {
			return List.of();
		}
		if (templateByType == null || templateByType.isEmpty()) {
			return List.of();
		}
		List<ItemStack> result = new ArrayList<>(allocation.size() * 2);
		for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
			int count = entry.getValue();
			if (count <= 0) {
				continue;
			}
			ItemStack template = templateByType.get(entry.getKey());
			if (template == null || template.isEmpty()) {
				continue;
			}
			int remaining = count;
			while (remaining > 0) {
				int stackSize = Math.min(MAX_STACK_SIZE, remaining);
				result.add(template.copyWithCount(stackSize));
				remaining -= stackSize;
			}
		}
		return result;
	}

	/**
	 * 蜜脾预聚合异常降级路径
	 * <br/>
	 * 退化为 {@link MyriadCreationsEventHandler#getAggregatedRandomHoneycombs}（原 576 路径），
	 * 通过 {@link LogThrottle} 节流警告日志。二次异常时返回 {@link List#of()}，避免影响 flush 主流程。
	 *
	 * @param totalCount 原始蜜脾总数
	 * @param level      世界（提供随机源）
	 * @param cause      触发降级的异常
	 * @return 原版随机路径返回的 ItemStack 列表，或二次异常时返回空列表
	 */
	private List<ItemStack> degradeToHoneycombs(int totalCount, Level level, Exception cause) {
		degradeThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
			ProductiveBeesGenesis.LOGGER.warn(
					"万象创世预聚合蜜脾异常，退化为原版随机路径{}",
					suppressed > 0 ? " (抑制 " + suppressed + " 次类似警告)" : "",
					cause);
		});
		try {
			return MyriadCreationsEventHandler.getAggregatedRandomHoneycombs(
					totalCount, level.getRandom());
		} catch (Exception fallback) {
			return List.of();
		}
	}

	/**
	 * 蜜脾块预聚合异常降级路径
	 * <br/>
	 * 退化为 {@link MyriadCreationsEventHandler#getAggregatedRandomCombBlocks}（原 576 路径），
	 * 传入 4× 缩放后的总数以保持机制平衡（与原 {@code BeeProduceProcessor} 调用
	 * {@code getAggregatedRandomCombBlocks(cappedMyriadCount * 4, random)} 语义一致）。
	 * 二次异常时返回 {@link List#of()}。
	 *
	 * @param totalCount 原始蜜脾块等价总数（未缩放）
	 * @param level      世界（提供随机源）
	 * @param cause      触发降级的异常
	 * @return 原版随机路径返回的 ItemStack 列表，或二次异常时返回空列表
	 */
	private List<ItemStack> degradeToCombBlocks(int totalCount, Level level, Exception cause) {
		degradeThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
			ProductiveBeesGenesis.LOGGER.warn(
					"万象创世预聚合蜜脾块异常，退化为原版随机路径{}",
					suppressed > 0 ? " (抑制 " + suppressed + " 次类似警告)" : "",
					cause);
		});
		try {
			int scaledTotal = totalCount * COMB_BLOCK_SCALE;
			return MyriadCreationsEventHandler.getAggregatedRandomCombBlocks(
					scaledTotal, level.getRandom());
		} catch (Exception fallback) {
			return List.of();
		}
	}
}
