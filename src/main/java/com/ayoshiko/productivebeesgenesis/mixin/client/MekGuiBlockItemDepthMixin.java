package com.ayoshiko.productivebeesgenesis.mixin.client;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 修复 MEK GUI 中 JEI 拖拽预览与幽灵槽内 3D 方块物品的深度精度问题。
 * <br/>
 * 根因：MEK {@code GuiMekanism.renderLabels} 会在渲染末尾故意泄漏 {@code maxZOffset}
 * （窗口内容约 750、tooltip/手持物品约 950），此时 {@code renderFakeItem} 内部再
 * 平移 +150，方块实际位于接近远裁剪面的位置，深度缓冲精度不足，导致棱角缺失；
 * 若再叠加深度测试失败，整个方块可能不可见或落在窗口下方。
 * <br/>
 * 修复：仅在 {@code renderFakeItem}（JEI 拖拽预览/幽灵物品）路径中，当检测到
 * 高 Z 偏移且物品为 BlockItem 时，临时将深度函数从 LEQUAL 改为 ALWAYS，让方块
 * 所有可见面都通过深度测试（保留原始 Z 不变，避免越出 GUI 深度可视范围），
 * 渲染完成后在 finally 中恢复原深度函数。
 * <br/>
 * 方块是刚体且 GUI 方块渲染启用了背面剔除，ALWAYS 不会导致面排序错误。
 * 不拦截 {@code renderItem}：MEK 侧边配置等槽位用该路径渲染相邻机器图标，
 * 拦截会破坏这些图标的深度关系。
 * <p>
 * 触发条件：{@code renderFakeItem} + PoseStack z > 100（MEK 泄漏的高 Z）+
 * 物品是 BlockItem。vanilla GUI 的 poseZ 为 0，不受影响。
 * <p>
 * 线程安全：仅客户端渲染线程调用 GL 状态，无并发风险。
 */
@Mixin(GuiGraphics.class)
public class MekGuiBlockItemDepthMixin {

	/**
	 * 包裹 {@code renderFakeItem(ItemStack, int, int, int)} 内部对
	 * {@code renderItem(LivingEntity, Level, ItemStack, int, int, int)} 的调用，
	 * 在 try/finally 中保护 GL 深度函数的设置与恢复。
	 */
	@WrapOperation(
		method = "renderFakeItem(Lnet/minecraft/world/item/ItemStack;III)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V"
		),
		require = 1
	)
	private void productivebeesgenesis$wrapRenderFakeItem(GuiGraphics instance,
			LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed,
			Operation<Void> original) {
		int originalDepthFunc = GL11.GL_LEQUAL;
		boolean depthChanged = false;
		if (stack.getItem() instanceof BlockItem
				&& instance.pose().last().pose().m32() > 100.0F) {
			originalDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
			GL11.glDepthFunc(GL11.GL_ALWAYS);
			depthChanged = true;
		}
		try {
			original.call(instance, entity, level, stack, x, y, seed);
		} finally {
			if (depthChanged) {
				GL11.glDepthFunc(originalDepthFunc);
			}
		}
	}
}
