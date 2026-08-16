package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.capability.IInventoryDirtyDebouncer;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
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
	 * <p>
	 * 高倍加速（如 256×）下每 tick 仍可能产生脏标记，进一步通过可配置的 saveInterval
	 * 限制实际 setChanged 频率：仅当距上次 flush 达到间隔 tick 且存在脏标记时才执行，
	 * 未达间隔时保留脏标记延后到下次满足条件时 flush。
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
	private volatile AtomicLong productivebeesgenesis$dirtyInventoryTick;

	/**
	 * 上次执行 flush（setChanged）的游戏刻。
	 * <p>
	 * 用于配合 {@code saveInterval} 限制实际序列化频率，初始 -1L 保证首次脏标记即可触发。
	 */
	@Unique
	private volatile AtomicLong productivebeesgenesis$lastFlushTick;

	/**
	 * Mixin field initializers are not reliably added to foreign target constructors.
	 * Establish the state on first use so deserialized block entities are safe too.
	 */
	@Unique
	private void productivebeesgenesis$ensureInventoryDebounceState() {
		if (productivebeesgenesis$dirtyInventoryTick != null && productivebeesgenesis$lastFlushTick != null) {
			return;
		}
		synchronized (this) {
			if (productivebeesgenesis$dirtyInventoryTick == null) {
				productivebeesgenesis$dirtyInventoryTick = new AtomicLong(-1L);
			}
			if (productivebeesgenesis$lastFlushTick == null) {
				productivebeesgenesis$lastFlushTick = new AtomicLong(-1L);
			}
		}
	}

	@Override
	public void productivebeesgenesis$markInventoryDirty(long gameTime) {
		productivebeesgenesis$ensureInventoryDebounceState();
		// 同一 tick 内多次写入覆盖相同值即可，无需 CAS 判断
		productivebeesgenesis$dirtyInventoryTick.set(gameTime);
	}

	@Override
	public void productivebeesgenesis$flushInventoryDirty(long gameTime) {
		productivebeesgenesis$ensureInventoryDebounceState();
		// 无脏标记直接返回，未变化的 tick 不产生任何开销
		if (productivebeesgenesis$dirtyInventoryTick.get() == -1L) {
			return;
		}
		// 距上次 flush 未达 saveInterval，保留脏标记延后处理
		int saveInterval = ModConfig.SERVER.advancedBeehiveSaveInterval.get();
		long lastFlush = productivebeesgenesis$lastFlushTick.get();
		if (lastFlush != -1L && (gameTime - lastFlush) < saveInterval) {
			return;
		}
		// 满足间隔且存在脏标记，原子清除脏标记并执行一次 setChanged
		if (productivebeesgenesis$dirtyInventoryTick.getAndSet(-1L) != -1L) {
			productivebeesgenesis$lastFlushTick.set(gameTime);
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
			method = "tick(Lnet/minecraft/world/level/Level;"
					+ "Lnet/minecraft/core/BlockPos;"
					+ "Lnet/minecraft/world/level/block/state/BlockState;"
					+ "Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntity;)V",
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
