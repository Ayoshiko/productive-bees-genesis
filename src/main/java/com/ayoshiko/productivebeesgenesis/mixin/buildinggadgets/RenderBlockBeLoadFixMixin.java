package com.ayoshiko.productivebeesgenesis.mixin.buildinggadgets;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Building Gadgets 剪切/粘贴方块实体数据修复。
 * <br/>
 * 背景：BG2 的 {@code RenderBlockBE.setRealBlock} 在把占位方块转换为真实方块时，
 * 调用 {@link BlockEntity#loadCustomOnly} 应用剪切时保存的
 * {@code saveWithFullMetadata} 数据。NeoForge 21.1 的 {@code loadCustomOnly}
 * 只调用 {@code loadAdditional}，<b>不会</b>恢复 DataComponents
 * （Mekanism 升级、能量、安全拥有者、侧面配置等）。蜂箱/离心机的蜜蜂与 PB 升级
 * 通过 {@code loadAdditional} 恢复，但 Mekanism 组件数据会丢失，表现为
 * 剪切后机器状态不完整（蜜蜂/升级异常、能量清空）。
 * <p>
 * 本 Mixin 将 {@code loadCustomOnly} 调用重定向为
 * {@link BlockEntity#loadWithComponents}，与 {@code loadStatic}/
 * {@code loadWithComponents} 的标准恢复路径保持一致，完整恢复
 * {@code saveWithFullMetadata} 的全部内容（自定义 NBT + DataComponents）。
 * 该修复对任意带 DataComponents 的方块实体同样生效。
 *
 * @since 2.0.6
 */
@Mixin(targets = "com.direwolf20.buildinggadgets2.common.blockentities.RenderBlockBE", remap = false)
public abstract class RenderBlockBeLoadFixMixin {

	private RenderBlockBeLoadFixMixin() {
	}

	/**
	 * 用 {@code loadWithComponents} 替代 {@code loadCustomOnly}，
	 * 确保粘贴时 DataComponents 与自定义 NBT 都恢复。
	 */
	@Redirect(
			method = "setRealBlock",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadCustomOnly(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
					remap = false
			),
			remap = false
	)
	private static void productivebeesgenesis$loadWithComponents(BlockEntity blockEntity,
			CompoundTag tag, HolderLookup.Provider provider) {
		blockEntity.loadWithComponents(tag, provider);
	}
}
