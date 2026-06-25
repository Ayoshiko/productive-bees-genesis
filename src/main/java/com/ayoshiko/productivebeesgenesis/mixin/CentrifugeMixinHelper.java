package com.ayoshiko.productivebeesgenesis.mixin;

import java.util.function.Function;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.CentrifugeBlockEntityAccessor;

import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

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
 */
public final class CentrifugeMixinHelper {

	private CentrifugeMixinHelper() {
	}

	/**
	 * canOperate RETURN 检查：输出满时阻止机器启动
	 * <p>
	 * 用于修复 PB 原版空转耗能问题：canOperate 返回 true 但输出槽已满时，
	 * 机器仍会消耗能量但不产出，本方法拦截此种情况。
	 *
	 * @param cir         回调信息
	 * @param entity      离心机实例（Mixin this 强转）
	 * @param shouldBlock 判断是否应阻止运行的函数（传入 EventHandler::shouldBlockOperation）
	 */
	public static void checkCanOperate(
			CallbackInfoReturnable<Boolean> cir,
			CentrifugeBlockEntity entity,
			Function<IItemHandlerModifiable, Boolean> shouldBlock) {
		if (!cir.getReturnValue()) return;
		if (shouldBlock.apply(entity.inventoryHandler)) {
			cir.setReturnValue(false);
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
		if (shouldBlock.apply(invHandler)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * completeRecipeProcessing TAIL 追加随机蜜脾产出
	 * <p>
	 * 通过函数式参数注入具体的 EventHandler.appendRandomCombs 调用，
	 * 避免 MyriadCreations 和 InfinityCreation 两个体系重复 try-catch 与强转逻辑。
	 * <p>
	 * PB 内部流程：3 参数版 completeRecipeProcessing → 计算 modifier → 调用 5 参数版 → 产出×modifier。
	 * 本方法在 3 参数版 TAIL 注入，通过 accessor 获取 modifier。
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
			ProductiveBeesGenesis.LOGGER.error(errorMessage, e);
		}
	}

	/**
	 * 四参数消费者接口（Java 标准库未提供 QuadConsumer）
	 */
	@FunctionalInterface
	public interface QuadConsumer<T, U, V, W> {
		void accept(T t, U u, V v, W w);
	}
}
