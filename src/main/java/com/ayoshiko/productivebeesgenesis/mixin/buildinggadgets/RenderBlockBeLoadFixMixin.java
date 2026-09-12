package com.ayoshiko.productivebeesgenesis.mixin.buildinggadgets;

import com.ayoshiko.productivebeesgenesis.ICustomDataPersistable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Building Gadgets 剪切/粘贴方块实体数据修复（仅对本模组机器生效）。
 * <br/>
 * 背景：BG2 的 {@code RenderBlockBE.setRealBlock} 在把占位方块转换为真实方块时，
 * 调用 {@link BlockEntity#loadCustomOnly} 应用剪切时保存的
 * {@code saveWithFullMetadata} 数据。NeoForge 21.1 的 {@code loadCustomOnly}
 * 只调用 {@code loadAdditional}，<b>不会</b>恢复 DataComponents
 * （Mekanism 升级、能量、安全拥有者、侧面配置等）。蜂箱/离心机的蜜蜂与 PB 升级
 * 通过 {@code loadAdditional} 恢复，但 Mekanism 组件数据会丢失，表现为
 * 剪切后机器状态不完整（蜜蜂/升级异常、能量清空）。
 * <p>
 * <b>作用域边界（v1.0.8 收紧）</b>：原实现把 {@code loadCustomOnly} 无条件改写为
 * {@link BlockEntity#loadWithComponents}，等于替<b>所有</b>模组的方块实体改变了
 * BG2 粘贴语义（原 javadoc 自己写着「对任意带 DataComponents 的方块实体同样生效」）。
 * 现在只对本模组机器（实现 {@link ICustomDataPersistable}）改写，其余一律走
 * {@code original.call(...)} 保持 BG2 原语义，不再越界影响其它模组。
 * <p>
 * 同时把 {@code @Redirect} 改为 {@code @WrapOperation}：{@code @Redirect} 对同一调用点
 * 是独占的，会与其它同样修改该调用的模组 mixin 直接冲突；{@code WrapOperation} 可共存。
 *
 * @since 2.0.6
 */
@Mixin(targets = "com.direwolf20.buildinggadgets2.common.blockentities.RenderBlockBE", remap = false)
public abstract class RenderBlockBeLoadFixMixin {

	private RenderBlockBeLoadFixMixin() {
	}

	/**
	 * 仅当被粘贴的方块实体是本模组机器时，用 {@code loadWithComponents} 替代
	 * {@code loadCustomOnly}（确保 DataComponents 与自定义 NBT 都恢复）；
	 * 其它模组的方块实体保持 BG2 原行为。
	 */
	@WrapOperation(
			method = "setRealBlock",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadCustomOnly("
							+ "Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
					remap = false
			),
			remap = false,
			require = 1
	)
	private static void productivebeesgenesis$loadWithComponents(BlockEntity blockEntity,
			CompoundTag tag, HolderLookup.Provider provider, Operation<Void> original) {
		if (blockEntity instanceof ICustomDataPersistable) {
			blockEntity.loadWithComponents(tag, provider);
			return;
		}
		original.call(blockEntity, tag, provider);
	}
}
