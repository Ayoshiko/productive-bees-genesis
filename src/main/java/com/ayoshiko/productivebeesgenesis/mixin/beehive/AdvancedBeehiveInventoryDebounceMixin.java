package com.ayoshiko.productivebeesgenesis.mixin.beehive;

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
 * 在 {@code AdvancedBeehiveBlockEntity.tick} 尾部统一 flush。
 * 只保留尾部注入点，避免每 tick 两次 Mixin 回调带来的额外开销。
 */
@Mixin(AdvancedBeehiveBlockEntity.class)
public abstract class AdvancedBeehiveInventoryDebounceMixin implements IInventoryDirtyDebouncer {

	/**
	 * 记录物品栏变脏时的游戏刻。
	 * <p>
	 * {@code -1L} 表示无脏标记；其他值表示该游戏刻需要刷新。
	 * 所有读写均在服务端主线程完成，AtomicLong 仅提供可见性与原子性保障。
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
		if (productivebeesgenesis$dirtyInventoryTick.getAndSet(-1L) != -1L) {
			// 统一刷新一次 setChanged，触发 NBT 保存与客户端同步
			((AdvancedBeehiveBlockEntity) (Object) this).setChanged();
		}
	}

	/**
	 * tick 尾部 flush：处理本 tick 内部产出/交互产生的脏标记。
	 * <p>
	 * 去掉头部的注入点，因为尾部 flush 已经能在同一个 tick 内处理变化；
	 * 跨 tick 产生的新变化最多延迟到下一个 tick 尾部刷新，对 NBT/网络同步无实质影响。
	 */
	@Inject(
			method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntity;)V",
			at = @At("TAIL")
	)
	private static void productivebeesgenesis$flushDirtyAtTail(
			Level level, BlockPos pos, BlockState state, AdvancedBeehiveBlockEntity blockEntity, CallbackInfo ci) {
		if (level == null) {
			return;
		}
		// blockEntity 必然实现 IInventoryDirtyDebouncer（本 Mixin 注入），直接接口调用
		((IInventoryDirtyDebouncer) blockEntity).productivebeesgenesis$flushInventoryDirty(level.getGameTime());
	}
}
