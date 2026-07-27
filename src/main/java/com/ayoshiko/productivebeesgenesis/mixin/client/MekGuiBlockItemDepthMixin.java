package com.ayoshiko.productivebeesgenesis.mixin.client;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 修复 MEK GUI 中 JEI 拖拽方块物品棱角不完整的问题
 * <br/>
 * 根因：MEK 的 {@code GuiMekanism.renderLabels} 在末尾故意泄漏 {@code maxZOffset}（约 550）
 * 到 super 方法，以便手持物品和 tooltip 在正确 z 级别渲染。但这个泄漏的 z 偏移会导致
 * JEI 拖拽预览物品（通过 {@code drawTooltips} 调用 {@code renderFakeItem} 渲染）的实际
 * 渲染 z = 550 + 150 = 700，接近正交投影远裁剪面，深度缓冲精度极差，3D 方块面的
 * 深度测试错误，表现为棱角不完整。
 * <br/>
 * 修复：仅在 {@code GuiGraphics.renderFakeItem}（JEI 拖拽预览/幽灵物品）路径中，
 * 当检测到 MEK 泄漏的高 z 偏移时，临时将深度函数从 LEQUAL 改为 ALWAYS，
 * 让方块所有面都通过深度测试（无论深度值如何），渲染完成后恢复原深度函数。
 * <br/>
 * 由于方块物品是刚体（面之间不会交叉），使用 ALWAYS 不会导致面排序错误，
 * {@code renderModelLists} 已按面顺序渲染。
 * <br/>
 * 为什么不拦截 {@code renderItem}：MEK 自身在侧边配置面板等物品槽位使用
 * {@code renderItem} 渲染相邻机器图标，拦截会导致这些图标的深度关系错乱。
 * <p>
 * 触发条件：{@code renderFakeItem} + PoseStack z > 100（MEK 泄漏的 z）+ 物品是 BlockItem。
 * <p>
 * 实现方式：使用 {@link WrapOperation} 包裹 {@code renderFakeItem} 内部对
 * {@code GuiGraphics#renderItem} 的调用，在 try/finally 中执行 GL 深度函数的设置与恢复。
 * 相比 HEAD/TAIL 配对注入，try/finally 保证即使原方法在渲染过程中抛出异常，
 * GL_DEPTH_FUNC 也能在 finally 块中恢复，避免 GL 状态泄漏导致后续 GUI 渲染损坏。
 * <p>
 * 线程安全：仅从客户端渲染线程调用，GL 状态字段为实例字段，无并发风险。
 */
@Mixin(GuiGraphics.class)
public class MekGuiBlockItemDepthMixin {

	@Unique
	private boolean productivebeesgenesis$depthFuncChanged = false;

	@Unique
	private int productivebeesgenesis$originalDepthFunc = GL11.GL_LEQUAL;

	/**
	 * 包裹 {@code renderFakeItem(ItemStack, int, int)} 内部对
	 * {@code renderItem(LivingEntity, Level, ItemStack, int, int, int, int)} 的调用，
	 * 在 try/finally 中保护 GL 深度函数的设置与恢复。
	 * <br/>
	 * 相比 HEAD/TAIL 配对注入（TAIL 非finally语义，原方法异常时 GL 状态泄漏），
	 * try/finally 保证异常路径也能恢复 {@code GL_DEPTH_FUNC}。
	 * <br/>
	 * setup 在 try 块之外：若 setup 自身抛异常则 GL 状态未被修改，无需 restore；
	 * setup 成功后（GL 状态已改、标志已置位），原渲染调用在 try 中执行，
	 * finally 中无条件执行 restore（由 {@code productivebeesgenesis$depthFuncChanged} 标志守卫）。
	 *
	 * @param entity   实体上下文（renderFakeItem 传 null）
	 * @param level    世界上下文（renderFakeItem 传当前客户端世界）
	 * @param stack    待渲染物品栈
	 * @param x        渲染 x 坐标
	 * @param y        渲染 y 坐标
	 * @param seed     渲染种子
	 * @param z        z 偏移
	 * @param original 原始 renderItem 调用
	 */
	@WrapOperation(
		method = "renderFakeItem(Lnet/minecraft/world/item/ItemStack;II)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V"
		),
		require = 0
	)
	private void productivebeesgenesis$wrapRenderFakeItem(LivingEntity entity, Level level,
			ItemStack stack, int x, int y, int seed, int z, Operation<Void> original) {
		productivebeesgenesis$setupDepthAlways(stack);
		try {
			original.call(entity, level, stack, x, y, seed, z);
		} finally {
			productivebeesgenesis$restoreDepthFunc();
		}
	}

	/**
	 * 在渲染前临时将深度函数设置为 ALWAYS。
	 * <br/>
	 * 仅对方块物品（3D 方块才有棱角不完整问题）且当前 PoseStack z 偏移 > 100
	 * （MEK renderLabels 泄漏的 z 约 400-550）时生效；vanilla GUI 的 poseZ 为 0，不受影响。
	 * 保存原 depthFunc 后设置 ALWAYS，并置位标志以供 restore 守卫。
	 *
	 * @param stack 待渲染物品栈
	 */
	@Unique
	private void productivebeesgenesis$setupDepthAlways(ItemStack stack) {
		// 仅对方块物品生效（3D 方块才有棱角不完整问题）
		if (!(stack.getItem() instanceof BlockItem)) return;

		GuiGraphics self = (GuiGraphics) (Object) this;
		float poseZ = self.pose().last().pose().m32();

		// 仅当 z 偏移 > 100 时触发（MEK renderLabels 泄漏的 z 约 400-550）
		// vanilla GUI 的 poseZ 为 0，不受影响
		if (poseZ > 100.0f) {
			productivebeesgenesis$originalDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
			GL11.glDepthFunc(GL11.GL_ALWAYS);
			productivebeesgenesis$depthFuncChanged = true;
		}
	}

	/**
	 * 恢复原深度函数。
	 * <br/>
	 * 由 {@code productivebeesgenesis$depthFuncChanged} 标志守卫：仅当 setup 成功修改过
	 * GL 状态时才恢复，避免对未修改的 GL 状态执行误操作。恢复后清零标志。
	 */
	@Unique
	private void productivebeesgenesis$restoreDepthFunc() {
		if (productivebeesgenesis$depthFuncChanged) {
			GL11.glDepthFunc(productivebeesgenesis$originalDepthFunc);
			productivebeesgenesis$depthFuncChanged = false;
		}
	}
}
