package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.IMekApiaryTile;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TileComponentEjector Mixin — 输出槽弹出速度优化
 * <br/>
 * Mekanism原版硬编码tickDelay=10（半秒），导致多种物品输出时弹出缓慢。
 * 此Mixin在outputItems方法末尾注入，对MEK离心机和通用机械蜂箱使用配置的更小延迟值。
 * <p>
 * 覆盖范围：
 * <ul>
 *   <li>离心机：通过 {@link IMekCentrifugeTile} 标记接口统一识别4种离心机
 *     <ul>
 *       <li>TileEntityMekCentrifuge（原版基础离心机）</li>
 *       <li>TileEntityMekCentrifugeFactory（原版工厂离心机）</li>
 *       <li>TileEntityExtraMekCentrifugeFactory（ME扩展工厂离心机）</li>
 *       <li>TileEntityEMExtraMekCentrifugeFactory（EME扩展工厂离心机）</li>
 *     </ul>
 *   </li>
 *   <li>蜂箱：通过 {@link IMekApiaryTile} 标记接口统一识别4种蜂箱
 *     <ul>
 *       <li>TileEntityMekApiary（基础蜂箱）</li>
 *       <li>TileEntityMekApiaryFactory（原版工厂蜂箱）</li>
 *       <li>TileEntityExtraMekApiaryFactory（ME扩展工厂蜂箱）</li>
 *       <li>TileEntityEMExtraMekApiaryFactory（EME扩展工厂蜂箱）</li>
 *     </ul>
 *   </li>
 * </ul>
 * 使用标记接口避免对可选模组类的硬依赖，防止 ClassNotFoundException。
 * <p>
 * 优化策略（活动/空闲双配置项，离心机与蜂箱独立配置）：
 * <ul>
 *   <li>离心机：mekCentrifugeEjectDelay / mekCentrifugeEjectDelayActive</li>
 *   <li>蜂箱：apiaryEjectDelay / apiaryEjectDelayActive</li>
 * </ul>
 * 输出槽仍有物品时（活动状态）使用 active 延迟（默认1，最大化弹出吞吐），
 * 输出槽已空时（空闲状态）使用 idle 延迟（减少无效 tick 开销）。
 * <p>
 * 原理：
 * - outputItems()执行完毕后Mekanism会设置tickDelay=TICKS_PER_HALF_SECOND(10)
 * - 此Mixin在outputItems()返回前拦截，通过标记接口判断tile类型
 * - 根据输出槽状态动态设置tickDelay，使用对应类型的独立配置
 * - 非目标机器保持原版行为（10 tick延迟）
 */
@Mixin(value = TileComponentEjector.class, remap = false)
public abstract class TileComponentEjectorMixin {

	/**
	 * 离心机配置不匹配警告标志位（仅首次触发时记录日志，避免每次 outputItems 刷屏）
	 * 使用 AtomicBoolean + CAS 模式，保证多线程下"只警告一次"语义
	 */
	@Unique
	private static final AtomicBoolean productivebeesgenesis$configMismatchWarned = new AtomicBoolean(false);

	/**
	 * 蜂箱配置不匹配警告标志位（独立于离心机，避免互相影响）
	 */
	@Unique
	private static final AtomicBoolean productivebeesgenesis$apiaryConfigMismatchWarned = new AtomicBoolean(false);

	/**
	 * 在outputItems方法末尾注入，对MEK离心机和蜂箱使用动态延迟值
	 * <br/>
	 * 离心机读取 mekCentrifugeEjectDelay/Active 配置，蜂箱读取 apiaryEjectDelay/Active 配置，
	 * 两者独立配置互不影响。输出槽仍有物品时使用 active 延迟，已空时使用 idle 延迟。
	 */
	@Inject(method = "outputItems(Lnet/minecraft/core/Direction;Lmekanism/common/tile/component/config/ConfigInfo;)V",
			at = @At("RETURN"),
			remap = false)
	private void productivebeesgenesis$onOutputItemsReturn(Direction facing, ConfigInfo info, CallbackInfo ci) {
		if (((Object) this) instanceof com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor accessor) {
			var tile = accessor.productivebeesgenesis$getTile();
			if (tile == null) return;
			// 离心机分支：使用离心机独立配置
			if (tile instanceof IMekCentrifugeTile) {
				int idleDelay = ModConfig.SERVER.mekCentrifugeEjectDelay.get();
				int activeDelay = ModConfig.SERVER.mekCentrifugeEjectDelayActive.get();
				activeDelay = productivebeesgenesis$clampActiveDelay(activeDelay, idleDelay,
						productivebeesgenesis$configMismatchWarned, "mekCentrifugeEjectDelay");
				int delay = productivebeesgenesis$hasOutputItems(tile) ? activeDelay : idleDelay;
				accessor.productivebeesgenesis$setTickDelay(delay);
				return;
			}
			// 蜂箱分支：使用蜂箱独立配置
			if (tile instanceof IMekApiaryTile) {
				int idleDelay = ModConfig.SERVER.apiaryEjectDelay.get();
				int activeDelay = ModConfig.SERVER.apiaryEjectDelayActive.get();
				activeDelay = productivebeesgenesis$clampActiveDelay(activeDelay, idleDelay,
						productivebeesgenesis$apiaryConfigMismatchWarned, "apiaryEjectDelay");
				int delay = productivebeesgenesis$hasOutputItems(tile) ? activeDelay : idleDelay;
				accessor.productivebeesgenesis$setTickDelay(delay);
			}
		}
	}

	/**
	 * 约束活动延迟不超过空闲延迟，避免 active > idle 的反直觉组合
	 * <br/>
	 * CAS 模式保证多线程下每种类型只记录一次 warn，避免刷屏日志。
	 *
	 * @param activeDelay 活动延迟
	 * @param idleDelay   空闲延迟
	 * @param warned      类型独立的警告标志位
	 * @param configName  配置名（用于日志）
	 * @return 修正后的活动延迟
	 */
	@Unique
	private static int productivebeesgenesis$clampActiveDelay(int activeDelay, int idleDelay,
			AtomicBoolean warned, String configName) {
		if (activeDelay > idleDelay) {
			if (warned.compareAndSet(false, true)) {
				ProductiveBeesGenesis.LOGGER.warn("{}Active({}) > {}({})，已自动调整为 idleDelay",
						configName, activeDelay, configName, idleDelay);
			}
			return idleDelay;
		}
		return activeDelay;
	}

	/**
	 * 检查输出槽中是否仍有物品待弹出
	 * <br/>
	 * 优先读取由 IContentsListener 维护的标志位（O(1)），避免每次弹出都遍历所有槽位（O(n)）。
	 * 覆盖以下实现路径：
	 * - 基础离心机 {@link TileEntityMekCentrifuge}：直接调用其标志位方法
	 * - 工厂版离心机（三个 Factory 类）：通过 {@link PbRecipeContext} 接口读取标志位
	 * - 蜂箱（基础+工厂版）：通过 {@link PbRecipeContext} 接口读取（蜂箱输出槽少，遍历足够高效）
	 * 其他 Mekanism 机器回退到原 O(n) 遍历逻辑。
	 */
	@Unique
	private boolean productivebeesgenesis$hasOutputItems(mekanism.common.tile.base.TileEntityMekanism tile) {
		// 基础离心机：直接读取标志位（未实现 PbRecipeContext，单独判断）
		if (tile instanceof TileEntityMekCentrifuge mekCentrifuge) {
			return mekCentrifuge.productivebeesgenesis$hasOutputItems();
		}
		// 工厂版：通过 PbRecipeContext 接口读取标志位（三个 Factory 类均实现该接口）
		if (tile instanceof PbRecipeContext context) {
			return context.productivebeesgenesis$hasOutputItems();
		}
		// 其他 Mekanism 机器：回退到原遍历逻辑
		for (IInventorySlot slot : tile.getInventorySlots(null)) {
			if (slot instanceof mekanism.common.inventory.slot.OutputInventorySlot && !slot.getStack().isEmpty()) {
				return true;
			}
		}
		return false;
	}
}
