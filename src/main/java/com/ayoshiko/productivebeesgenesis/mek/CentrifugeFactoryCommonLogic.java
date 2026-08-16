package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeData;
import com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeDataHelper;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2FluidPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputPuller;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputPusher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mek.ae2.MekAe2LifecycleHandler;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.InputOutputCompatibilityCache;
import com.ayoshiko.productivebeesgenesis.util.InputValidationCache;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.OneInputCachedRecipe;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.outputs.IOutputHandler;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.upgrade.IUpgradeData;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;

/**
	 * 离心机工厂公共逻辑静态工具类
	 * <br/>
	 * 封装三个工厂（{@link AbstractMekCentrifugeFactory}、
	 * {@link TileEntityExtraMekCentrifugeFactory}、
	 * {@link TileEntityEMExtraMekCentrifugeFactory}）的公共方法实现，
	 * 消除因 Java 单继承限制导致的代码重复。
	 * <p>
	 * 与 {@link MekCentrifugeFactoryHelper} 的分工：Helper 侧重配方查找和 IO 配置，
	 * 本类侧重 NBT 序列化、配置卡数据、AE2 生命周期、容器同步等横切逻辑。
	 *
	 * @author ayoshiko
	 * @since Task 23
	 */
public final class CentrifugeFactoryCommonLogic {

	private CentrifugeFactoryCommonLogic() {
	}

	// ===== 输入校验 =====

	/** 校验输入物品是否有效（同时查找 SMELTING 和 PB 配方），带缓存 */
	public static boolean isItemValidForSlot(@Nullable Level level, @NotNull ItemStack stack,
			@NotNull InputValidationCache cache, @NotNull PbRecipeProcessor pbProcessor,
			@NotNull IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
					SingleItem<ItemStackToItemStackRecipe>> recipeType, boolean allowSmelting) {
		if (level == null) return false;
		// Clear cached validation when the smelting-compat switch changes (O(1) flag compare per probe)
		cache.setSmeltingAllowed(allowSmelting);
		return cache.getResult(level, stack,
				() -> MekCentrifugeFactoryHelper.getInputValidationResult(recipeType, level, stack, pbProcessor,
					allowSmelting)).valid();
	}

	/** 检查输入是否产出指定输出（支持 PB 配方回退），带缓存 */
	public static boolean inputProducesOutput(@Nullable Level level, @NotNull ItemStack fallbackInput,
			@NotNull IInventorySlot outputSlot, @Nullable IInventorySlot secondaryOutputSlot,
			@NotNull InputOutputCompatibilityCache cache, @NotNull PbRecipeProcessor pbProcessor,
			@NotNull BooleanSupplier superChecker) {
		return cache.get(level, fallbackInput, outputSlot.getStack(),
				secondaryOutputSlot == null ? ItemStack.EMPTY : secondaryOutputSlot.getStack(),
				() -> superChecker.getAsBoolean()
						|| MekCentrifugeFactoryHelper.checkPbOutputFallback(pbProcessor, fallbackInput, outputSlot, secondaryOutputSlot));
	}

	/** 查找配方 — PB 配方存在时返回 null，阻止 SMELTING 管线抢占输入 */
	@Nullable
	public static ItemStackToItemStackRecipe getRecipe(
			@NotNull IInputHandler[] inputHandlers, int cacheIndex,
			@NotNull PbRecipeProcessor pbProcessor,
			@NotNull Function<IInputHandler, ItemStackToItemStackRecipe> findFirstRecipe,
			boolean allowSmelting) {
		if (!allowSmelting) {
			return null;
		}
		ItemStack input = (ItemStack) inputHandlers[cacheIndex].getInput();
		if (!input.isEmpty() && pbProcessor.findPbRecipe(input) != null) {
			return null;
		}
		return findFirstRecipe.apply(inputHandlers[cacheIndex]);
	}

	// ===== 配置卡数据 =====

	/** 写入配置卡数据 — 添加 PB 升级数量和 AE2 per-tile 状态 */
	public static void writeSustainedData(@NotNull CompoundTag data,
			@NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate,
			@NotNull Ae2OutputStateHolder ae2StateHolder) {
		PbConfigCardDataHelper.writePbUpgrades(data, pbUpgradeDelegate.getPbUpgradeCounts(),
				PbConfigCardDataHelper.MachineType.CENTRIFUGE);
		ae2StateHolder.savePerTileState(data);
	}

	/** 从配置卡数据读取 — 恢复 AE2 per-tile 状态 */
	public static void readSustainedData(@NotNull CompoundTag data,
			@NotNull Ae2OutputStateHolder ae2StateHolder) {
		ae2StateHolder.loadPerTileState(data);
	}

	/** 设置配置卡数据 — 处理 PB 升级粘贴（含生存模式物品消耗） */
	public static void setConfigurationData(@NotNull CompoundTag data,
			@Nullable Player player, @NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate) {
		PbConfigCardDataHelper.readAndApplyPbUpgrades(data, player,
				pbUpgradeDelegate::installPbUpgrade,
				() -> clearAllPbUpgrades(pbUpgradeDelegate),
				PbConfigCardDataHelper.MachineType.CENTRIFUGE);
	}

	/**
	 * 清空所有已安装 PB 升级 — 供配置卡粘贴前调用
	 * <br/>
	 * 修复物品守恒：使用 removePbUpgrade 直接清空并返还物品栈列表，
	 * 由调用方（PbConfigCardDataHelper.readAndApplyPbUpgrades）注入玩家物品栏或掉落地面。
	 * 不再依赖 extractPbUpgradeByType（输出槽空间不足会截断物品）。
	 *
	 * @return 被清空的 PB 升级物品栈列表
	 */
	@NotNull
	public static java.util.List<ItemStack> clearAllPbUpgrades(@NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate) {
		java.util.List<ItemStack> dropped = new java.util.ArrayList<>();
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (!type.isBuiltin() && pbUpgradeDelegate.getPbUpgradeInstalledCount(type) > 0) {
				dropped.addAll(pbUpgradeDelegate.removePbUpgrade(type, true));
			}
		}
		return dropped;
	}

	// ===== NBT 序列化 =====

	/** 持久化 PB 进度、升级、AE2 状态和多流体槽（Task 6 orphaned NBT 方案 + Task 10 多流体槽） */
	public static void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider,
			@NotNull PbRecipeProcessor pbProcessor, @NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate,
			@NotNull MekAe2LifecycleHandler ae2LifecycleHandler, @NotNull IAe2OutputHostBase factory,
			@Nullable IFluidTankHolder fluidOutputHolder, @NotNull Runnable superSave) {
		superSave.run();
		pbProcessor.saveAdditional(nbt, provider);
		pbUpgradeDelegate.save(nbt, provider);
		ae2LifecycleHandler.saveNodeNBT(factory, nbt);
		factory.productivebeesgenesis$getAe2StateHolder().savePerTileState(nbt);
		// Task 6/10: 多流体槽 NBT 持久化 — orphaned NBT 字段方案
		if (fluidOutputHolder instanceof MultiFluidTankHolder multiHolder) {
			// MULTI 模式:写出当前多流体槽数据
			multiHolder.writeToNBT(nbt, provider);
		} else if (factory instanceof IMultiFluidTankHost host) {
			// SINGLE 模式:若存在孤儿 NBT,写出以持久化
			// 原理:saveAdditional 接收全新 CompoundTag,SINGLE 模式不调用 writeToNBT,
			// 孤儿 NBT 字段确保上次 MULTI 模式的数据不会在第一次保存后永久丢失
			CompoundTag orphanedNbt = host.getOrphanedMultiFluidTanksNbt();
			if (orphanedNbt != null) {
				nbt.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, orphanedNbt);
			}
		}
	}

	/**
	 * 保存自定义数据为 NBT（扳手拆卸持久化，Task 10 多流体槽 + Bug 修复 SINGLE 孤儿 NBT）
	 * <br/>
	 * 修复 HIGH-4: SINGLE 模式孤儿 NBT 也需写出（与 saveAdditional 对称），
	 * 否则扳手拆卸后 SINGLE 模式保留的孤儿多流体槽 NBT 永久丢失。
	 *
	 * @param factory 工厂方块实体（同时实现 IAe2OutputHostBase 和 IMultiFluidTankHost）
	 */
	@NotNull
	public static CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider,
			@NotNull PbRecipeProcessor pbProcessor, @NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate,
			@NotNull Ae2OutputStateHolder ae2StateHolder,
			@Nullable IFluidTankHolder fluidOutputHolder,
			@NotNull Supplier<BlockEntityType<?>> typeSupplier,
			@NotNull IAe2OutputHostBase factory) {
		CompoundTag nbt = new CompoundTag();
		pbProcessor.saveAdditional(nbt, provider);
		pbUpgradeDelegate.save(nbt, provider);
		ae2StateHolder.savePerTileState(nbt);
		// Task 10: MULTI_PER_FLUID 模式下持久化多流体槽内容（扳手拆卸不丢失流体）
		// 修复 HIGH-4: SINGLE 模式孤儿 NBT 也需写出（与 saveAdditional 对称）
		if (fluidOutputHolder instanceof MultiFluidTankHolder multiHolder) {
			multiHolder.writeToNBT(nbt, provider);
		} else if (factory instanceof IMultiFluidTankHost host) {
			CompoundTag orphanedNbt = host.getOrphanedMultiFluidTanksNbt();
			if (orphanedNbt != null) {
				nbt.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, orphanedNbt);
			}
		}
		BlockEntity.addEntityType(nbt, typeSupplier.get());
		return nbt;
	}

	/** 加载 PB 进度、升级、AE2 状态和多流体槽（Task 6 orphaned NBT 检测 + Task 10 多流体槽恢复） */
	public static void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider,
			@NotNull PbRecipeProcessor pbProcessor, @NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate,
			@NotNull MekAe2LifecycleHandler ae2LifecycleHandler, @NotNull IAe2OutputHostBase factory,
			@Nullable IFluidTankHolder fluidOutputHolder, @NotNull Runnable superLoad) {
		superLoad.run();
		pbProcessor.loadAdditional(nbt, provider);
		pbUpgradeDelegate.load(nbt, provider);
		ae2LifecycleHandler.loadNodeNBT(factory, nbt);
		factory.productivebeesgenesis$getAe2StateHolder().loadPerTileState(nbt);
		boolean hasMultiFluidTag = nbt.contains(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS);
		// Task 6/10: 多流体槽 NBT 处理 — orphaned NBT 字段方案
		// 原理:saveAdditional 接收全新 CompoundTag,SINGLE 模式不调用 writeToNBT 会导致数据丢失;
		// orphaned NBT 字段在 BlockEntity 实例中存储,saveAdditional 显式写出,确保持久化
		if (hasMultiFluidTag) {
			if (fluidOutputHolder instanceof MultiFluidTankHolder multiHolder) {
				// MULTI 模式:正常恢复(loadAdditional 时 orphanedMultiFluidTanksNbt 字段是 null,
				// NBT 中的数据可能是正常数据或上次 SINGLE 模式保存的 orphaned NBT,两种情况都通过 readFromNBT 正确恢复)
				multiHolder.readFromNBT(nbt, provider);
			} else if (factory instanceof IMultiFluidTankHost host) {
				// SINGLE 模式:存入孤儿 NBT,不加载到 SINGLE 持有者(数据休眠)
				host.setOrphanedMultiFluidTanksNbt(nbt.getCompound(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS));
				DevLog.warn("fluid_tank", "检测到多流体槽数据,当前为 SINGLE 模式。数据已保留为孤儿 NBT,切换回 MULTI 模式可恢复");
			}
		}
	}

	// ===== 容器同步 =====

	/** 添加容器追踪器 — 含 AE2 输入过滤模式同步（原版工厂专用，比基础追踪器多 1 个 DataSlot） */
	public static void addContainerTrackersWithFilter(@NotNull MekanismContainer container,
			@NotNull PbRecipeProcessor pbProcessor, @NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate,
			@NotNull Ae2OutputStateHolder ae2StateHolder, @NotNull Runnable superTracker) {
		superTracker.run();
		pbProcessor.addContainerTrackers(container);
		pbUpgradeDelegate.addContainerTrackers(container);
		addAe2StateTrackers(container, ae2StateHolder);
		// per-tile AE2 输入过滤模式同步（ordinal：0=DISABLED, 1=WHITELIST, 2=BLACKLIST）
		container.track(SyncableInt.create(
				() -> ae2StateHolder.getOrCreateInputFilter().getFilterMode().ordinal(),
				v -> ae2StateHolder.getOrCreateInputFilter().setFilterMode(Ae2InputFilter.FilterMode.values()[v])));
	}

	/** 添加 AE2 per-tile 状态追踪器（输出/输入开关 + NBT 忽略开关） */
	private static void addAe2StateTrackers(@NotNull MekanismContainer container,
			@NotNull Ae2OutputStateHolder ae2StateHolder) {
		container.track(SyncableBoolean.create(ae2StateHolder::isAeItemOutputEnabled,
			ae2StateHolder::setAeItemOutputEnabled));
		container.track(SyncableBoolean.create(ae2StateHolder::isAeFluidOutputEnabled,
			ae2StateHolder::setAeFluidOutputEnabled));
		container.track(SyncableBoolean.create(ae2StateHolder::isAeItemInputEnabled, ae2StateHolder::setAeItemInputEnabled));
		container.track(SyncableBoolean.create(ae2StateHolder::isAeInputNbtIgnore, ae2StateHolder::setAeInputNbtIgnore));
		container.track(SyncableBoolean.create(ae2StateHolder::isSmeltingCompatEnabled,
			ae2StateHolder::setSmeltingCompatEnabled));
		container.track(SyncableBoolean.create(ae2StateHolder::isCentrifugeDirectAeOutputEnabled,
				ae2StateHolder::setCentrifugeDirectAeOutputEnabled));
	}

	// ===== AE2 生命周期 =====

	/** clearRemoved 回调 — 准备 AE2 节点加载 */
	public static void onClearRemoved(@NotNull MekAe2LifecycleHandler ae2LifecycleHandler,
			@NotNull IAe2OutputHostBase factory, @NotNull Runnable superClear) {
		superClear.run();
		ae2LifecycleHandler.prepareForLoad(factory);
	}

	/** setRemoved 回调 — 销毁 AE2 节点 */
	public static void onSetRemoved(@NotNull MekAe2LifecycleHandler ae2LifecycleHandler,
			@NotNull IAe2OutputHostBase factory, @NotNull Runnable superSetRemoved) {
		superSetRemoved.run();
		ae2LifecycleHandler.destroyForRemoval(factory);
	}

	/** onChunkUnloaded 回调 — 销毁 AE2 节点（区块卸载） */
	public static void onChunkUnloaded(@NotNull MekAe2LifecycleHandler ae2LifecycleHandler,
			@NotNull IAe2OutputHostBase factory, @NotNull Runnable superChunkUnload) {
		superChunkUnload.run();
		ae2LifecycleHandler.destroyForChunkUnload(factory);
	}

	// ===== AE2 开关切换 =====

	/** 切换 per-tile AE2 物品输出开关 */
	public static void toggleAeItemOutput(@NotNull Ae2OutputStateHolder ae2StateHolder, @NotNull Runnable markForSave) {
		ae2StateHolder.setAeItemOutputEnabled(!ae2StateHolder.isAeItemOutputEnabled());
		markForSave.run();
	}

	/** 切换 per-tile AE2 流体输出开关 */
	public static void toggleAeFluidOutput(@NotNull Ae2OutputStateHolder ae2StateHolder, @NotNull Runnable markForSave) {
		ae2StateHolder.setAeFluidOutputEnabled(!ae2StateHolder.isAeFluidOutputEnabled());
		markForSave.run();
	}

	/** 切换 per-tile AE2 输入拉取开关 */
	public static void toggleAeItemInput(@NotNull Ae2OutputStateHolder ae2StateHolder, @NotNull Runnable markForSave) {
		ae2StateHolder.toggleAeItemInputEnabled();
		markForSave.run();
	}

	/** 切换 per-tile AE2 输入 NBT 忽略开关 */
	public static void toggleAeInputNbtIgnore(
		@NotNull Ae2OutputStateHolder ae2StateHolder,
		@NotNull Runnable markForSave
	) {
		ae2StateHolder.toggleAeInputNbtIgnore();
		markForSave.run();
	}

	// ===== onUpdateServer 公共后处理 =====

	/** onUpdateServer 后处理 — 推送输出到 AE2 网络并拉取输入（AE2 未加载时短路） */
	public static void pushAe2OutputsAndPullInputs(@NotNull IAe2OutputHostBase factory, int batchMultiplier) {
		Ae2OutputPusher.pushOutputs(factory);
		// Task 13: 多槽推送 — 内部遍历 host.fluidOutputTankCount() 个槽
		Ae2FluidPusher.pushFluids(factory);
		if (Ae2IntegrationLoader.isAe2Loaded()) {
			if (factory instanceof IAe2InputHost inputHost) {
				Ae2InputPuller.pullInputs(inputHost, batchMultiplier);
			}
		}
	}

	// ===== 升级数据 =====

	/**
	 * 构建升级数据 — 保存离心机工厂完整状态供等级切换时流转
	 * <br/>
	 * Task 5: 传入 fluidOutputHolder 以序列化多流体槽内容,与扳手拆卸/区块存档一致
	 */
	@NotNull
	public static CentrifugeUpgradeData getUpgradeData(
			@NotNull HolderLookup.Provider provider,
			boolean redstone, @NotNull RedstoneControl controlType,
			@NotNull IEnergyContainer energyContainer, int[] progress,
			@NotNull EnergyInventorySlot energySlot,
			@NotNull List<IInventorySlot> inputSlots, @NotNull List<IInventorySlot> outputSlots,
			boolean sorting, @NotNull List<ITileComponent> components,
			@NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate,
			@NotNull Ae2OutputStateHolder ae2StateHolder,
			@Nullable IFluidTankHolder fluidOutputHolder) {
		return CentrifugeUpgradeDataHelper.buildUpgradeData(provider, redstone, controlType,
				energyContainer, progress, energySlot, inputSlots, outputSlots,
				sorting, components, pbUpgradeDelegate, ae2StateHolder, fluidOutputHolder);
	}

	/**
	 * 应用升级数据 — 先委托父类恢复标准字段，再恢复 PB 升级、AE2 per-tile 设置和深拷贝槽位内容
	 * <br/>
	 * Task 5: 传入 fluidOutputHolder 以恢复多流体槽内容;类型不匹配时记录 DevLog.warn 警告
	 * <p>
	 * 模块 3 Bug 2：传递新方块目标槽位列表（inputSlots/outputSlots/energySlot）给 helper，
	 * 由 helper 从升级数据中的深拷贝字段恢复槽位内容。
	 * 原理：旧方块的 getUpgradeData 调用 saveAllItemsForDrop 已清空槽位，
	 * super.parseUpgradeData 通过引用列表读取到空栈，必须从深拷贝字段覆盖恢复。
	 * 向后兼容：深拷贝字段为 null 时跳过（旧升级数据），由 super.parseUpgradeData 引用路径处理。
	 * <p>
	 * 修复 HIGH-7: 包裹 try-catch 防止单点异常导致整体崩溃。
	 * 失败时记录 ERROR 日志并保留 upgradeData 对象引用（toString）便于管理员手动恢复。
	 * 不重抛异常,允许新方块保持部分恢复状态,避免 super.parseUpgradeData 覆盖已恢复的字段。
	 *
	 * @param provider           注册表访问器
	 * @param upgradeData        升级数据
	 * @param pbUpgradeDelegate  PB 升级委托
	 * @param ae2StateHolder     AE2 状态持有者
	 * @param fluidOutputHolder  流体输出槽持有者
	 * @param targetInputSlots   模块 3 Bug 2: 新方块输入槽列表（null 时跳过深拷贝恢复）
	 * @param targetOutputSlots  模块 3 Bug 2: 新方块输出槽列表（null 时跳过深拷贝恢复）
	 * @param targetEnergySlot   模块 3 Bug 2: 新方块能量槽（null 时跳过深拷贝恢复）
	 * @param superParse         父类 parseUpgradeData 调用回调
	 */
	public static void parseUpgradeData(@NotNull HolderLookup.Provider provider,
			@NotNull IUpgradeData upgradeData,
			@NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate,
			@NotNull Ae2OutputStateHolder ae2StateHolder,
			@Nullable IFluidTankHolder fluidOutputHolder,
			@Nullable List<IInventorySlot> targetInputSlots,
			@Nullable List<IInventorySlot> targetOutputSlots,
			@Nullable IInventorySlot targetEnergySlot,
			@NotNull Consumer<IUpgradeData> superParse) {
		try {
			if (upgradeData instanceof CentrifugeUpgradeData data) {
				superParse.accept(upgradeData);
				CentrifugeUpgradeDataHelper.applyUpgradeData(provider, data, pbUpgradeDelegate, ae2StateHolder,
						fluidOutputHolder, targetInputSlots, targetOutputSlots, targetEnergySlot);
			} else {
				superParse.accept(upgradeData);
			}
		} catch (RuntimeException e) {
			// 修复 HIGH-7: 失败时记录 ERROR 日志并保留 upgradeData 对象引用,便于管理员手动恢复
			// 不重抛异常,允许新方块保持部分恢复状态（比 super 覆盖更安全）
			DevLog.error("离心机工厂升级数据应用失败,upgradeData=" + upgradeData + ",新方块可能处于部分恢复状态", e);
		}
	}

	// ===== CachedRecipe 创建 =====

	/** 创建新的缓存配方 — 1:1 复刻三个工厂的 createNewCachedRecipe 逻辑 */
	@NotNull
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static CachedRecipe<ItemStackToItemStackRecipe> createNewCachedRecipe(
			@NotNull ItemStackToItemStackRecipe recipe, int cacheIndex,
			BooleanSupplier[] recheckAllRecipeErrors,
			IInputHandler[] inputHandlers, IOutputHandler[] outputHandlers,
			@NotNull ObjIntConsumer<Set<CachedRecipe.OperationTracker.RecipeError>> errorsChanged,
			@NotNull BooleanSupplier canFunction,
			@NotNull ObjIntConsumer<Boolean> setActiveState,
			@NotNull BooleanSupplier hasCreativeUpgrade,
			@NotNull MachineEnergyContainer<?> energyContainer,
			@NotNull IntSupplier ticksRequired,
			@NotNull Runnable markForSave,
			@NotNull IntSupplier operationsPerTick,
			int[] progress) {
		return OneInputCachedRecipe.itemToItem(
				recipe, recheckAllRecipeErrors[cacheIndex],
				inputHandlers[cacheIndex], outputHandlers[cacheIndex])
				.setErrorsChanged(errors -> errorsChanged.accept((Set<CachedRecipe.OperationTracker.RecipeError>) errors,
					cacheIndex))
				.setCanHolderFunction(canFunction)
				.setActive(active -> setActiveState.accept(active, cacheIndex))
				.setEnergyRequirements(() -> MekExtrasUpgradeSemantics.energyPerTick(
						hasCreativeUpgrade.getAsBoolean(), energyContainer.getEnergyPerTick()), energyContainer)
				.setRequiredTicks(ticksRequired)
				.setOnFinish(markForSave)
				.setBaselineMaxOperations(operationsPerTick)
				.setOperatingTicksChanged(operatingTicks -> progress[cacheIndex] = operatingTicks);
	}

	// ===== PB 上下文计算 =====

	/** 计算每刻操作数 — STACK 升级提供 2^stackUpgrades 倍并行 */
	public static int operationsPerTick(@NotNull TileEntityMekanism tile, int baseTicksRequired) {
		int maxOps = 1;
		int stackUpgrades = MekUpgradeSupport.getStackUpgrades(tile);
		if (stackUpgrades > 0) {
			// 位运算替代 Math.pow：stackUpgrades 最大 16，1 << 16 = 65536 不会溢出
			maxOps = 1 << stackUpgrades;
		}
		int speedAdjustedOps = MekanismUtils.getOperationsPerTick(tile, baseTicksRequired, maxOps);
		return MekExtrasUpgradeSemantics.operationsPerTick(
				MekUpgradeSupport.hasCreativeUpgrade(tile), maxOps, speedAdjustedOps);
	}

	/**
	 * 计算基础刻数。CREATIVE 返回 0；其他情况应用 SPEED/PB 时间倍率。
	 * <br/>
	 * 修复 SPEED 双重应用：timeMultiplier 已包含 SPEED 升级影响（见
	 * {@link MekCentrifugePbUpgradeHandler#getMekSpeedTimeMultiplier}），不再调用
	 * {@link MekanismUtils#getTicks}（其内部也会应用 SPEED 升级），否则 8 级 SPEED 实际加速 100 倍。
	 * 与基础离心机 {@link MekCentrifugeUpgradeOps#calcTicksForBase} 和蜂箱公式对齐，只应用一次 SPEED。
	 */
	public static int getTicksForBase(@NotNull TileEntityMekanism tile,
			int baseTime, @NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate) {
		return MekExtrasUpgradeSemantics.processingTicks(MekUpgradeSupport.hasCreativeUpgrade(tile),
				baseTime, pbUpgradeDelegate.getTimeMultiplier());
	}

	/** 计算生产力修正 — 基于 PB 升级的生产力倍率 */
	public static int productivityModifier(@NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate) {
		return Math.max(1, (int) Math.floor(pbUpgradeDelegate.getProductivityMultiplier()));
	}
}
