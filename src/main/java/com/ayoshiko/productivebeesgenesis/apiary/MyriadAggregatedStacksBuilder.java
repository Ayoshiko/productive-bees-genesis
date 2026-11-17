package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.mek.WeightedAllocation;
import com.ayoshiko.productivebeesgenesis.mek.WeightedTypeSelector;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
	 * 万象创世蜜蜂产出预聚合器
	 * <br/>
	 * 万象创世蜜蜂每批选取最多三种模板，每个模板保留一个完整数量的大堆叠。
	 * 冻结状态与输出交接沿用普通采样的大堆叠协议，避免生成阶段按 64 拆栈后重复合并。
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
	 * 两条生成路径都失败时保留首根因与回退异常，由宿主保管该组已付周期。
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

	/** 主路径未恢复期间保留首因；回退成功不等于主路径已经恢复。 */
	private Exception honeycombFailure;
	private Exception combBlockFailure;

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
	 * @return 每个已选模板一个大堆叠，异常时退化为原版随机路径
	 */
	public List<ItemStack> buildAggregatedHoneycombs(int totalCount, Level level, Object factoryKey) {
		if (totalCount <= 0 || !MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return List.of();
		}
		MyriadBeeTypeCache.BeeTypeCacheSnapshot snapshot = MyriadBeeTypeCache.snapshot();
		List<ResourceLocation> allTypes = snapshot.beeTypes();
		if (allTypes.isEmpty()) {
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

			List<ItemStack> result = buildStacksFromAllocation(allocation, snapshot.selectTemplates(selectedTypes, false, level.getRandom()));
			if (honeycombFailure != null && !result.isEmpty()) {
				LogThrottle.infoWithCooldown("apiary_myriad_recovered", 60_000L,
						"万象蜂箱蜜脾主生成已恢复，原首因 {}；同类恢复合并报告", honeycombFailure.toString());
				honeycombFailure = null;
			}
			return result;
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
	 * @return 每个已选模板一个大堆叠，保留四倍数量；异常时退化为原版随机路径
	 */
	public List<ItemStack> buildAggregatedCombBlocks(int totalCount, Level level, Object factoryKey) {
		if (totalCount <= 0 || !MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return List.of();
		}
		MyriadBeeTypeCache.BeeTypeCacheSnapshot snapshot = MyriadBeeTypeCache.snapshot();
		List<ResourceLocation> allTypes = snapshot.combBlockBeeTypes();
		if (allTypes.isEmpty()) {
			return List.of();
		}

		try {
			int scaledTotal = SaturatingMath.saturatingToInt(
					SaturatingMath.saturatingMultiply(totalCount, COMB_BLOCK_SCALE));
			List<ResourceLocation> selectedTypes = WeightedTypeSelector.getInstance()
					.selectForProcess(PROCESS_INDEX, PROCESS_COUNT, level, allTypes, factoryKey);
			if (selectedTypes.isEmpty()) {
				return List.of();
			}
			double[] weights = WeightedTypeSelector.getInstance().getWeightsFor(selectedTypes);
			Map<ResourceLocation, Integer> allocation = WeightedAllocation.allocateByWeight(
					scaledTotal, selectedTypes, weights);

			List<ItemStack> result = buildStacksFromAllocation(allocation, snapshot.selectTemplates(selectedTypes, true, level.getRandom()));
			if (combBlockFailure != null && !result.isEmpty()) {
				LogThrottle.infoWithCooldown("apiary_myriad_recovered", 60_000L,
						"万象蜂箱蜜脾块主生成已恢复，原首因 {}；同类恢复合并报告", combBlockFailure.toString());
				combBlockFailure = null;
			}
			return result;
		} catch (Exception e) {
			return degradeToCombBlocks(totalCount, level, e);
		}
	}

	/**
	 * 从分配映射构造聚合 ItemStack 列表。
	 * 每个已选模板保留一个大堆叠，冻结状态保存独立数量，实际槽位仍由接收方限制。
	 * 不先按 64 拆栈再在输出阶段合并；完整 bee_type 与其它组件继续来自本次模板。
	 *
	 * @param allocation      类型→数量映射（来自 {@link WeightedAllocation#allocateByWeight}）
	 * @param templateByType  模板 Map（ResourceLocation → ItemStack），不可变快照
	 * @return 聚合后的 ItemStack 列表，空分配返回 {@link List#of()}
	 */
	private List<ItemStack> buildStacksFromAllocation(
			Map<ResourceLocation, Integer> allocation,
			Map<ResourceLocation, ItemStack> templateByType) {
		if (allocation.isEmpty() || templateByType.isEmpty()) {
			return List.of();
		}
		List<ItemStack> result = new ArrayList<>(allocation.size());
		for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
			int count = entry.getValue();
			if (count <= 0) {
				continue;
			}
			ItemStack template = templateByType.get(entry.getKey());
			if (template == null || template.isEmpty()) {
				return List.of();
			}
			result.add(template.copyWithCount(count));
		}
		return result;
	}

	/**
	 * 蜜脾预聚合异常降级路径
	 * <br/>
	 * 退化为 {@link MyriadCreationsEventHandler#getAggregatedRandomHoneycombs}（原 576 路径），
	 * 通过 {@link LogThrottle} 节流警告日志。两条路径都失败时由宿主保管原已付周期。
	 *
	 * @param totalCount 原始蜜脾总数
	 * @param level      世界（提供随机源）
	 * @param cause      触发降级的异常
	 * @return 原版随机路径返回的 ItemStack 列表
	 */
	private List<ItemStack> degradeToHoneycombs(int totalCount, Level level, Exception cause) {
		if (honeycombFailure == null) {
			honeycombFailure = cause;
			LogThrottle.errorWithCooldown("apiary_myriad_failure", 60_000L,
					"万象蜂箱蜜脾主生成失败，使用原随机回退并保留首因；同类故障合并报告", cause);
		} else {
			LogThrottle.warnWithCooldown("apiary_myriad_failure_summary", 60_000L,
					"万象蜂箱蜜脾主生成故障持续，原首因 {}；同类故障合并报告", honeycombFailure.toString());
		}
		try {
			return MyriadCreationsEventHandler.getAggregatedRandomHoneycombs(
					totalCount, level.getRandom());
		} catch (Exception | LinkageError fallback) {
			var failure = new IllegalStateException("Myriad honeycomb generation and fallback both failed", honeycombFailure);
			failure.addSuppressed(fallback);
			throw failure;
		}
	}

	/**
	 * 蜜脾块预聚合异常降级路径
	 * <br/>
	 * 退化为 {@link MyriadCreationsEventHandler#getAggregatedRandomCombBlocks}（原 576 路径），
	 * 传入 4× 缩放后的总数以保持机制平衡（与原 {@code BeeProduceProcessor} 调用
	 * {@code getAggregatedRandomCombBlocks(cappedMyriadCount * 4, random)} 语义一致）。
	 * 两条路径都失败时保留两次根因，不能以空产物提交已付周期。
	 *
	 * @param totalCount 原始蜜脾块等价总数（未缩放）
	 * @param level      世界（提供随机源）
	 * @param cause      触发降级的异常
	 * @return 原版随机路径返回的 ItemStack 列表
	 */
	private List<ItemStack> degradeToCombBlocks(int totalCount, Level level, Exception cause) {
		if (combBlockFailure == null) {
			combBlockFailure = cause;
			LogThrottle.errorWithCooldown("apiary_myriad_failure", 60_000L,
					"万象蜂箱蜜脾块主生成失败，使用原随机回退并保留首因；同类故障合并报告", cause);
		} else {
			LogThrottle.warnWithCooldown("apiary_myriad_failure_summary", 60_000L,
					"万象蜂箱蜜脾块主生成故障持续，原首因 {}；同类故障合并报告", combBlockFailure.toString());
		}
		try {
			int scaledTotal = SaturatingMath.saturatingToInt(
					SaturatingMath.saturatingMultiply(totalCount, COMB_BLOCK_SCALE));
			return MyriadCreationsEventHandler.getAggregatedRandomCombBlocks(
					scaledTotal, level.getRandom());
		} catch (Exception | LinkageError fallback) {
			var failure = new IllegalStateException("Myriad comb block generation and fallback both failed", combBlockFailure);
			failure.addSuppressed(fallback);
			throw failure;
		}
	}
}
