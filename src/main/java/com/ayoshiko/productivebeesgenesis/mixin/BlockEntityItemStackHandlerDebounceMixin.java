package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.capability.IInventoryDirtyDebouncer;

import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BlockEntityItemStackHandler 去抖 Mixin。
 * <p>
 * 仅对 {@link AdvancedBeehiveBlockEntity} 的物品栏生效：
 * 当内容变化时不再立即调用 {@code blockEntity.setChanged()}，
 * 而是把脏标记委托给 {@link IInventoryDirtyDebouncer}，
 * 由 AdvancedBeehiveBlockEntity 的 tick 在 tick 头尾统一 flush。
 * 其他方块（离心机、升级栏等）保持原版行为不变。
 */
@Mixin(InventoryHandlerHelper.BlockEntityItemStackHandler.class)
public class BlockEntityItemStackHandlerDebounceMixin {

    @Shadow
    protected BlockEntity blockEntity;

    /**
     * 拦截内容变化回调，对高级蜂箱进行去抖。
     * <p>
     * 原理：
     * <ol>
     *   <li>仅当所属方块实体是 {@link AdvancedBeehiveBlockEntity} 且已附加到世界时才处理；</li>
     *   <li>调用 {@link IInventoryDirtyDebouncer#productivebeesgenesis$markInventoryDirty(long)}
     *       记录当前游戏刻已变脏；</li>
     *   <li>取消本次 super.onContentsChanged → setChanged 调用链。</li>
     * </ol>
     * 同一 tick 内的多次变化只会覆盖相同的脏标记，从而避免重复的 NBT 序列化与网络同步。
     */
    @Inject(method = "onContentsChanged", at = @At("HEAD"), cancellable = true)
    private void productivebeesgenesis$debounceSetChanged(int slot, CallbackInfo ci) {
        if (this.blockEntity instanceof AdvancedBeehiveBlockEntity
                && this.blockEntity.getLevel() != null) {
            long gameTime = this.blockEntity.getLevel().getGameTime();
            ((IInventoryDirtyDebouncer) this.blockEntity).productivebeesgenesis$markInventoryDirty(gameTime);
            // 取消本次 setChanged，统一在 tick 头尾刷新
            ci.cancel();
        }
    }
}
