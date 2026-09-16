package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.mek.IMekApiaryTile;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 本模组机器专用弹出器。
 * <p>
 * 快速路径通过普通继承绑定到本模组主动创建的实例，不向 Mekanism 的全局弹出器类注入字段或方法。
 * 因此 Mekanism 原版机器和任意附属机器仍使用其原始 {@link TileComponentEjector} 实现。
 */
public final class GenesisTileComponentEjector extends TileComponentEjector implements IFastEjectHost {

	private final TileEntityMekanism tile;
	private final FastItemEjector itemEjector;
	private final FastFluidEjector fluidEjector;
	private final OutputWakeNotifier wakeNotifier = new OutputWakeNotifier();

	@Nullable
	private ConfigInfo itemConfig;
	@Nullable
	private ConfigInfo fluidConfig;
	private boolean itemListenerRegistered;
	private boolean fluidListenerRegistered;
	private boolean vanillaTickRequired;

	private GenesisTileComponentEjector(TileEntityMekanism tile, LongSupplier chemicalEjectRate,
			IntSupplier fluidEjectRate) {
		super(tile, chemicalEjectRate, fluidEjectRate);
		this.tile = tile;
		this.itemEjector = new FastItemEjector(tile);
		this.fluidEjector = new FastFluidEjector(tile);
	}

	/**
	 * 替换父类构造期创建的原版弹出器，并从组件列表移除旧实例。
	 */
	public static GenesisTileComponentEjector replace(TileEntityMekanism tile,
			@Nullable TileComponentEjector previous, LongSupplier chemicalEjectRate,
			IntSupplier fluidEjectRate) {
		GenesisTileComponentEjector replacement =
				new GenesisTileComponentEjector(tile, chemicalEjectRate, fluidEjectRate);
		if (previous != null && previous != replacement) {
			tile.getComponents().remove(previous);
		}
		return replacement;
	}

	@Override
	public GenesisTileComponentEjector setOutputData(TileComponentConfig configComponent,
			TransmissionType... transmissions) {
		super.setOutputData(configComponent, transmissions);
		for (TransmissionType transmission : transmissions) {
			if (transmission == TransmissionType.ITEM) {
				itemConfig = configComponent.getConfig(TransmissionType.ITEM);
				if (!itemListenerRegistered) {
					configComponent.addConfigChangeListener(TransmissionType.ITEM,
							ignored -> itemEjector.onConfigChanged());
					itemListenerRegistered = true;
				}
			} else if (transmission == TransmissionType.FLUID) {
				fluidConfig = configComponent.getConfig(TransmissionType.FLUID);
				if (!fluidListenerRegistered) {
					configComponent.addConfigChangeListener(TransmissionType.FLUID,
							ignored -> fluidEjector.onConfigChanged());
					fluidListenerRegistered = true;
				}
			} else {
				// 防御未来误用：未知传输类型完整回退原版组件行为，不做半套快速接管。
				vanillaTickRequired = true;
			}
		}
		return this;
	}

	@Override
	public void tickServer() {
		if (vanillaTickRequired) {
			super.tickServer();
			return;
		}
		Level level = tile.getLevel();
		if (level == null || level.isClientSide) return;

		boolean hasOutput = hasAnyOutputItem(tile);
		wakeNotifier.onOutputStateTick(level, tile.getBlockPos(), hasOutput);
		long gameTime = level.getGameTime();
		itemEjector.tick(tile, this, itemConfig, gameTime, outputContentsVersion(tile), hasOutput);
		if (fluidConfig != null && isEjecting(fluidConfig, TransmissionType.FLUID)) {
			fluidEjector.tick(tile, fluidConfig, gameTime);
		}
	}

	@Override
	public int productivebeesgenesis$pushGeneratedItem(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		Level level = tile.getLevel();
		if (level == null || level.isClientSide
				|| !ExternalLogisticsSettings.directContainerOutput(level.getGameTime())
				|| !perTileDirectOutput(tile)) {
			return 0;
		}
		return itemEjector.pushDirect(this, itemConfig, stack);
	}

	private static boolean perTileDirectOutput(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile centrifuge) {
			return centrifuge.productivebeesgenesis$isDirectContainerOutputEnabled();
		}
		if (tile instanceof IMekApiaryTile apiary) {
			return apiary.productivebeesgenesis$isDirectContainerOutputEnabled();
		}
		return false;
	}

	private static boolean hasAnyOutputItem(TileEntityMekanism tile) {
		if (tile instanceof PbRecipeContext context) {
			return context.productivebeesgenesis$hasOutputItems();
		}
		if (tile instanceof IMekCentrifugeTile centrifuge) {
			return centrifuge.productivebeesgenesis$outputItemCount() > 0L;
		}
		if (tile instanceof IMekApiaryTile apiary) {
			return apiary.productivebeesgenesis$outputItemCount() > 0L;
		}
		return false;
	}

	private static long outputContentsVersion(TileEntityMekanism tile) {
		if (tile instanceof IMekCentrifugeTile centrifuge) {
			return centrifuge.productivebeesgenesis$outputContentsVersion();
		}
		if (tile instanceof IMekApiaryTile apiary) {
			return apiary.productivebeesgenesis$outputContentsVersion();
		}
		return -1L;
	}
}
