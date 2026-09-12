package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.logistics.EjectItemMapBuilder;
import com.ayoshiko.productivebeesgenesis.logistics.ExternalLogisticsSettings;
import com.ayoshiko.productivebeesgenesis.logistics.FastItemEjector;
import com.ayoshiko.productivebeesgenesis.logistics.IFastEjectHost;
import com.ayoshiko.productivebeesgenesis.logistics.NeighborItemTargets;
import com.ayoshiko.productivebeesgenesis.logistics.OutputWakeNotifier;
import com.ayoshiko.productivebeesgenesis.mek.IMekApiaryTile;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.lib.inventory.HandlerTransitRequest;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 弹出器快速通道 Mixin —— 用自研的高性能物品弹出替换 Mekanism 的 {@code outputItems}。
 * <p>
 * <b>背景：</b>Mekanism 原版弹出器每个输出面每次只送走一种物品，构建弹出清单还是 O(n²) 且每次
 * 分配 + 洗牌；早期版本为此堆了十余个节流配置（弹出延迟、跳过刻数、最小间隔、长冷却、单刻上限、
 * 最大速度模式…）去缓解症状。现在改为一次遍历送完全部产物（见 {@link FastItemEjector}），
 * 那些配置全部删除，行为固定为最大速度，阻塞退避内置自适应。
 * <p>
 * 本 Mixin 做四件事，只对本模组机器生效：
 * <ol>
 *   <li>接管 {@code tickServer → outputItems} 调用：走自研通道；只有目标是 Mekanism 逻辑运输管道
 *       时才回退原版（管道需要 TransitRequest 的颜色/路由协议），并把 tickDelay 压回 1。</li>
 *   <li>不调用原版 {@code outputItems} 时 Mekanism 的 {@code tickDelay} 保持 0，
 *       因此每刻都会尝试弹出，无需再用配置调节延迟。</li>
 *   <li>回退路径上仍把 {@code InventoryUtils.getEjectItemMap} 换成 O(n) 零分配实现。</li>
 *   <li>输出「空→非空」边沿唤醒相邻方块，抵消物流模组的失败退避。</li>
 * </ol>
 * <p>
 * 同时实现 {@link IFastEjectHost}，为「产物直通」提供入口：弹出器天然持有输出面与能力缓存，
 * 配方提交侧只依赖该接口即可把产物直接送进容器（先模拟再放入）。
 * <p>
 * 所有注入点使用 {@code require = 0}：Mekanism 未来重构方法体时最坏只是回到原版弹出行为，
 * 不会导致启动崩溃。
 *
 * @since 2.1.0
 */
@Mixin(value = TileComponentEjector.class, remap = false)
public abstract class TileComponentEjectorFastPathMixin implements IFastEjectHost {

	/** 自研弹出通道（懒创建，仅本模组机器持有） */
	@Unique
	private volatile FastItemEjector productivebeesgenesis$fastEjector;

	/** 输出边沿唤醒器 */
	@Unique
	private volatile OutputWakeNotifier productivebeesgenesis$wakeNotifier;

	/** 回退路径的弹出清单轮转游标 */
	@Unique
	private int productivebeesgenesis$fallbackCursor;

	/**
	 * 判断该弹出器所属方块实体是否为本模组机器。
	 *
	 * @return 命中时返回方块实体，否则返回 null
	 */
	@Unique
	private TileEntityMekanism productivebeesgenesis$ownTile() {
		TileEntityMekanism tile =
				((TileEntityEjectorAccessor) (Object) this).productivebeesgenesis$getTile();
		return (tile instanceof IMekCentrifugeTile || tile instanceof IMekApiaryTile) ? tile : null;
	}

	/** 懒创建弹出通道，并注册侧面配置变更监听（目标方向集合失效）。 */
	@Unique
	private FastItemEjector productivebeesgenesis$ensureFastEjector(TileEntityMekanism tile) {
		FastItemEjector ejector = productivebeesgenesis$fastEjector;
		if (ejector != null) return ejector;
		synchronized (this) {
			ejector = productivebeesgenesis$fastEjector;
			if (ejector == null) {
				ejector = new FastItemEjector(tile);
				TileComponentConfig config = tile instanceof ISideConfiguration sideConfiguration
						? sideConfiguration.getConfig() : null;
				if (config != null) {
					FastItemEjector created = ejector;
					config.addConfigChangeListener(TransmissionType.ITEM,
							ignored -> created.onConfigChanged());
				}
				productivebeesgenesis$fastEjector = ejector;
			}
		}
		return ejector;
	}

	@Override
	public int productivebeesgenesis$pushGeneratedItem(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		TileEntityMekanism tile = productivebeesgenesis$ownTile();
		if (tile == null) return 0;
		Level level = tile.getLevel();
		if (level == null || level.isClientSide) return 0;
		if (!ExternalLogisticsSettings.directContainerOutput(level.getGameTime())) return 0;
		// 全局开关之上再叠加 per-tile 开关（AND）：玩家可按台关闭直通，
		// 让该机器的产物只走输出槽 + 常规弹出（对齐 AE2 的按台输出开关语义）
		if (!productivebeesgenesis$perTileDirectOutput(tile)) return 0;
		ConfigInfo itemConfig = NeighborItemTargets.itemConfig(tile);
		if (itemConfig == null) return 0;
		return productivebeesgenesis$ensureFastEjector(tile)
				.pushDirect((TileComponentEjector) (Object) this, itemConfig, stack);
	}

	/** per-tile 产物直通开关（离心机/蜂箱各自持有，未实现者默认开启）。 */
	@Unique
	private static boolean productivebeesgenesis$perTileDirectOutput(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile centrifuge) {
			return centrifuge.productivebeesgenesis$isDirectContainerOutputEnabled();
		}
		if (tile instanceof IMekApiaryTile apiary) {
			return apiary.productivebeesgenesis$isDirectContainerOutputEnabled();
		}
		return true;
	}

	/**
	 * 输出槽由空变为非空时唤醒相邻物流网络（节流 + 仅上升沿）。
	 */
	@Inject(method = "tickServer", at = @At("HEAD"), require = 0)
	private void productivebeesgenesis$wakeNeighborsOnOutputEdge(CallbackInfo ci) {
		TileEntityMekanism tile = productivebeesgenesis$ownTile();
		if (tile == null) return;
		Level level = tile.getLevel();
		if (level == null || level.isClientSide) return;
		OutputWakeNotifier notifier = productivebeesgenesis$wakeNotifier;
		if (notifier == null) {
			synchronized (this) {
				notifier = productivebeesgenesis$wakeNotifier;
				if (notifier == null) {
					notifier = new OutputWakeNotifier();
					productivebeesgenesis$wakeNotifier = notifier;
				}
			}
		}
		notifier.onOutputStateTick(level, tile.getBlockPos(),
				productivebeesgenesis$hasAnyOutputItem(tile));
	}

	/**
	 * 接管物品弹出：自研通道优先，逻辑运输管道回退原版。
	 */
	@WrapOperation(
			method = "tickServer",
			at = @At(
					value = "INVOKE",
					target = "Lmekanism/common/tile/component/TileComponentEjector;outputItems("
							+ "Lnet/minecraft/core/Direction;Lmekanism/common/tile/component/config/ConfigInfo;)V"
			),
			require = 0
	)
	private void productivebeesgenesis$fastOutputItems(
			TileComponentEjector ejector,
			Direction facing,
			ConfigInfo info,
			Operation<Void> original) {
		TileEntityMekanism tile = productivebeesgenesis$ownTile();
		Level level = tile == null ? null : tile.getLevel();
		if (tile == null || level == null || level.isClientSide) {
			original.call(ejector, facing, info);
			return;
		}
		boolean needsVanilla = productivebeesgenesis$ensureFastEjector(tile).tick(
				tile, ejector, info, level.getGameTime(),
				productivebeesgenesis$outputContentsVersion(tile),
				productivebeesgenesis$hasAnyOutputItem(tile));
		if (needsVanilla) {
			original.call(ejector, facing, info);
			// 原版 outputItems 结尾会把 tickDelay 设成 10（半秒）；压回 1 保持最大速度
			((TileEntityEjectorAccessor) (Object) this).productivebeesgenesis$setTickDelay(1);
		}
	}

	/**
	 * 回退路径：用 O(n) 零分配的轮转实现替换 Mekanism 的 shuffle + indexOf 弹出清单构建。
	 */
	@WrapOperation(
			method = "outputItems",
			at = @At(
					value = "INVOKE",
					target = "Lmekanism/common/util/InventoryUtils;getEjectItemMap("
							+ "Lmekanism/common/lib/inventory/HandlerTransitRequest;Ljava/util/List;)"
							+ "Lmekanism/common/lib/inventory/HandlerTransitRequest;"
			),
			require = 0
	)
	private HandlerTransitRequest productivebeesgenesis$buildEjectMapFast(
			HandlerTransitRequest request,
			List<IInventorySlot> slots,
			Operation<HandlerTransitRequest> original) {
		if (productivebeesgenesis$ownTile() == null) {
			return original.call(request, slots);
		}
		productivebeesgenesis$fallbackCursor =
				EjectItemMapBuilder.build(request, slots, productivebeesgenesis$fallbackCursor);
		return request;
	}

	/** O(1) 读取输出槽物品总数（离心机为增量计数，蜂箱为少量槽遍历）。 */
	@Unique
	private static long productivebeesgenesis$outputItemCount(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile centrifuge) {
			return centrifuge.productivebeesgenesis$outputItemCount();
		}
		if (tile instanceof IMekApiaryTile apiary) {
			return apiary.productivebeesgenesis$outputItemCount();
		}
		return 0L;
	}

	/**
	 * 输出槽是否非空 —— 调用点只关心「有没有」，不需要总数。
	 * <p>
	 * 原调用点用 {@code outputItemCount() > 0}：离心机家族是 O(1) 增量计数（无所谓），
	 * 但蜂箱的 {@code outputItemCount()} 会遍历全部输出槽求和，且本 mixin **每个 tick 调两次**
	 * （唤醒沿 + 弹出前判空），spark 报告里该方法是蜂箱侧自耗最高的方法之一。
	 * 改走 {@code hasOutputItems()}：离心机读维护好的 O(1) 标志位，蜂箱命中首个非空槽即返回。
	 */
	@Unique
	private static boolean productivebeesgenesis$hasAnyOutputItem(TileEntityMekanism tile) {
		if (tile instanceof PbRecipeContext context) {
			return context.productivebeesgenesis$hasOutputItems();
		}
		return productivebeesgenesis$outputItemCount(tile) > 0L;
	}

	/** 输出槽内容版本号（用于阻塞退避与同刻重复调用拦截）。 */
	@Unique
	private static long productivebeesgenesis$outputContentsVersion(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile centrifuge) {
			return centrifuge.productivebeesgenesis$outputContentsVersion();
		}
		if (tile instanceof IMekApiaryTile apiary) {
			return apiary.productivebeesgenesis$outputContentsVersion();
		}
		return -1L;
	}
}
