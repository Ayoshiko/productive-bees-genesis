package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.capability.IInventoryDirtyDebouncer;

import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AdvancedBeehiveBlockEntity 物品栏脏标记去抖 Mixin。
 * <p>
 * 实现 {@link IInventoryDirtyDebouncer}，把每个游戏刻内可能多次触发的 setChanged 合并为一次，
 * 在 {@code AdvancedBeehiveBlockEntity.tick} 的头部和尾部分别尝试 flush，
 * 保证：
 * <ul>
 *   <li>tick 内多次物品变化最多只触发一次 setChanged；</li>
 *   <li>tick 之间产生的新变化最迟在下一个 tick 头被刷新；</li>
 *   <li>不修改其他方块或普通箱子的行为。</li>
 * </ul>
 */
@Mixin(AdvancedBeehiveBlockEntity.class)
public abstract class AdvancedBeehiveInventoryDebounceMixin implements IInventoryDirtyDebouncer {

    /**
     * 记录物品栏变脏时的游戏刻。
     * <p>
     * {@code -1L} 表示无脏标记；其他值表示该游戏刻需要刷新。
     * 使用 AtomicLong 保证多线程（如外部自动化在服务端主线程回调）下的可见性与原子性。
     */
    @Unique
    private final AtomicLong productivebeesgenesis$dirtyInventoryTick = new AtomicLong(-1L);

    @Override
    public void productivebeesgenesis$markInventoryDirty(long gameTime) {
        // 同一 tick 内多次写入覆盖相同值即可，无需 CAS 判断
        productivebeesgenesis$dirtyInventoryTick.set(gameTime);
    }

    @Override
    public void productivebeesgenesis$flushInventoryDirty(long gameTime) {
        long dirtyTick = productivebeesgenesis$dirtyInventoryTick.getAndSet(-1L);
        if (dirtyTick != -1L) {
            // 统一刷新一次 setChanged，触发 NBT 保存与客户端同步
            ((AdvancedBeehiveBlockEntity) (Object) this).setChanged();
        }
    }

    /**
     * tick 头部 flush：处理上一 tick 末尾或本 tick 开始前产生、尚未刷新的脏标记。
     */
    @Inject(
            method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntity;)V",
            at = @At("HEAD")
    )
    private static void productivebeesgenesis$flushDirtyAtHead(
            Level level, BlockPos pos, BlockState state, AdvancedBeehiveBlockEntity blockEntity, CallbackInfo ci) {
        productivebeesgenesis$flushDirty(level, blockEntity);
    }

    /**
     * tick 尾部 flush：处理本 tick 内部产出/交互产生的脏标记。
     */
    @Inject(
            method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntity;)V",
            at = @At("TAIL")
    )
    private static void productivebeesgenesis$flushDirtyAtTail(
            Level level, BlockPos pos, BlockState state, AdvancedBeehiveBlockEntity blockEntity, CallbackInfo ci) {
        productivebeesgenesis$flushDirty(level, blockEntity);
    }

    private static void productivebeesgenesis$flushDirty(Level level, AdvancedBeehiveBlockEntity blockEntity) {
        if (level != null && blockEntity instanceof IInventoryDirtyDebouncer debouncer) {
            debouncer.productivebeesgenesis$flushInventoryDirty(level.getGameTime());
        }
    }
}
