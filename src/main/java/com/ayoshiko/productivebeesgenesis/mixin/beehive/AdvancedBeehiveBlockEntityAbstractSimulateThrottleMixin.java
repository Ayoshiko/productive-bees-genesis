package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import java.lang.ref.WeakReference;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import cy.jdkdigital.productivebees.common.entity.bee.hive.FarmerBee;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 高级蜂箱 simulateBee() 中农夫/囤积/收集行为查询冷却 Mixin。
 * <p>
 * {@code AdvancedBeehiveBlockEntityAbstract.simulateBee()} 每次被调用时都会：
 * <ul>
 *   <li>农夫蜂：调用 {@link FarmerBee#findHarvestablesNearby(Level, BlockPos, int)} 扫描附近可收获作物；</li>
 *   <li>囤积蜂/收集蜂：调用 {@link ServerLevel#getEntitiesOfClass(Class, AABB)} 扫描附近掉落物。</li>
 * </ul>
 * 在 256x 加速或大量模拟蜂箱场景下，这些查询每 tick 都会执行，成为 CPU 显著开销。
 * <p>
 * 本 Mixin 在每个高级蜂箱实例中直接维护冷却字段与跳过标志，避免 {@link java.util.WeakHashMap}
 * 查找与 synchronized 开销，同时消除 Map 中 BeeData/Occupant 哈希计算带来的间接热点。
 *
 * <h3>线程安全与 ThreadLocal 泄漏修复说明</h3>
 * <p>
 * {@code simulateBee} 是 {@code public static} 方法，{@code @Redirect} 注入方法必须为 static，
 * 且拦截的目标（{@code FarmerBee.findHarvestablesNearby} / {@code ServerLevel.getEntitiesOfClass}）
 * 参数中不含 BlockEntity，因此 redirect 无法通过 this 或参数直接访问实例字段。
 * <p>
 * 旧方案使用 {@code SimulateContext} ThreadLocal 持有 skipFarmer/skipHoarder，配合 HEAD/RETURN
 * 注入设置与清理。问题在于 {@code @Inject(at=RETURN)} 只在正常返回时触发，原方法抛异常时
 * {@code exit()} 不会调用，ThreadLocal 残留上一次的 skipFarmer/skipHoarder 残值，
 * 服务端主线程长期运行下可能导致间歇性逻辑错误。
 * <p>
 * 本方案重构为「实例字段 + WeakReference ThreadLocal」混合方案：
 * <ul>
 *   <li>{@code skipFarmer}/{@code skipHoarder} 改为 {@code @Unique} 实例字段，生命周期与 BlockEntity
 *       一致，不会跨实例污染。即使异常未清理，下次 HEAD 会先重置为 {@code false}，残值不会被
 *       redirect 读到。</li>
 *   <li>{@link ThreadLocal} 只持有 BlockEntity 的 {@link WeakReference}（小对象），用于 redirect
 *       定位当前实例字段。蜂箱被破坏后 BlockEntity 可被 GC 回收，不会发生内存泄漏。</li>
 *   <li>异常返回时 ThreadLocal 残留 WeakReference，但下次 HEAD 会覆盖；WeakReference 的 referent
 *       为 null 后 redirect 走默认不跳过路径，逻辑安全。</li>
 * </ul>
 */
@Mixin(AdvancedBeehiveBlockEntityAbstract.class)
public class AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin {

    /** 上次执行农夫作物扫描时的游戏刻 */
    @Unique
    private long productivebeesgenesis$lastFarmerTick = -1L;

    /** 上次执行囤积/收集掉落物扫描时的游戏刻 */
    @Unique
    private long productivebeesgenesis$lastHoarderTick = -1L;

    /**
     * 当前 simulateBee 调用是否跳过农夫作物扫描。
     * <p>实例字段，生命周期与 BlockEntity 一致，不会跨实例污染。
     */
    @Unique
    private boolean productivebeesgenesis$skipFarmer = false;

    /**
     * 当前 simulateBee 调用是否跳过囤积/收集掉落物扫描。
     * <p>实例字段，生命周期与 BlockEntity 一致，不会跨实例污染。
     */
    @Unique
    private boolean productivebeesgenesis$skipHoarder = false;

    /**
     * 当前 simulateBee 调用对应的 BlockEntity 弱引用。
     * <p>simulateBee 是静态方法，{@code @Redirect} 无法通过参数获取 BlockEntity，
     * 故通过 ThreadLocal 传递。使用 {@link WeakReference} 避免蜂箱被破坏后 BlockEntity 无法 GC。
     */
    @Unique
    private static final ThreadLocal<WeakReference<AdvancedBeehiveBlockEntityAbstract>> productivebeesgenesis$CURRENT_BLOCK_ENTITY = new ThreadLocal<>();

    @Inject(
            method = "simulateBee(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"),
            remap = false
    )
    private static void productivebeesgenesis$onSimulateBeeHead(
            ServerLevel pLevel, BlockPos pPos, BlockState state,
            AdvancedBeehiveBlockEntityAbstract blockEntity, BeehiveBlockEntity.Occupant inhabitant,
            CallbackInfoReturnable<Entity> cir) {
        // 设置 ThreadLocal，供 redirect 定位当前 BlockEntity（WeakReference 避免 GC 泄漏）
        productivebeesgenesis$CURRENT_BLOCK_ENTITY.set(new WeakReference<>(blockEntity));
        if (blockEntity == null) {
            return;
        }
        AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin self =
                (AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin) (Object) blockEntity;
        // 先重置实例字段，避免上次异常未清理时的残值污染本次判断
        self.productivebeesgenesis$skipFarmer = false;
        self.productivebeesgenesis$skipHoarder = false;
        int cooldown = ModConfig.SERVER.advancedBeehiveSimulateCooldown.get();
        if (cooldown <= 0) {
            return;
        }
        long gameTime = pLevel.getGameTime();
        if (gameTime - self.productivebeesgenesis$lastFarmerTick < cooldown) {
            self.productivebeesgenesis$skipFarmer = true;
        } else {
            self.productivebeesgenesis$lastFarmerTick = gameTime;
        }
        if (gameTime - self.productivebeesgenesis$lastHoarderTick < cooldown) {
            self.productivebeesgenesis$skipHoarder = true;
        } else {
            self.productivebeesgenesis$lastHoarderTick = gameTime;
        }
    }

    @Inject(
            method = "simulateBee(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant;)Lnet/minecraft/world/entity/Entity;",
            at = @At(value = "RETURN"),
            remap = false
    )
    private static void productivebeesgenesis$onSimulateBeeReturn(CallbackInfoReturnable<Entity> cir) {
        // 正常返回时清理 ThreadLocal；异常返回时由下次 HEAD 覆盖，WeakReference 保证可 GC
        productivebeesgenesis$CURRENT_BLOCK_ENTITY.remove();
    }

    @Redirect(
            method = "simulateBee(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant;)Lnet/minecraft/world/entity/Entity;",
            at = @At(value = "INVOKE", target = "Lcy/jdkdigital/productivebees/common/entity/bee/hive/FarmerBee;findHarvestablesNearby(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;I)Ljava/util/List;"),
            remap = false
    )
    private static List<BlockPos> productivebeesgenesis$redirectFindHarvestablesNearby(Level level, BlockPos pos, int range) {
        AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin self = productivebeesgenesis$getCurrentSelf();
        if (self != null && self.productivebeesgenesis$skipFarmer) {
            return List.of();
        }
        return FarmerBee.findHarvestablesNearby(level, pos, range);
    }

    @SuppressWarnings("unchecked")
    @Redirect(
            method = "simulateBee(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant;)Lnet/minecraft/world/entity/Entity;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"),
            remap = true
    )
    private static <T extends Entity> List<T> productivebeesgenesis$redirectGetEntitiesOfClass(ServerLevel level, Class<T> clazz, AABB aabb) {
        AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin self = productivebeesgenesis$getCurrentSelf();
        if (self != null && self.productivebeesgenesis$skipHoarder && ItemEntity.class.isAssignableFrom(clazz)) {
            return List.of();
        }
        return level.getEntitiesOfClass(clazz, aabb);
    }

    /**
     * 从 ThreadLocal 获取当前 simulateBee 调用的 BlockEntity，并转换为 Mixin 类型以访问实例字段。
     * <p>返回 null 表示无有效上下文（ThreadLocal 未设置或 WeakReference 已被 GC 回收），
     * 此时 redirect 走默认不跳过路径，逻辑安全。
     */
    @Unique
    private static AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin productivebeesgenesis$getCurrentSelf() {
        WeakReference<AdvancedBeehiveBlockEntityAbstract> ref = productivebeesgenesis$CURRENT_BLOCK_ENTITY.get();
        if (ref == null) {
            return null;
        }
        AdvancedBeehiveBlockEntityAbstract blockEntity = ref.get();
        if (blockEntity == null) {
            return null;
        }
        return (AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin) (Object) blockEntity;
    }
}