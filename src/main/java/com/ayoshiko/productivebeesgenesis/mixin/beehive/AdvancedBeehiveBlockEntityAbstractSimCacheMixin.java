package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
	 * 高级蜂箱 isSim() 每 tick 缓存 Mixin。
	 * <p>
	 * {@code AdvancedBeehiveBlockEntityAbstract.tickBees()} 会对每只存活的蜜蜂判断
	 * {@code blockEntity instanceof AdvancedBeehiveBlockEntity && advancedBeehive.isSim()}。
	 * 在蜜蜂数量多或安装模拟升级的高级蜂箱密集场景下，isSim() 内部会多次读取升级栏与配置，
	 * 成为热点。
	 * <p>
	 * 本 Mixin 在目标抽象类中注入两个字段，直接以方块实体自身存储每 tick 的缓存结果，
	 * 避免 WeakHashMap / synchronizedMap 的查找与同步开销。
	 */
@Mixin(AdvancedBeehiveBlockEntityAbstract.class)
public abstract class AdvancedBeehiveBlockEntityAbstractSimCacheMixin {

	/** 上次缓存 isSim() 结果时的游戏刻，-1L 表示未缓存 */
	@Unique
	private long productivebeesgenesis$isSimCacheTick;

	/** 对应 tick 的 isSim() 缓存值 */
	@Unique
	private boolean productivebeesgenesis$isSimCacheValue;

	@Unique
	private boolean productivebeesgenesis$isSimCacheInitialized;

	@Unique
	private void productivebeesgenesis$ensureSimCacheState() {
		if (!productivebeesgenesis$isSimCacheInitialized) {
			productivebeesgenesis$isSimCacheTick = -1L;
			productivebeesgenesis$isSimCacheValue = false;
			productivebeesgenesis$isSimCacheInitialized = true;
		}
	}

	@WrapOperation(
			method = "tickBees(Lnet/minecraft/server/level/ServerLevel;"
					+ "Lnet/minecraft/core/BlockPos;"
					+ "Lnet/minecraft/world/level/block/state/BlockState;"
					+ "Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;)V",
			at = @At(value = "INVOKE",
					target = "Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntity;isSim()Z",
					remap = false),
			require = 0
	)
	private static boolean productivebeesgenesis$redirectIsSim(AdvancedBeehiveBlockEntity blockEntity,
		Operation<Boolean> original) {
		Level level = blockEntity.getLevel();
		long gameTime = level != null ? level.getGameTime() : -1L;
		AdvancedBeehiveBlockEntityAbstractSimCacheMixin mixin =
				(AdvancedBeehiveBlockEntityAbstractSimCacheMixin) (Object) blockEntity;
		mixin.productivebeesgenesis$ensureSimCacheState();
		if (gameTime != mixin.productivebeesgenesis$isSimCacheTick) {
			mixin.productivebeesgenesis$isSimCacheTick = gameTime;
			mixin.productivebeesgenesis$isSimCacheValue = original.call(blockEntity);
		}
		return mixin.productivebeesgenesis$isSimCacheValue;
	}
}
