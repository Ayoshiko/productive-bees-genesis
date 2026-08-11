package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.CentrifugeBlockEntityAccessor;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
	 * 离心机 Mixin 公共逻辑工具类
	 * <br/>
	 * 抽取 6 个离心机 Mixin（Centrifuge / HeatedCentrifuge / PoweredCentrifuge × Myriad/Infinity）
	 * 中重复的以下逻辑：
	 * <ol>
	 *   <li>canOperate RETURN 输出满检查</li>
	 *   <li>canProcessRecipe HEAD 输出满检查</li>
	 *   <li>completeRecipeProcessing TAIL 追加随机蜜脾</li>
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
	 * completeRecipeProcessing TAIL 追加随机蜜脾产出
	 *
	 * @param invHandler   物品处理器
	 * @param random       随机源
	 * @param entity       离心机实例（Mixin this 强转）
	 * @param appendFunc   追加产出函数：(input, invHandler, random, modifier) -> void
	 * @param errorMessage 异常日志消息
	 */
	public static void appendRandomCombs(
			IItemHandlerModifiable invHandler,
			RandomSource random,
			CentrifugeBlockEntity entity,
			QuadConsumer<ItemStack, IItemHandlerModifiable, RandomSource, Integer> appendFunc,
			String errorMessage) {
		try {
			ItemStack input = invHandler.getStackInSlot(InventoryHandlerHelper.INPUT_SLOT);
			int modifier = ((CentrifugeBlockEntityAccessor) entity).productivebeesgenesis$getProductivityModifier();
			appendFunc.accept(input, invHandler, random, modifier);
		} catch (Exception e) {
			// M9: LogThrottle 节流，避免 completeRecipeProcessing TAIL 高频触发刷屏
			LogThrottle.error("centrifuge_append_combs",
					"{} (5秒内仅首条输出): {}", errorMessage, e.toString());
		}
	}

	/** 四参数消费者接口 */
	@FunctionalInterface
	public interface QuadConsumer<T, U, V, W> {
		void accept(T t, U u, V v, W w);
	}
}
