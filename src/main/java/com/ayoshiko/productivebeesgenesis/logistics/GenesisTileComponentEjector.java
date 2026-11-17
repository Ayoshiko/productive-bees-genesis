package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
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
	private final OutputCustody custody;
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
		this.custody = new OutputCustody(tile::setChanged);
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
        recoverLocalPositions();
        recoverKnownExternalRemainder();
		if (custody.externalOutputBlocked()) return;
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

	public OutputCustody outputCustody() { return custody; }

    private void recoverLocalPositions() {
        for (var entry : custody.localRecords().entrySet()) {
            if (entry.getValue().sequence() == null) continue;
            try {
                recoverPosition(entry.getKey(), entry.getValue());
            } catch (RuntimeException | LinkageError error) {
                LogThrottle.error("local_output_recovery_position", "本地输出恢复位置查询失败，位置={}，槽罐编号={}",
                        tile.getBlockPos(), entry.getKey(), error);
            }
        }
    }

    private void recoverPosition(int key, OutputCustody record) {
        if (key >= 0) {
            IInventorySlot slot;
            if (tile instanceof TileEntityMekApiary apiary) {
                slot = apiary.getOutputSlots().get(key);
            } else if (tile instanceof PbRecipeContext context) {
                int lane = key / 3;
                int index = key % 3;
                slot = switch (index) {
                    case 0 -> context.primaryOutputSlot(lane);
                    case 1 -> context.secondaryOutputSlot(lane);
                    default -> context.tertiaryOutputSlot(lane);
                };
            } else {
                return;
            }
            if (slot != null) record.recoverLocalItem(slot::getStack, stack -> ItemPushHelper.accepted(stack,
                    slot.insertItem(stack.copy(), mekanism.api.Action.EXECUTE, mekanism.api.AutomationType.INTERNAL)));
        } else {
            IExtendedFluidTank tank;
            if (tile instanceof TileEntityMekApiary apiary) {
                if (key != -1) throw new IllegalStateException("Apiary has no recorded local tank");
                tank = apiary.getFluidTank();
            } else if (tile instanceof PbRecipeContext context) {
                tank = context.fluidOutputTank(-key - 1);
            } else {
                return;
            }
            if (tank != null) record.recoverLocalFluid(tank::getFluid, stack -> stack.getAmount()
                    - tank.insert(stack.copy(), mekanism.api.Action.EXECUTE, mekanism.api.AutomationType.INTERNAL).getAmount());
        }
    }

    private void recoverKnownExternalRemainder() {
        if (custody.recoveredItem().isEmpty() && custody.recoveredFluid().isEmpty()) return;
        if (tile instanceof TileEntityMekApiary apiary) {
            for (int slot = 0; slot < apiary.getOutputSlots().size() && !custody.recoveredItem().isEmpty(); slot++) {
                if (!custody.localItemBlocked(slot)) recoverKnownAtPosition(slot);
            }
            if (!custody.localFluidBlocked(0) && !custody.recoveredFluid().isEmpty()) recoverKnownAtPosition(-1);
        } else if (tile instanceof PbRecipeContext context) {
            for (int lane = 0; lane < context.processes() && !custody.recoveredItem().isEmpty(); lane++) {
                for (int index = 0; index < 3 && !custody.recoveredItem().isEmpty(); index++) {
                    int physicalSlot = lane * 3 + index;
                    if (!custody.localItemBlocked(physicalSlot)) recoverKnownAtPosition(physicalSlot);
                }
            }
            for (int tank = 0; tank < context.fluidOutputTankCount() && !custody.recoveredFluid().isEmpty(); tank++) {
                if (!custody.localFluidBlocked(tank)) recoverKnownAtPosition(-tank - 1);
            }
        }
    }

    private void recoverKnownAtPosition(int key) {
        try {
            if (key >= 0) {
                IInventorySlot slot;
                if (tile instanceof TileEntityMekApiary apiary) slot = apiary.getOutputSlots().get(key);
                else if (tile instanceof PbRecipeContext context) {
                    int lane = key / 3;
                    slot = switch (key % 3) {
                        case 0 -> context.primaryOutputSlot(lane);
                        case 1 -> context.secondaryOutputSlot(lane);
                        default -> context.tertiaryOutputSlot(lane);
                    };
                } else return;
                if (slot == null) return;
                ItemStack before = slot.getStack();
                custody.recoverItem(stack -> {
                    try {
                        return (int) custody.localItem(key).handoffLocalItem(stack, stack.getCount(), before,
                                "local-recovery-slot:" + key, slot::getStack, () -> ItemPushHelper.accepted(stack,
                                        slot.insertItem(stack.copy(), mekanism.api.Action.EXECUTE, mekanism.api.AutomationType.INTERNAL)));
                    } catch (RuntimeException | LinkageError error) {
                        LogThrottle.error("local_output_recovery_prepare", "本地输出回填准备失败，位置={}，槽位={}", tile.getBlockPos(), key, error);
                        return 0;
                    }
                });
            } else {
                IExtendedFluidTank tank;
                if (tile instanceof TileEntityMekApiary apiary) tank = apiary.getFluidTank();
                else if (tile instanceof PbRecipeContext context) tank = context.fluidOutputTank(-key - 1);
                else return;
                if (tank == null) return;
                var before = tank.getFluid();
                custody.recoverFluid(stack -> {
                    try {
                        return (int) custody.localFluid(-key - 1).handoffLocalFluid(stack, stack.getAmount(), before,
                                "local-recovery-tank:" + (-key - 1), tank::getFluid, () -> stack.getAmount()
                                        - tank.insert(stack.copy(), mekanism.api.Action.EXECUTE, mekanism.api.AutomationType.INTERNAL).getAmount());
                    } catch (RuntimeException | LinkageError error) {
                        LogThrottle.error("local_output_recovery_prepare", "本地输出回填准备失败，位置={}，罐位={}", tile.getBlockPos(), -key - 1, error);
                        return 0;
                    }
                });
            }
        } catch (RuntimeException | LinkageError error) {
            LogThrottle.error("local_output_recovery_query", "本地输出回填查询失败，位置={}，槽罐编号={}", tile.getBlockPos(), key, error);
        }
    }

	@Override
	public net.minecraft.nbt.CompoundTag serialize(net.minecraft.core.HolderLookup.Provider provider) {
		var tag = super.serialize(provider);
		tag.put("output_custody", custody.save(provider));
		return tag;
	}

	@Override
	public void deserialize(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
		super.deserialize(tag, provider);
		custody.loadRaw(provider, tag.get("output_custody"));
	}

	@Override
	public int productivebeesgenesis$pushGeneratedItem(ItemStack stack) {
		return productivebeesgenesis$pushGeneratedItem(stack, stack.getCount());
	}

	@Override
	public int productivebeesgenesis$pushGeneratedItem(ItemStack stack, int amount) {
		if (stack.isEmpty() || amount <= 0) return 0;
		Level level = tile.getLevel();
		if (level == null || level.isClientSide
				|| !ExternalLogisticsSettings.directContainerOutput(level.getGameTime())
				|| !perTileDirectOutput(tile)) {
			return 0;
		}
		return itemEjector.pushDirect(this, itemConfig, stack, amount, level.getGameTime());
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
