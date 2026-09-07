package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.render.MekGuiBlockItemRenderContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mekanism.client.gui.GuiMekanism;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
	 * 修复 MEK GUI 中 JEI 拖拽预览与幽灵槽内 3D 方块物品的深度精度问题。
	 * <br/>
	 * 根因：MEK {@code GuiMekanism.renderLabels} 会在渲染末尾故意泄漏 {@code maxZOffset}
	 * （窗口内容约 750、tooltip/手持物品约 950），此时 {@code renderFakeItem} 内部再
	 * 平移 +150，方块实际位于接近远裁剪面的位置，深度缓冲精度不足，导致棱角缺失；
	 * 若再叠加深度测试失败，整个方块可能不可见或落在窗口下方。
	 * <br/>
	 * 修复：仅在 {@code renderFakeItem}（JEI 拖拽预览/幽灵物品）路径中处理 BlockItem。
	 * 窗口内部沿用 100 单位的临时 Z 压缩，避免方块模型进入远裁剪边界；JEI 在窗口绘制
	 * 完成后从 z=0 开始绘制时，则依据 {@link GuiMekanism#maxZOffset} 抬到最深窗口内容之上。
	 * 两条路径都继续使用原版 LEQUAL 深度关系。
	 * <br/>
	 * 不拦截 {@code renderItem}：MEK 侧边配置等槽位用该路径渲染相邻机器图标，
	 * 拦截会破坏这些图标的深度关系。
	 * <p>
	 * 触发条件：当前屏幕是本模组包下的 {@link GuiMekanism} + {@code renderFakeItem} +
	 * 物品是 BlockItem；根据 PoseStack 是否已处于窗口层级选择压缩或覆盖层抬升。
	 * <p>
	 * 线程安全：仅客户端渲染线程调用 PoseStack，无并发风险。
	 */
@Mixin(GuiGraphics.class)
public class MekGuiBlockItemDepthMixin {

	/** 窗口内部方块物品的 Z 压缩；追加 renderFakeItem 的 +150 后仍比槽背景前进 50。 */
	private static final float Z_COMPRESSION = 100.0F;
	/**
	 * JEI 拖拽预览在窗口绘制完成后从基础 PoseStack（z=0）开始渲染。
	 * Mekanism 在 maxZOffset 之后为 tooltip/光标预留 200 个单位，目标层再留 200
	 * 个单位，确保预览高于最深窗口子控件及其物品图标。
	 */
	private static final float OVERLAY_Z_MARGIN = 400.0F;

	/**
	 * JEI 在屏幕绘制完成后调用 {@code renderFakeItem}，此时窗口已经写入深度缓冲。
	 * 方块模型是立体几何，单纯提高 Z 仍可能被已有深度值拒绝，因此对这条专用路径
	 * 暂时关闭深度测试。使用线程局部状态，保证异常或嵌套调用不会误恢复其他渲染路径。
	 */
	private static final ThreadLocal<Boolean> DEPTH_OVERRIDE = ThreadLocal.withInitial(() -> false);

	@Inject(method = "renderFakeItem(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"))
	private void productivebeesgenesis$beginFakeItemOverlay(ItemStack stack, int x, int y, int seed,
			CallbackInfo callbackInfo) {
		boolean ownMekScreen = productivebeesgenesis$isOwnMekScreen();
		if (ownMekScreen && MekGuiBlockItemRenderContext.isJeiDragRender()
				&& stack.getItem() instanceof BlockItem) {
			// JEI 拖拽预览在 MEK 窗口绘制完成后调用，提升到本帧最深窗口之上。
			RenderSystem.disableDepthTest();
			DEPTH_OVERRIDE.set(true);
		}
		if (ownMekScreen && !MekGuiBlockItemRenderContext.isJeiDragRender()
				&& stack.getItem() instanceof BlockItem) {
			// 窗口内部的方块物品保留 MEK 的深度压缩，避免进入远裁剪边界。
			DEPTH_OVERRIDE.set(true);
		}
	}

	@Inject(method = "renderFakeItem(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("RETURN"))
	private void productivebeesgenesis$endFakeItemOverlay(ItemStack stack, int x, int y, int seed,
			CallbackInfo callbackInfo) {
		if (DEPTH_OVERRIDE.get()) {
			DEPTH_OVERRIDE.remove();
			if (MekGuiBlockItemRenderContext.isJeiDragRender()) {
				RenderSystem.enableDepthTest();
			}
		}
	}

	/**
	 * 包裹 {@code renderFakeItem(ItemStack, int, int, int)} 内部对
	 * {@code renderItem(LivingEntity, Level, ItemStack, int, int, int)} 的调用，
	 * 在 try/finally 中保护临时的 PoseStack 平移。
	 */
	@WrapOperation(
		method = "renderFakeItem(Lnet/minecraft/world/item/ItemStack;III)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;renderItem(Lnet/minecraft/world/entity/LivingEntity;"
					+ "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V"
		),
		require = 1
	)
	private void productivebeesgenesis$wrapRenderFakeItem(GuiGraphics instance,
			LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed,
			Operation<Void> original) {
		if (productivebeesgenesis$isOwnMekScreen() && stack.getItem() instanceof BlockItem) {
			PoseStack pose = instance.pose();
			float poseZ = pose.last().pose().m32();
			// 窗口内渲染已经处于 MEK 的层级树中，保留原有深度压缩；
			// JEI 覆盖层从 z=0 开始，按本帧实际最深层级抬到所有窗口内容之上。
			float targetZ = GuiMekanism.maxZOffset + OVERLAY_Z_MARGIN;
			float deltaZ = MekGuiBlockItemRenderContext.isJeiDragRender()
					? targetZ - poseZ
					: (poseZ > 100.0F ? -Z_COMPRESSION : 0.0F);
			if (Math.abs(deltaZ) > 0.001F) {
				pose.pushPose();
				try {
					pose.translate(0.0F, 0.0F, deltaZ);
					original.call(instance, entity, level, stack, x, y, seed);
				} finally {
					pose.popPose();
				}
				return;
			}
		}
		original.call(instance, entity, level, stack, x, y, seed);
	}

	/** 只允许本附属模组注册的机器界面使用深度压缩，避免影响其他模组的 MEK GUI。 */
	private static boolean productivebeesgenesis$isOwnMekScreen() {
		Screen screen = Minecraft.getInstance().screen;
		return screen instanceof GuiMekanism<?>
				&& screen.getClass().getName().startsWith("com.ayoshiko.productivebeesgenesis.");
	}
}
