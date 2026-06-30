package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import java.util.List;
import java.util.Map;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.beehive.SimulateContext;

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
 * 本 Mixin 改为在每个高级蜂箱实例中直接维护冷却字段，避免 {@link java.util.WeakHashMap}
 * 查找与 synchronized 开销，同时消除 Map 中 BeeData/Occupant 哈希计算带来的间接热点。
 */
@Mixin(AdvancedBeehiveBlockEntityAbstract.class)
public class AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin {

    /** 上次执行农夫作物扫描时的游戏刻 */
    @Unique
    private long productivebeesgenesis$lastFarmerTick = -1L;

    /** 上次执行囤积/收集掉落物扫描时的游戏刻 */
    @Unique
    private long productivebeesgenesis$lastHoarderTick = -1L;

    @Inject(
            method = "simulateBee(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"),
            remap = false
    )
    private static void productivebeesgenesis$onSimulateBeeHead(
            ServerLevel pLevel, BlockPos pPos, BlockState state,
            AdvancedBeehiveBlockEntityAbstract blockEntity, BeehiveBlockEntity.Occupant inhabitant,
            CallbackInfoReturnable<Entity> cir) {
        int cooldown = ModConfig.SERVER.advancedBeehiveSimulateCooldown.get();
        SimulateContext ctx = SimulateContext.enter(cooldown);
        if (cooldown <= 0 || blockEntity == null) {
            return;
        }
        AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin self =
                (AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin) (Object) blockEntity;
        long gameTime = pLevel.getGameTime();
        if (gameTime - self.productivebeesgenesis$lastFarmerTick < cooldown) {
            ctx.skipFarmer = true;
        } else {
            self.productivebeesgenesis$lastFarmerTick = gameTime;
        }
        if (gameTime - self.productivebeesgenesis$lastHoarderTick < cooldown) {
            ctx.skipHoarder = true;
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
        SimulateContext.exit();
    }

    @Redirect(
            method = "simulateBee(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant;)Lnet/minecraft/world/entity/Entity;",
            at = @At(value = "INVOKE", target = "Lcy/jdkdigital/productivebees/common/entity/bee/hive/FarmerBee;findHarvestablesNearby(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;I)Ljava/util/List;"),
            remap = false
    )
    private static List<BlockPos> productivebeesgenesis$redirectFindHarvestablesNearby(Level level, BlockPos pos, int range) {
        SimulateContext ctx = SimulateContext.get();
        if (ctx != null && ctx.skipFarmer) {
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
        SimulateContext ctx = SimulateContext.get();
        if (ctx != null && ctx.skipHoarder && ItemEntity.class.isAssignableFrom(clazz)) {
            return List.of();
        }
        return level.getEntitiesOfClass(clazz, aabb);
    }
}
