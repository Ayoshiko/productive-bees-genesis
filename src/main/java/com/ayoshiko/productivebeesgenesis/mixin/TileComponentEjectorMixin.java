package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * TileComponentEjector Mixin — 输出槽弹出速度优化
 * <br/>
 * Mekanism原版硬编码tickDelay=10（半秒），导致多种物品输出时弹出缓慢。
 * 此Mixin在outputItems方法末尾注入，对MEK离心机使用配置的更小延迟值。
 * <p>
 * 覆盖范围：通过 {@link IMekCentrifugeTile} 标记接口统一识别以下4种离心机：
 * - TileEntityMekCentrifuge（原版基础离心机）
 * - TileEntityMekCentrifugeFactory（原版工厂离心机）
 * - TileEntityExtraMekCentrifugeFactory（ME扩展工厂离心机）
 * - TileEntityEMExtraMekCentrifugeFactory（EME扩展工厂离心机）
 * 使用标记接口避免对可选模组类的硬依赖，防止ClassNotFoundException。
 * <p>
 * 优化策略（活动/空闲双配置项）：
 * - 输出槽仍有物品时（活动状态）：使用 mekCentrifugeEjectDelayActive（默认1，最大化弹出吞吐）
 * - 输出槽已空时（空闲状态）：使用 mekCentrifugeEjectDelay（默认2，节省服务器资源）
 * <p>
 * 原理：
 * - outputItems()执行完毕后Mekanism会设置tickDelay=TICKS_PER_HALF_SECOND(10)
 * - 此Mixin在outputItems()返回前拦截，通过标记接口判断tile是否为MEK离心机
 * - 如果是MEK离心机，根据输出槽状态动态设置tickDelay
 * - 非MEK离心机保持原版行为（10 tick延迟）
 */
@Mixin(value = TileComponentEjector.class, remap = false)
public class TileComponentEjectorMixin {

    /**
     * 在outputItems方法末尾注入，对MEK离心机使用动态延迟值
     * <br/>
     * 输出槽仍有物品时（活动状态）使用 mekCentrifugeEjectDelayActive 配置（默认1，最大化弹出速度），
     * 输出槽已空时使用 mekCentrifugeEjectDelay 配置（减少无效tick开销）。
     */
    @Inject(method = "outputItems(Lnet/minecraft/core/Direction;Lmekanism/common/tile/component/config/ConfigInfo;)V",
            at = @At("RETURN"),
            remap = false)
    private void productivebeesgenesis$onOutputItemsReturn(Direction facing, ConfigInfo info, CallbackInfo ci) {
        if (((Object) this) instanceof com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor accessor) {
            var tile = accessor.productivebeesgenesis$getTile();
            if (tile == null) return;
            if (tile instanceof IMekCentrifugeTile) {
                int idleDelay = ModConfig.COMMON.mekCentrifugeEjectDelay.get();
                int activeDelay = ModConfig.COMMON.mekCentrifugeEjectDelayActive.get();
                // 约束：活动延迟不应超过空闲延迟，避免 active > idle 的反直觉组合
                activeDelay = Math.min(activeDelay, idleDelay);
                // 输出槽仍有物品时（活动状态）使用active延迟配置，否则使用空闲延迟配置
                int delay = productivebeesgenesis$hasOutputItems(tile) ? activeDelay : idleDelay;
                accessor.productivebeesgenesis$setTickDelay(delay);
            }
        }
    }

    /**
     * 检查离心机输出槽中是否仍有物品待弹出
     * <br/>
     * 只检查OutputInventorySlot类型的槽位，避免输入槽/能量槽有物品时误触发快速弹出。
     */
    @Unique
    private boolean productivebeesgenesis$hasOutputItems(mekanism.common.tile.base.TileEntityMekanism tile) {
        for (IInventorySlot slot : tile.getInventorySlots(null)) {
            if (slot instanceof mekanism.common.inventory.slot.OutputInventorySlot && !slot.getStack().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
