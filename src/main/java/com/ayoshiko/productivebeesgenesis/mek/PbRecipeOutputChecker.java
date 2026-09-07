package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.util.InventoryUtils;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
	 * PB 配方输出检查器 — 封装配方输出兼容性、流体输出存在性、输出受阻判定等纯检查逻辑
	 * <br/>
	 * 从 {@link PbRecipeProcessor} 抽取，遵循单一职责原则：只负责配方输出的只读检查，
	 * 不涉及状态变更、进度推进或能量扣除。所有方法均为静态，无实例状态。
	 * <p>
	 * 线程安全：所有方法均为无状态纯函数，依赖传入参数，线程安全由调用方保证。
	 *
	 * @since 2.0.0
	 * @see PbRecipeProcessor
	 */
final class PbRecipeOutputChecker {

	/** 工具类，禁止实例化 */
	private PbRecipeOutputChecker() {
	}

	/**
	 * 检查PB配方输出与现有输出槽内容是否兼容
	 * <br/>
	 * 判定标准：<b>至少一种产物能落进某个输出槽</b>（槽位为空或可与之堆叠）。
	 * PB 输出写入器会把产物路由到任意可用槽位，不按配方 Map 顺序绑定固定槽。
	 *
	 * @param recipe				PB离心配方
	 * @param outputSlot			主输出槽
	 * @param secondaryOutputSlot	副输出槽1（可为null）
	 * @return true 如果配方输出与现有槽内容兼容
	 */
	public static boolean isPbOutputCompatible(CentrifugeRecipe recipe,
			@NotNull IInventorySlot outputSlot,
			@Nullable IInventorySlot secondaryOutputSlot) {
		return isPbOutputCompatible(recipe, outputSlot, secondaryOutputSlot, null);
	}

	/** 检查主、副1、副2三个物品输出槽是否能接收此 PB 配方。 */
	public static boolean isPbOutputCompatible(CentrifugeRecipe recipe,
			@NotNull IInventorySlot outputSlot,
			@Nullable IInventorySlot secondaryOutputSlot,
			@Nullable IInventorySlot tertiaryOutputSlot) {
		return isPbOutputCompatible(recipe, outputSlot, secondaryOutputSlot, tertiaryOutputSlot, false);
	}

	/**
	 * 检查输出槽兼容性，并可忽略已由副产物销毁升级过滤的蜜蜡。
	 * <br/>
	 * <b>为什么是「任一产物可放」而不是「全部产物一对一完全匹配」：</b>
	 * 产物种类可能多于三个物理输出槽（如屠夫蜜脾的 4 种肉，安装 7 个稳定性升级后概率产物
	 * 100% 触发；多模组整合包中种类更多）。完全匹配判定对这类配方永远返回 false，
	 * 会让工厂输入槽直接拒收（AE2/SFM 都送不进去）。放不下的种类由
	 * {@link PbRecipeFlusher} 延迟提交后续排空，机器只要能写出一种产物就能推进。
	 */
	public static boolean isPbOutputCompatible(CentrifugeRecipe recipe,
			@NotNull IInventorySlot outputSlot,
			@Nullable IInventorySlot secondaryOutputSlot,
			@Nullable IInventorySlot tertiaryOutputSlot,
			boolean discardWax) {
		Map<ItemStack, ChancedOutput> outputs = recipe.getRecipeOutputs();
		if (outputs.isEmpty()) {
			return true;
		}
		boolean hasCandidate = false;
		for (Map.Entry<ItemStack, ChancedOutput> entry : outputs.entrySet()) {
			ChancedOutput chanced = entry.getValue();
			if (chanced == null || chanced.chance() <= 0.0f || Math.max(0, chanced.max()) <= 0) continue;
			if (discardWax && UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
			hasCandidate = true;
			ItemStack template = entry.getKey();
			if (canPlace(template, outputSlot)
					|| canPlace(template, secondaryOutputSlot)
					|| canPlace(template, tertiaryOutputSlot)) {
				return true;
			}
		}
		// 全部产物都被过滤（如仅蜜蜡且已装副产物销毁升级）时不阻塞输入
		return !hasCandidate;
	}

	/** 槽位为空或可与模板堆叠时视为可放置（不判满：数量不足由 flusher 按批量回退处理）。 */
	private static boolean canPlace(ItemStack template, @Nullable IInventorySlot slot) {
		if (slot == null) return false;
		ItemStack existing = slot.getStack();
		return existing.isEmpty() || InventoryUtils.areItemsStackable(template, existing);
	}

	/** 检查配方是否有实际物品输出，已安装升级时蜜蜡输出不计入阻塞判定。 */
	public static boolean hasItemOutput(PbRecipeContext context, CentrifugeRecipe recipe) {
		for (Map.Entry<ItemStack, ChancedOutput> entry : recipe.getRecipeOutputs().entrySet()) {
			ChancedOutput chanced = entry.getValue();
			if (chanced == null || chanced.chance() <= 0.0f || Math.max(0, chanced.max()) <= 0) continue;
			if (context.suppressesUselessByproducts()
					&& UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
			return true;
		}
		return false;
	}

	/**
	 * 检查配方是否有流体输出（捕获异常防止自定义配方实现崩溃）
	 *
	 * @param recipe	PB离心配方
	 * @return true 如果配方有流体输出
	 */
	public static boolean hasFluidOutput(PbRecipeContext context, CentrifugeRecipe recipe) {
		try {
			FluidStack fluid = recipe.getFluidOutputs();
			return !fluid.isEmpty() && !(context.suppressesUselessByproducts()
					&& UselessByproductUpgradeHelper.isHoney(fluid));
		} catch (RuntimeException e) {
			// 自定义配方实现可能抛异常，fail-safe 返回 false（节流日志便于排查）
			LogThrottle.warn("pb_recipe_fluid_output",
					"PB 配方流体输出查询异常, 视为无流体输出: {}", e.toString());
			return false;
		}
	}

	/**
	 * 检查输出是否受阻（物品槽满/流体槽满/流体槽类型不匹配）
	 * <br/>
	 * 流体槽类型不匹配时同样暂停，避免无限重试浪费 TPS（Task 12 方案 F）。
	 *
	 * @param context			PB配方处理上下文（读取输出槽满载标志位 + 类型不匹配检查）
	 * @param process			进程索引
	 * @param recipe			PB离心配方（用于提取流体输出类型，判断类型不匹配）
	 * @param hasItemOutputs	配方是否有物品输出
	 * @param hasFluidOutputs	配方是否有流体输出
	 * @param isFluidTankFull	流体槽是否已满（由调用方缓存并提供）
	 * @return true 如果输出受阻
	 */
	public static boolean isOutputBlocked(PbRecipeContext context, int process, CentrifugeRecipe recipe,
			boolean hasItemOutputs, boolean hasFluidOutputs, boolean isFluidTankFull) {
		// Direct-AE 模式允许先完成一批并尝试写入 AE；拒收部分由 flusher 回退本地槽。
		// 若 AE 与本地均不可接收，pending 输出会保留并自然暂停该进程。
		if (context.productivebeesgenesis$isDirectAeOutputEnabled()) {
			return false;
		}
		if (hasItemOutputs && context.productivebeesgenesis$outputSlotsFull(process)) {
			return true;
		}
		if (hasFluidOutputs && isFluidTankFull) {
			return true;
		}
		// Task 12: 流体槽类型不匹配（SINGLE 主槽已有不同流体 / MULTI_PER_FLUID 槽位已满且无匹配槽）
		// 机器进入暂停状态，避免无限重试浪费 TPS
		if (hasFluidOutputs && isFluidTankTypeMismatch(context, process, recipe)) {
			return true;
		}
		return false;
	}

	/**
	 * 检查配方流体输出与流体槽是否存在类型不匹配（机器应进入暂停状态）
	 * <br/>
	 * <b>SINGLE 模式：</b>主槽已有不同类型流体 → 无法插入 → 返回 true
	 * <b>MULTI_PER_FLUID 模式：</b>所有槽位已分配且无匹配槽 → 无法分配新槽 → 返回 true
	 * 主槽为空 / 有匹配槽 → 返回 false（可继续处理）
	 * <p>
	 * 移除配置开关但保留检查,避免 TPS 退化(19 进程工厂最坏 304 次重试/tick)。
	 * 原配置 fluidTypeMismatchPause 关闭检查后机器每 tick 重复 accumulate+flush+reset 流程,
	 * 不如保留检查始终启用 — SINGLE 模式主槽已有不同类型时返回 true 触发暂停,MULTI 模式
	 * 委托 MultiFluidTankHolder.isTypeMismatch。
	 * <p>
	 * 异常防御：自定义配方实现可能抛异常，捕获后返回 false 不阻塞流程。
	 *
	 * @param context	PB配方处理上下文
	 * @param process	进程索引
	 * @param recipe	PB离心配方
	 * @return true 若存在类型不匹配，机器应暂停
	 */
	private static boolean isFluidTankTypeMismatch(PbRecipeContext context, int process, CentrifugeRecipe recipe) {
		// 检查始终启用:移除原 fluidTypeMismatchPause 配置开关,避免关闭检查导致 TPS 退化
		try {
			FluidStack recipeFluid = recipe.getFluidOutputs();
			if (recipeFluid.isEmpty()) {
				return false;
			}
			boolean result = context.isFluidTankTypeMismatch(recipeFluid);
			if (result) {
				DevLog.warn("fluid_tank", "流体类型不匹配,触发暂停");
			}
			return result;
		} catch (RuntimeException e) {
			// 自定义配方/容器实现可能抛异常，fail-safe 返回 false 不阻塞流程（节流日志便于排查）
			LogThrottle.warn("pb_recipe_fluid_mismatch",
					"PB 配方流体类型不匹配检查异常, 视为无类型不匹配: {}", e.toString());
			return false;
		}
	}
}
