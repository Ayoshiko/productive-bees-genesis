package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.IHasEjectorCooldown;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TileComponentEjector 输出阻塞冷却 Mixin — 降低输出侧阻塞时的无效弹出尝试。
 * <p>
 * 当 TimeWand 加速或目标容器已满时，Mekanism 原版的 outputItems 会每 tick 全量尝试插入，
 * 导致 TransitResponse.isEmpty() 反复失败，TPS 暴跌。此 Mixin 仅对实现 {@link IHasEjectorCooldown}
 * 的 ProductiveBeesGenesis 离心机工厂生效：
 * <ul>
 *   <li>连续多次未弹出物品后，进入可配置冷却期，跳过 outputItems 调用；</li>
 *   <li>冷却结束后会再次尝试，不会导致物品永久卡死；</li>
 *   <li>一旦成功弹出物品，计数器立即清零，恢复正常频率。</li>
 * </ul>
 * 判断“是否弹出”通过比较调用前后 {@link PbRecipeContext} 输出槽物品总数，无需侵入 Mekanism 内部返回值。
 */
@Mixin(value = TileComponentEjector.class, remap = false)
public class TileComponentEjectorCooldownMixin {

    /** 连续未弹出物品次数（Atomic 保证服务端主线程与异步回调的可见性） */
    @Unique
    private final AtomicInteger productivebeesgenesis$consecutiveEmptyEjects = new AtomicInteger(0);

    /** 剩余冷却 tick 数，大于 0 时跳过 outputItems */
    @Unique
    private final AtomicInteger productivebeesgenesis$ejectCooldown = new AtomicInteger(0);

    @Shadow
    private void outputItems(Direction facing, ConfigInfo info) {
    }

    /**
     * 每 tick 开始时递减冷却计数器，使冷却以真实 tick 为单位。
     * <p>
     * 仅对目标工厂生效；非目标方块实体的冷却字段始终为 0，不会产生影响。
     */
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void productivebeesgenesis$decrementCooldownAtTickStart(CallbackInfo ci) {
        TileEntityMekanism tile = ((TileEntityEjectorAccessor) (Object) this).productivebeesgenesis$getTile();
        if (tile instanceof IHasEjectorCooldown && productivebeesgenesis$ejectCooldown.get() > 0) {
            productivebeesgenesis$ejectCooldown.decrementAndGet();
        }
    }

    /**
     * 拦截 tickServer 中对 outputItems 的调用。
     * <p>
     * 非目标方块实体保持原行为；目标方块实体在冷却期内直接跳过 outputItems，
     * 否则执行 outputItems，并根据输出槽物品总量变化更新阻塞计数器。
     */
    @Redirect(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lmekanism/common/tile/component/TileComponentEjector;outputItems(Lnet/minecraft/core/Direction;Lmekanism/common/tile/component/config/ConfigInfo;)V"
            )
    )
    private void productivebeesgenesis$redirectOutputItems(TileComponentEjector ejector, Direction facing, ConfigInfo info) {
        TileEntityMekanism tile = ((TileEntityEjectorAccessor) ejector).productivebeesgenesis$getTile();
        if (!(tile instanceof IHasEjectorCooldown)) {
            outputItems(facing, info);
            return;
        }

        // 冷却计数器已在 tickServer 头部递减；此处直接跳过 outputItems 调用
        if (productivebeesgenesis$ejectCooldown.get() > 0) {
            return;
        }

        long before = productivebeesgenesis$countOutputItems(tile);
        outputItems(facing, info);
        long after = productivebeesgenesis$countOutputItems(tile);

        // 输出槽原本无物品：本轮没有实际工作，不计入失败
        if (before == 0 || after < before) {
            productivebeesgenesis$consecutiveEmptyEjects.set(0);
            return;
        }

        int failures = productivebeesgenesis$consecutiveEmptyEjects.incrementAndGet();
        int threshold = ModConfig.COMMON.mekCentrifugeEjectBlockedThreshold.get();
        if (failures >= threshold) {
            int cooldown = ModConfig.COMMON.mekCentrifugeEjectBlockedCooldown.get();
            if (cooldown > 0) {
                productivebeesgenesis$ejectCooldown.set(cooldown);
            }
            productivebeesgenesis$consecutiveEmptyEjects.set(0);
        }
    }

    /**
     * 统计工厂所有输出槽中的物品总数。
     * <p>
     * 通过 {@link PbRecipeContext} 访问每进程的 主/副/第三输出槽，避免硬依赖 ME/EME 的可选槽位类。
     */
    @Unique
    private static long productivebeesgenesis$countOutputItems(TileEntityMekanism tile) {
        if (!(tile instanceof PbRecipeContext context)) {
            return 0;
        }
        long total = 0;
        int processes = context.processes();
        for (int i = 0; i < processes; i++) {
            total += productivebeesgenesis$stackCount(context.primaryOutputSlot(i));
            total += productivebeesgenesis$stackCount(context.secondaryOutputSlot(i));
            total += productivebeesgenesis$stackCount(context.tertiaryOutputSlot(i));
        }
        return total;
    }

    @Unique
    private static long productivebeesgenesis$stackCount(IInventorySlot slot) {
        if (slot == null) {
            return 0;
        }
        var stack = slot.getStack();
        return stack.isEmpty() ? 0 : stack.getCount();
    }
}
