package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.CentrifugeBlockEntityAccessor;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModTags;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
	 * 离心机 Mixin 公共逻辑工具类
	 * <br/>
	 * 抽取 6 个离心机 Mixin（Centrifuge / HeatedCentrifuge / PoweredCentrifuge × Myriad/Infinity）
	 * 中重复的以下逻辑：
	 * <ol>
	 *   <li>canOperate RETURN 输出满检查</li>
	 *   <li>canProcessRecipe HEAD 输出满检查</li>
	 *   <li>completeRecipeProcessing HEAD 预留全部产物空间并追加随机蜜脾</li>
	 * </ol>
	 * Mixin 类必须针对不同目标类独立定义，但方法体可委托给本工具类的静态方法，
	 * 通过函数式参数注入差异化的 EventHandler 调用，遵循 DRY 原则。
	 * <p>
	 * <b>注意</b>：本类必须放在 mixin 包之外（util 包），因为 Mixin 框架将 mixin 包下的
	 * 所有类视为 Mixin 类，不允许直接引用非 Mixin 类，否则抛出 IllegalClassLoadError。
	 */
public final class CentrifugeMixinHelper {

	private CentrifugeMixinHelper() {
	}

	/**
	 * canOperate RETURN 检查：输出满时阻止机器启动
	 *
	 * @param cir         回调信息
	 * @param entity      离心机实例（Mixin this 强转）
	 * @param shouldBlock 判断是否应阻止运行的函数（传入 EventHandler::shouldBlockOperation）
	 */
	public static void checkCanOperate(
			CallbackInfoReturnable<Boolean> cir,
			CentrifugeBlockEntity entity,
			Function<IItemHandlerModifiable, Boolean> shouldBlock) {
		// try/catch 防止 shouldBlock 或 inventoryHandler 访问抛异常导致 PB 原方法崩溃
		try {
			if (!cir.getReturnValue()) return;
			if (shouldBlock.apply(entity.inventoryHandler)) {
				cir.setReturnValue(false);
			}
		} catch (RuntimeException e) {
			// 异常时不阻止机器运行（默认 false），让 PB 原逻辑继续
			// DevLog 节流日志便于排查（高频 tick 路径，避免刷屏）
			DevLog.warn("centrifuge_mixin", "CentrifugeMixinHelper.checkCanOperate 执行异常, 跳过空转拦截: {}",
					e.toString());
		}
	}

	/**
	 * canProcessRecipe HEAD 检查：输出满时阻止配方处理（双重保险）
	 *
	 * @param invHandler  物品处理器
	 * @param cir         回调信息
	 * @param shouldBlock 判断是否应阻止运行的函数
	 */
	public static void checkCanProcessRecipe(
			IItemHandlerModifiable invHandler,
			CallbackInfoReturnable<Boolean> cir,
			Function<IItemHandlerModifiable, Boolean> shouldBlock) {
		// try/catch 防止 shouldBlock 或 invHandler 访问抛异常导致 PB 原方法崩溃
		try {
			if (shouldBlock.apply(invHandler)) {
				cir.setReturnValue(false);
			}
		} catch (RuntimeException e) {
			// 异常时不阻止配方处理（默认 false），让 PB 原逻辑继续
			// DevLog 节流日志便于排查（高频 tick 路径，避免刷屏）
			DevLog.warn("centrifuge_mixin", "CentrifugeMixinHelper.checkCanProcessRecipe 执行异常, 跳过空转拦截: {}",
					e.toString());
		}
	}

	/**
	 * 在 PB 扣料之前追加随机蜜脾，返回 false 时调用方必须取消配方完成。
	 * 仅在服务器生产线程调用；提交异常向上传播，不能吞掉后重试部分提交的产物。
	 *
	 * @param recipe      本次 PB 配方
	 * @param invHandler   物品处理器
	 * @param random       随机源
	 * @param entity       离心机实例（Mixin this 强转）
	 * @param heated       热能机去蜡及蜜脾块四次处理规则
	 * @return 是否允许 PB 继续完成并扣除输入
	 */
	public static boolean appendRandomCombs(
			RecipeHolder<CentrifugeRecipe> recipe,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CentrifugeBlockEntity entity,
			boolean heated) {
		ItemStack input = invHandler.getStackInSlot(InventoryHandlerHelper.INPUT_SLOT);
		if (!MyriadCreationsEventHandler.isMyriadCreationsItem(input)) return true;
		if (recipe == null) return false;
		int modifier = Math.min(input.getCount(), Math.min(64,
				((CentrifugeBlockEntityAccessor) entity).productivebeesgenesis$getProductivityModifier()));
		int repetitions = heated && input.is(ModTags.Common.STORAGE_BLOCK_HONEYCOMBS)
				&& !recipe.value().ingredient.test(input) ? 4 : 1;
		List<ItemStack> reserved = new ArrayList<>();
		// 按 PB 最大可能副产物预留，避免随机蜜脾抢占蜡等配方产物的空间。
		for (int i = 0; i < repetitions; i++) {
			for (var entry : recipe.value().getRecipeOutputs().entrySet()) {
				if (heated && entry.getKey().is(ModTags.Common.WAXES)) continue;
				int amount = Math.multiplyExact(Math.max(0, entry.getValue().max()), modifier);
				if (amount > 0) reserved.add(entry.getKey().copyWithCount(amount));
			}
		}
		return MyriadCreationsEventHandler.appendRandomCombs(input, invHandler, random, modifier, reserved);
	}
}
