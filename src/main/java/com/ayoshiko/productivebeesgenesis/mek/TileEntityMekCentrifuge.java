package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.SerializationConstants;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.prefab.TileEntityElectricMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityElectricMachineAccessor;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.PerformanceMonitor;
import com.ayoshiko.productivebeesgenesis.util.RecipeCacheManager;

/**
 * 基础MEK离心机方块实体
 * <br/>
 * 继承Mekanism的TileEntityElectricMachine，复用完整的能量/侧面配置/升级/GUI体系。
 * 扩展PB离心配方查找：先查PB CentrifugeRecipe，找到则用PB逻辑处理（概率多物品输出+流体），
 * 未找到则回退到Mekanism的SMELTING配方。
 * <p>
 * 额外添加2个副输出槽+FluidTank，支持PB配方的多物品和流体输出。
 * 通过Accessor Mixin访问父类的包私有字段（inputSlot/outputSlot/energySlot/energyContainer）。
 */
public class TileEntityMekCentrifuge extends TileEntityElectricMachine implements IMekCentrifugeTile {

    /** PB离心配方类型 */
    private static final RecipeType<CentrifugeRecipe> CENTRIFUGE_RECIPE_TYPE = ModRecipeTypes.CENTRIFUGE_TYPE.get();

    /** 配方缓存最大条目数 */
    private static final int MAX_RECIPE_CACHE_SIZE = 256;

    /** PB离心配方缓存（实例级LRU，避免静态缓存污染） */
    private final RecipeCacheManager<RecipeHolder<CentrifugeRecipe>> pbRecipeCache =
            new RecipeCacheManager<>(MAX_RECIPE_CACHE_SIZE);

    /** 副输出槽1 — PB配方第2个物品输出 */
    private OutputInventorySlot secondaryOutputSlot;

    /** 副输出槽2 — PB配方第3个物品输出 */
    private OutputInventorySlot tertiaryOutputSlot;

    /** PB离心配方缓存 */
    @Nullable
    private RecipeHolder<CentrifugeRecipe> cachedPbRecipe;

    /** PB配方处理进度（tick） */
    private int pbOperatingTicks;

    /** PB配方是否正在处理 */
    private boolean pbProcessing;

    /** 上一tick是否在处理PB配方 — 用于检测PB停止时恢复SMELTING激活状态 */
    private boolean pbWasProcessing;

    /** PB配方处理总时间（tick） — 同步到客户端用于进度条显示 */
    private int pbProcessingTime;

    /** 流体输出槽 — 接收PB配方的流体输出 */
    private IExtendedFluidTank fluidOutputTank;

    // ===== 256倍加速性能优化字段 =====
    /** 上次检查的输入物品（用于缓存SMELTING配方检查结果，避免每tick重复查询） */
    @Nullable
    private ItemStack lastCheckedInput = ItemStack.EMPTY;

    /** 上次输入是否有SMELTING配方（缓存结果，输入变更时重新计算） */
    private boolean lastHasSmeltingRecipe = false;

    /**
     * 上次缓存时的配方版本号 — 用于检测配方重载（/reload）
     * <br/>
     * 与 {@link ProductiveBeesGenesis#recipeVersion} 比较，不一致时清空 SMELTING 和 PB 配方缓存。
     * 使用 volatile 保证可见性：主线程（重载事件）写入 recipeVersion 后，方块实体线程能立即读到新值。
     */
    private volatile long lastRecipeVersion = -1L;

    /**
     * 缓存的每tick能量消耗 — 每次 tryProcessPbRecipe 调用时刷新
     * <br/>
     * getEnergyPerTick() 内部可能涉及 Math.pow 计算（升级影响能量消耗），
     * 在循环外缓存避免每次操作都重新计算，参考 PbRecipeProcessor 的同名字段。
     */
    private long cachedEnergyPerTick;

    /**
     * 缓存的每tick操作数 — 每次 tryProcessPbRecipe 调用时刷新
     * <br/>
     * getOperationsPerTick() 内部涉及升级计算，在循环外缓存避免重复计算。
     */
    private int cachedOperationsPerTick;

    /**
     * 缓存的 getTicks(BASE_TICKS_REQUIRED) 结果 — 用于万象创世处理时间计算
     * <br/>
     * MekanismUtils.getTicks 内部涉及升级组件遍历与 Math.pow 计算，
     * 在升级未变更时结果稳定，通过时间窗口缓存避免每 tick 重复计算。
     * 升级变更后最多 20 tick（1秒）内自动反映新值。
     */
    private volatile int cachedTicks = -1;

    /** 上次计算 cachedTicks 时的游戏刻（-1 表示未计算） */
    private volatile long cachedTicksAt = -1L;

    /** getTicks 缓存失效间隔（tick） — 升级变更后最多 1 秒内反映新值 */
    private static final int TICKS_CACHE_INTERVAL = 20;

    /** 可复用的输出槽列表（避免每次完成配方都创建新ArrayList） */
    private final List<IInventorySlot> reusableOutputSlots = new ArrayList<>(3);

    /** 输出槽是否有物品（由 IContentsListener 维护，供 EjectorMixin 读取，避免每次弹出遍历槽位） */
    private volatile boolean hasOutputItems = false;

    /** 输出槽是否已满（由 IContentsListener 维护，供 areOutputSlotsFull 读取，避免每次完成配方遍历3个槽） */
    private volatile boolean outputSlotsFull = false;

    public TileEntityMekCentrifuge(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state, BASE_TICKS_REQUIRED);
        // 重写侧面配置：3个输出槽（参考PrecisionSawmill）
        // 注：secondaryOutputSlot/tertiaryOutputSlot 在 super() 期间通过 getInitialInventory()
        // （TileEntityMekanism 构造函数虚方法调用）已赋值，此处 List.of 不会 NPE。
        // 切勿将 setupItemIOConfig 移到 getInitialInventory 之前或重构 super() 调用顺序，
        // 否则 List.of 的 null 检查会立即抛出 NPE。
        configComponent.setupItemIOConfig(
                Collections.singletonList(accessor().productivebeesgenesis$getInputSlot()),
                List.of(accessor().productivebeesgenesis$getOutputSlot(), secondaryOutputSlot, tertiaryOutputSlot),
                accessor().productivebeesgenesis$getEnergySlot(), false);
        configComponent.setupInputConfig(TransmissionType.ENERGY, accessor().productivebeesgenesis$getEnergyContainer());
        // 流体槽作为输出配置（右侧），参考TileEntityNutritionalLiquifier
        configComponent.setupOutputConfig(TransmissionType.FLUID, fluidOutputTank, RelativeSide.RIGHT);

        ejectorComponent = new TileComponentEjector(this);
        // 同时弹出物品和流体
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
    }

    @NotNull
    @Override
    public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
            SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
        return MekanismRecipeType.SMELTING;
    }

    /**
     * JEI配方查看器跳转支持
     * <br/>
     * 返回SMELTING类型，使JEI中点击配方时能正确跳转到熔炼配方类别。
     * 参考Mekanism原版TileEntityEnergizedSmelter的实现。
     */
    @NotNull
    @Override
    public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
        return RecipeViewerRecipeType.SMELTING;
    }

    /**
     * 重写containsRecipe — 同时查找Mekanism SMELTING和PB CentrifugeRecipe
     * <br/>
     * 输入槽通过this::containsRecipe判断物品是否有效。
     * 默认实现只查SMELTING配方缓存，PB蜜脾不在其中会被拒绝。
     * 重写后同时检查PB配方，使PB蜜脾能放入输入槽。
     */
    @Override
    public boolean containsRecipe(@NotNull ItemStack input) {
        if (super.containsRecipe(input)) return true;
        return findPbRecipe(input) != null;
    }

    /**
     * 重写getInitialInventory — 添加2个副输出槽
     * <br/>
     * 父类只有1个输出槽，PB离心配方最多3个物品输出。
     * 重写后添加secondaryOutputSlot和tertiaryOutputSlot。
     * 由于父类字段是包私有的，通过Accessor Mixin设置。
     * <p>
     * 布局：3个输出槽竖排于x=134，y分别为17/35/53，间隔18。
     * <p>
     * 性能优化：3个输出槽使用组合 listener，内容变更时同时触发
     * recipeCacheUnpauseListener 和 {@link #updateOutputSlotFlags}，
     * 维护 hasOutputItems/outputSlotsFull 标志位，避免 areOutputSlotsFull
     * 和 EjectorMixin 每次都遍历槽位。
     */
    @NotNull
    @Override
    protected IInventorySlotHolder getInitialInventory(@NotNull IContentsListener listener,
                                                       @NotNull IContentsListener recipeCacheListener,
                                                       @NotNull IContentsListener recipeCacheUnpauseListener) {
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this);

        // 输入槽 — 与父类相同位置
        InputInventorySlot inputSlot = InputInventorySlot.at(this::containsRecipe, recipeCacheListener, 64, 17);
        builder.addSlot(inputSlot)
                .tracksWarnings(slot -> slot.warning(WarningType.NO_MATCHING_RECIPE, getWarningCheck(RecipeError.NOT_ENOUGH_INPUT)));

        // 输出槽组合 listener：原 recipeCacheUnpauseListener + 标志位更新
        IContentsListener outputListener = () -> {
            recipeCacheUnpauseListener.onContentsChanged();
            updateOutputSlotFlags();
        };

        // 主输出槽 — 竖排第1个（x=134, y=17）
        OutputInventorySlot outputSlot = OutputInventorySlot.at(outputListener, 134, 17);
        builder.addSlot(outputSlot)
                .tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT, getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE)));

        // 副输出槽1 — 竖排第2个（x=134, y=35）
        secondaryOutputSlot = OutputInventorySlot.at(outputListener, 134, 35);
        builder.addSlot(secondaryOutputSlot);

        // 副输出槽2 — 竖排第3个（x=134, y=53）
        tertiaryOutputSlot = OutputInventorySlot.at(outputListener, 134, 53);
        builder.addSlot(tertiaryOutputSlot);

        // 能量槽 — 与父类相同位置
        EnergyInventorySlot energySlot = EnergyInventorySlot.fillOrConvert(
                accessor().productivebeesgenesis$getEnergyContainer(), this::getLevel, listener, 64, 53);
        builder.addSlot(energySlot);

        // 通过Accessor设置父类的包私有字段
        accessor().productivebeesgenesis$setInputSlot(inputSlot);
        accessor().productivebeesgenesis$setOutputSlot(outputSlot);
        accessor().productivebeesgenesis$setEnergySlot(energySlot);

        // 初始化输出槽标志位（基于初始空槽状态）
        updateOutputSlotFlags();

        return builder.build();
    }

    /**
     * 初始化FluidTank — 添加PB流体输出槽
     * <br/>
     * TileEntityRecipeMachine的1参数getInitialFluidTanks是final的，
     * 需要重写3参数版本。TileEntityElectricMachine默认没有FluidTank，重写此方法添加。
     */
    @NotNull
    @Override
    protected IFluidTankHolder getInitialFluidTanks(@NotNull IContentsListener listener,
                                                     @NotNull IContentsListener recipeCacheListener,
                                                     @NotNull IContentsListener recipeCacheUnpauseListener) {
        FluidTankHelper helper = FluidTankHelper.forSideWithConfig(this);
        fluidOutputTank = BasicFluidTank.output(ModConfig.COMMON.mekCentrifugeFluidTankCapacity.get(), listener);
        helper.addTank(fluidOutputTank);
        return helper.build();
    }

    /** 获取Accessor — 用于访问父类包私有字段 */
    private TileEntityElectricMachineAccessor accessor() {
        return (TileEntityElectricMachineAccessor) this;
    }

    /** 获取流体输出槽 — GUI显示用 */
    @NotNull
    public IExtendedFluidTank getFluidOutputTank() {
        return fluidOutputTank;
    }

    /** 获取副输出槽1 — GUI显示用 */
    @NotNull
    public OutputInventorySlot getSecondaryOutputSlot() {
        return secondaryOutputSlot;
    }

    /** 获取副输出槽2 — GUI显示用 */
    @NotNull
    public OutputInventorySlot getTertiaryOutputSlot() {
        return tertiaryOutputSlot;
    }

    /**
     * 重写getScaledProgress — PB配方处理时使用pbOperatingTicks
     * <br/>
     * 父类的getScaledProgress()使用operatingTicks/ticksRequired。
     * PB配方处理时operatingTicks不被更新（PB用自己的pbOperatingTicks），
     * 所以需要重写此方法，在PB处理时返回pbOperatingTicks/processingTime。
     * 使用同步的pbProcessingTime避免客户端重新计算（客户端无法访问升级组件）。
     */
    @Override
    public double getScaledProgress() {
        if (pbProcessing) {
            int processingTime = pbProcessingTime > 0 ? pbProcessingTime : BASE_TICKS_REQUIRED;
            return (double) pbOperatingTicks / processingTime;
        }
        return super.getScaledProgress();
    }

    /**
     * 服务端tick — 总是调用super以确保ejector被tick
     * <br/>
     * 参考Mekanism原版TileEntityNutritionalLiquifier的做法：总是调用super.onUpdateServer()，
     * 确保TileEntityConfigurableMachine中的ejectorComponent.tickServer()被执行（否则输出无法自动弹出）。
     * super会处理SMELTING配方（通过recipeCacheLookupMonitor.updateAndProcess()），
     * PB配方在tryProcessPbRecipe中独立处理（内部会跳过有SMELTING配方的输入，避免双重处理）。
     * <p>
     * 声音控制：基础机器只有1个输入槽，不可能同时处理SMELTING和PB配方。
     * PB停止时直接setActive(false)，如果SMELTING有配方在处理，下一tick的super会重新setActive(true)。
     * <p>
     * 能量追踪：super前保存能量，PB处理后计算总消耗（SMELTING + PB），
     * 与工厂版 {@link MekCentrifugeFactoryHelper#processPbRecipesAndUpdate} 逻辑对齐。
     * <p>
     * 注意：父类 TileEntityElectricMachine.onUpdateServer() 已经调用 energySlot.fillContainerOrConvert()，
     * 子类不应重复调用，否则每tick会执行两次能量容器填充（造成无意义的性能开销）。
     */
    @Override
    protected boolean onUpdateServer() {
        // 性能监控：默认关闭，isEnabled()为false时不产生System.nanoTime开销
        boolean monitor = PerformanceMonitor.isEnabled();
        long tickStartNanos = monitor ? System.nanoTime() : 0L;

        // super前保存能量，用于计算总消耗（SMELTING + PB），与工厂版逻辑保持一致
        var energyContainer = accessor().productivebeesgenesis$getEnergyContainer();
        long energyBefore = energyContainer.getEnergy();

        boolean sendUpdatePacket = super.onUpdateServer();

        // PB配方独立处理（不走Mekanism管线）
        boolean pbResult = tryProcessPbRecipe();
        if (pbResult) {
            setActive(true);
            pbWasProcessing = true;
        } else if (pbWasProcessing) {
            // PB刚停止 — 直接设为false
            // 基础机器只有1个输入槽，PB停止时不可能有SMELTING在处理
            // 如果SMELTING有配方，下一tick的super.onUpdateServer()会重新setActive(true)
            setActive(false);
            pbWasProcessing = false;
        }

        // 计算总能量消耗（SMELTING + PB），基于实际能量差
        long totalUsage = energyBefore - energyContainer.getEnergy();

        // 性能监控：记录tick耗时和能量消耗（仅启用时）
        if (monitor) {
            PerformanceMonitor monitorInst = PerformanceMonitor.getInstance();
            monitorInst.recordTickTime(System.nanoTime() - tickStartNanos);
            if (totalUsage > 0) {
                monitorInst.recordEnergyConsumed(totalUsage);
            }
        }

        // 不再重复调用 energySlot.fillContainerOrConvert() — 父类 super.onUpdateServer() 已处理
        return sendUpdatePacket;
    }

    /**
     * 尝试PB离心配方处理
     * <br/>
     * 查找PB的CentrifugeRecipe，如果找到则独立处理（不走Mekanism的CachedRecipe管线）。
     * 万象创世蜜脾/蜜脾块走特殊处理路径（转化为随机蜜脾）。
     * <p>
     * 重要：如果输入物品存在SMELTING配方，则跳过PB处理，交由super.onUpdateServer()的Mekanism管线处理，
     * 避免同一输入被双重处理（同时消耗能量和输入）。
     * <p>
     * 256倍加速优化：缓存SMELTING配方检查结果（输入变更时才重新查询），
     * 避免每tick都调用getInputCache().containsInput()，减少256倍加速下的重复查询开销。
     *
     * @return true 如果正在处理PB配方
     */
    private boolean tryProcessPbRecipe() {
        try {
            return tryProcessPbRecipeInternal();
        } catch (Exception e) {
            // 捕获异常防止tick崩溃，记录错误日志并重置PB状态
            ProductiveBeesGenesis.LOGGER.error("tryProcessPbRecipe 异常，重置PB状态", e);
            clearPbState();
            return false;
        }
    }

    private boolean tryProcessPbRecipeInternal() {
        if (level == null || level.isClientSide) return false;
        if (!canFunction()) return false;

        // 配方重载检测：版本号变更时清空 SMELTING 和 PB 配方缓存
        checkRecipeVersion();

        ItemStack input = accessor().productivebeesgenesis$getInputSlot().getStack();
        if (input.isEmpty()) {
            clearPbState();
            lastCheckedInput = ItemStack.EMPTY;
            return false;
        }

        // 256倍加速优化：缓存SMELTING配方检查结果，输入变更时才重新查询
        boolean hasSmeltingRecipe;
        if (ItemStack.isSameItemSameComponents(input, lastCheckedInput)) {
            hasSmeltingRecipe = lastHasSmeltingRecipe;
        } else {
            hasSmeltingRecipe = getRecipeType().getInputCache().containsInput(level, input);
            lastCheckedInput = input.copy();
            lastHasSmeltingRecipe = hasSmeltingRecipe;
        }

        // 如果输入有SMELTING配方，跳过PB处理（让Mekanism管线处理，避免双重处理）
        if (hasSmeltingRecipe) {
            clearPbState();
            return false;
        }

        // 缓存能量和操作数（避免循环内重复调用，getEnergyPerTick可能涉及Math.pow计算）
        var energyContainer = accessor().productivebeesgenesis$getEnergyContainer();
        cachedEnergyPerTick = energyContainer.getEnergyPerTick();
        cachedOperationsPerTick = mekanism.common.util.MekanismUtils.getOperationsPerTick(this, BASE_TICKS_REQUIRED, 1);

        // 万象创世蜜脾/蜜脾块 — 走特殊处理路径（不走PB CentrifugeRecipe）
        if (MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
                || MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)) {
            return tryProcessMyriadCreations(input);
        }

        // 配方查找（性能监控：记录查找耗时和缓存命中，仅启用时产生nanoTime开销）
        boolean monitor = PerformanceMonitor.isEnabled();
        long lookupStart = monitor ? System.nanoTime() : 0L;
        RecipeHolder<CentrifugeRecipe> pbRecipe = findPbRecipe(input);
        if (monitor) {
            PerformanceMonitor.getInstance().recordRecipeLookup(
                    System.nanoTime() - lookupStart, pbRecipeCache.wasLastGetHit());
        }
        if (pbRecipe == null) {
            clearPbState();
            return false;
        }

        // 配方变更时重置进度
        if (cachedPbRecipe != pbRecipe) {
            cachedPbRecipe = pbRecipe;
            pbOperatingTicks = 0;
        }

        // 计算并存储PB配方处理时间（同步到客户端用于进度条显示）
        int processingTime = getPbProcessingTime(pbRecipe.value());
        pbProcessingTime = processingTime;

        // 检查能量是否足够
        if (energyContainer.getEnergy() < cachedEnergyPerTick) {
            pbProcessing = true;
            return true;
        }

        // 累加进度
        pbProcessing = true;
        // MU扩展下每tick可处理多次（operationsPerTick>1），未加载MU时返回1
        // 使用缓存的 cachedOperationsPerTick，避免循环内重复调用 getOperationsPerTick（涉及升级计算）
        for (int op = 0; op < cachedOperationsPerTick; op++) {
            if (energyContainer.getEnergy() < cachedEnergyPerTick) {
                break;
            }
            pbOperatingTicks++;
            energyContainer.extract(cachedEnergyPerTick, Action.EXECUTE, AutomationType.INTERNAL);

            if (pbOperatingTicks >= processingTime) {
                // 输出槽满时暂停处理，避免物品丢失（与MEK原版NOT_ENOUGH_OUTPUT_SPACE一致）
                if (areOutputSlotsFull()) {
                    pbOperatingTicks = processingTime; // 保持满进度，等弹出器腾出空间后立即完成
                    break;
                }
                completePbRecipe(pbRecipe.value(), input, getProductivityModifier());
                pbOperatingTicks = 0;
                if (accessor().productivebeesgenesis$getInputSlot().getStack().isEmpty()) {
                    clearPbState();
                    break;
                }
            }
        }

        return true;
    }

    /**
     * 检查配方版本号是否变更，变更则清空所有 SMELTING 和 PB 配方缓存
     * <br/>
     * 在每次进入 tryProcessPbRecipe 时调用，确保配方重载后（/reload、数据包变更）
     * 立即失效旧缓存，避免使用过期的配方检查结果。
     * <p>
     * 线程安全：recipeVersion 是 volatile，读取是原子操作；清空操作在方块实体线程执行，无需同步锁。
     */
    private void checkRecipeVersion() {
        if (lastRecipeVersion != ProductiveBeesGenesis.recipeVersion) {
            lastCheckedInput = ItemStack.EMPTY;
            lastHasSmeltingRecipe = false;
            pbRecipeCache.clear();
            // 失效 getTicks 缓存（配方重载可能伴随升级配置变化，强制下次重新计算）
            cachedTicks = -1;
            lastRecipeVersion = ProductiveBeesGenesis.recipeVersion;
        }
    }

    /**
     * 尝试处理万象创世蜜脾/蜜脾块
     * <br/>
     * 万象创世蜜脾转化为随机蜜脾（最多3种，总数=生产力倍率）。
     * 万象创世蜜脾块转化为随机蜜脾块（最多3种，总数=生产力倍率*4）。
     * 使用PB原版离心机的标准处理时间。
     * <p>
     * 能量和操作数使用调用方（tryProcessPbRecipeInternal）已缓存的 cachedEnergyPerTick 和 cachedOperationsPerTick，
     * 避免在此方法中重复调用 getEnergyPerTick/getOperationsPerTick（可能涉及 Math.pow 计算）。
     */
    private boolean tryProcessMyriadCreations(ItemStack input) {
        // 万象创世使用固定的处理时间（参考PB原版离心机），使用缓存避免每tick重复计算
        int processingTime = getCachedTicks();
        pbProcessingTime = processingTime;

        // 配方变更时重置进度（用输入物品标识）
        if (cachedPbRecipe != null) {
            cachedPbRecipe = null;
            pbOperatingTicks = 0;
        }

        // 检查能量是否足够（使用缓存的 cachedEnergyPerTick）
        var energyContainer = accessor().productivebeesgenesis$getEnergyContainer();
        if (energyContainer.getEnergy() < cachedEnergyPerTick) {
            pbProcessing = true;
            return true;
        }

        // 累加进度
        pbProcessing = true;
        // MU扩展下每tick可处理多次（operationsPerTick>1），未加载MU时返回1
        // 使用缓存的 cachedOperationsPerTick，避免循环内重复调用 getOperationsPerTick
        for (int op = 0; op < cachedOperationsPerTick; op++) {
            if (energyContainer.getEnergy() < cachedEnergyPerTick) {
                break;
            }
            pbOperatingTicks++;
            energyContainer.extract(cachedEnergyPerTick, Action.EXECUTE, AutomationType.INTERNAL);

            if (pbOperatingTicks >= processingTime) {
                // 输出槽物理满时暂停处理，避免产物丢失；万象创世不再做类型数量预检
                if (areOutputSlotsFull()) {
                    pbOperatingTicks = processingTime;
                    break;
                }
                if (!completeMyriadCreations(input, getProductivityModifier())) {
                    pbOperatingTicks = processingTime;
                    break;
                }
                pbOperatingTicks = 0;
                if (accessor().productivebeesgenesis$getInputSlot().getStack().isEmpty()) {
                    clearPbState();
                    break;
                }
            }
        }

        return true;
    }

    /**
     * 获取生产力倍率
     * <br/>
     * 基础机器固定为1（一次处理1个输入）。
     * 未来可通过升级或其他方式增加。
     */
    protected int getProductivityModifier() {
        return 1;
    }

    /**
     * 获取PB配方处理时间（考虑速度升级）
     * <br/>
     * 使用MekanismUtils.getTicks()计算受速度升级影响的处理时间，
     * 与Mekanism原版机器的升级效果一致。
     */
    private int getPbProcessingTime(CentrifugeRecipe recipe) {
        int baseTime = recipe.getProcessingTime();
        if (baseTime <= 0) baseTime = BASE_TICKS_REQUIRED;
        // 使用Mekanism的升级计算工具，考虑速度升级
        return mekanism.common.util.MekanismUtils.getTicks(this, baseTime);
    }

    /**
     * 获取缓存的 getTicks(BASE_TICKS_REQUIRED) 结果（时间窗口缓存）
     * <br/>
     * 升级组件哈希计算开销较大且升级变更不频繁，采用"每 N tick 重新计算一次"策略，
     * 与 MyriadCreationsEventHandler 的 CACHE_UPDATE_INTERVAL 模式一致。
     * 升级变更后最多 {@link #TICKS_CACHE_INTERVAL} tick（1秒）内自动反映新值，可接受。
     * <p>
     * 线程安全：cachedTicks 和 cachedTicksAt 为 volatile，读写原子；方块实体在服务端单线程执行。
     *
     * @return 受速度升级影响的 BASE_TICKS_REQUIRED 处理时间
     */
    private int getCachedTicks() {
        long currentTick = level != null ? level.getGameTime() : 0L;
        if (cachedTicks < 0 || (currentTick - cachedTicksAt) >= TICKS_CACHE_INTERVAL) {
            cachedTicks = mekanism.common.util.MekanismUtils.getTicks(this, BASE_TICKS_REQUIRED);
            cachedTicksAt = currentTick;
        }
        return cachedTicks;
    }

    /** 清除PB处理状态 */
    private void clearPbState() {
        // 无条件重置所有PB状态字段，避免从PB配方切换到SMELTING配方后
        // 残留状态导致 getScaledProgress() 错误地返回0而不显示熔炼进度。
        pbProcessing = false;
        pbOperatingTicks = 0;
        pbProcessingTime = 0;
        cachedPbRecipe = null;
    }

    /**
     * 检查所有物品输出槽是否已满
     * <br/>
     * 满时暂停PB配方处理，避免物品丢失（与MEK原版NOT_ENOUGH_OUTPUT_SPACE行为一致）。
     * 仅检查物品槽，流体槽满时不暂停（流体溢出量通常较小）。
     * <p>
     * 性能优化：直接读取由 IContentsListener 维护的 {@link #outputSlotsFull} 标志位，
     * 避免每次配方完成都遍历3个槽位调用 getStack/getLimit。
     */
    private boolean areOutputSlotsFull() {
        return outputSlotsFull;
    }

    /**
     * 重新计算并更新输出槽状态标志（由输出槽 IContentsListener 调用）
     * <br/>
     * 一次遍历同时更新 {@link #hasOutputItems}（供 EjectorMixin 读取）和
     * {@link #outputSlotsFull}（供 {@link #areOutputSlotsFull} 读取），
     * 替代原 areOutputSlotsFull 的3槽遍历和 EjectorMixin 的全槽遍历。
     * volatile 保证可见性：服务端tick线程写入，EjectorMixin 同线程读取。
     */
    private void updateOutputSlotFlags() {
        OutputInventorySlot[] slots = {
                accessor().productivebeesgenesis$getOutputSlot(),
                secondaryOutputSlot,
                tertiaryOutputSlot
        };
        boolean hasItems = false;
        boolean full = true;
        for (OutputInventorySlot slot : slots) {
            if (slot == null) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) {
                hasItems = true;
                if (stack.getCount() < slot.getLimit(stack)) full = false;
            } else {
                full = false;
            }
        }
        this.hasOutputItems = hasItems;
        this.outputSlotsFull = full;
    }

    /**
     * 输出槽是否有物品（供 TileComponentEjectorMixin 读取，避免每次弹出遍历所有槽位）
     * <br/>
     * 仅 TileEntityMekCentrifuge 实现标志位优化；工厂版本由 EjectorMixin 走原遍历逻辑。
     */
    public boolean productivebeesgenesis$hasOutputItems() {
        return hasOutputItems;
    }

    /**
     * 查找匹配输入物品的PB离心配方（带实例级LRU缓存）
     * <br/>
     * 缓存命中时直接返回，避免每tick全量遍历配方列表。
     * 普通蜜脾路径优先用 {@link CentrifugeRecipeIndex} O(1) 查找，未命中再回退到全量遍历（防御性）。
     * 蜜脾块输入会动态生成对应的蜜脾块离心配方（min/max和流体乘以4）。
     */
    @Nullable
    private RecipeHolder<CentrifugeRecipe> findPbRecipe(ItemStack input) {
        if (level == null) return null;

        // 查询缓存（支持缓存"无配方"结果，避免重复全量遍历）
        Optional<RecipeHolder<CentrifugeRecipe>> cached = pbRecipeCache.get(input);
        if (cached != null) {
            return cached.orElse(null);
        }

        // 蜜脾块 — 动态生成蜜脾块离心配方（参考PB JEI插件逻辑）
        if (input.getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
            RecipeHolder<CentrifugeRecipe> blockRecipe = createCombBlockRecipe(input);
            if (blockRecipe != null) {
                pbRecipeCache.put(input, blockRecipe);
                return blockRecipe;
            }
            pbRecipeCache.put(input, null);
            return null;
        }

        // 普通蜜脾 — 优先从索引查找（O(1)），未命中再全量遍历（防御性回退）
        ResourceLocation beeType = input.get(ModDataComponents.BEE_TYPE.get());
        if (beeType != null) {
            RecipeHolder<CentrifugeRecipe> indexed = CentrifugeRecipeIndex.get(beeType);
            if (indexed != null && indexed.value().ingredient.test(input)) {
                pbRecipeCache.put(input, indexed);
                return indexed;
            }
        }

        // 索引未命中（无 bee_type 或索引为空或索引遗漏）— 全量遍历回退
        for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
                .getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
            if (holder.value().ingredient.test(input)) {
                pbRecipeCache.put(input, holder);
                return holder;
            }
        }

        // 缓存"无配方"结果，避免下次重复全量遍历
        pbRecipeCache.put(input, null);
        return null;
    }

    /**
     * 动态生成蜜脾块离心配方
     * <br/>
     * 蜜脾块 = 4个蜜脾，所以输出min/max和流体都乘以4。
     * 参考PB JEI插件（ProductiveBeesJeiPlugin）的蜜脾块配方生成逻辑。
     * 通过bee_type组件查找对应的蜜脾离心配方，然后构建蜜脾块版本。
     * 优先使用 {@link CentrifugeRecipeIndex} O(1) 查找，未命中再全量遍历（防御性回退）。
     */
    @Nullable
    private RecipeHolder<CentrifugeRecipe> createCombBlockRecipe(ItemStack combBlockInput) {
        ResourceLocation beeType = combBlockInput.get(ModDataComponents.BEE_TYPE.get());
        if (beeType == null) return null;

        // 优先从索引查找蜜脾配方（O(1)）
        RecipeHolder<CentrifugeRecipe> honeycombRecipe = CentrifugeRecipeIndex.get(beeType);

        // 索引未命中 — 回退到全量遍历（防御性）
        if (honeycombRecipe == null) {
            ItemStack honeycomb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
            honeycomb.set(ModDataComponents.BEE_TYPE.get(), beeType);
            for (RecipeHolder<CentrifugeRecipe> holder : level.getRecipeManager()
                    .getAllRecipesFor(CENTRIFUGE_RECIPE_TYPE)) {
                if (holder.value().ingredient.test(honeycomb)) {
                    honeycombRecipe = holder;
                    break;
                }
            }
        }

        if (honeycombRecipe == null) return null;

        // 动态生成蜜脾块配方：min/max和流体按配置倍率缩放
        int multiplier = ModConfig.COMMON.mekCentrifugeCombBlockMultiplier.get();
        CentrifugeRecipe original = honeycombRecipe.value();
        List<ChancedOutput> blockOutputs = new ArrayList<>();
        for (ChancedOutput chanced : original.itemOutput) {
            blockOutputs.add(new ChancedOutput(chanced.ingredient(), chanced.min() * multiplier, chanced.max() * multiplier, chanced.chance()));
        }
        SizedFluidIngredient blockFluid = new SizedFluidIngredient(original.fluidOutput.ingredient(), original.fluidOutput.amount() * multiplier);
        CentrifugeRecipe blockRecipe = new CentrifugeRecipe(original.ingredient, blockOutputs, blockFluid, original.getProcessingTime());

        return new RecipeHolder<>(honeycombRecipe.id().withSuffix("_block"), blockRecipe);
    }

    /**
     * 完成PB离心配方处理 — 概率多物品输出 + 流体输出
     * <br/>
     * PB的ChancedOutput包含概率(chance)、最小数量(min)、最大数量(max)。
     * 对每个输出：如果随机值 < chance，则产出 min~max 个物品。
     * 多个物品输出分别放入主输出槽、副输出槽1、副输出槽2。
     * 流体输出直接写入FluidTank。
     * 生产力倍率影响输出数量和消耗输入数量。
     * <p>
     * 256倍加速优化：复用reusableOutputSlots列表避免每次创建新ArrayList，
     * 使用ThreadLocalRandom替代level.getRandom()减少随机数生成开销。
     *
     * @param recipe PB离心配方
     * @param input 输入物品
     * @param productivityModifier 生产力倍率（1=正常，>1=多倍输出）
     */
    private void completePbRecipe(CentrifugeRecipe recipe, ItemStack input, int productivityModifier) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int modifier = Math.max(1, productivityModifier);

        Map<ItemStack, cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput> outputs =
                recipe.getRecipeOutputs();

        // 256倍加速优化：复用输出槽列表，避免每次完成配方都创建新ArrayList
        reusableOutputSlots.clear();
        reusableOutputSlots.add(accessor().productivebeesgenesis$getOutputSlot());
        reusableOutputSlots.add(secondaryOutputSlot);
        reusableOutputSlots.add(tertiaryOutputSlot);

        int slotIndex = 0;
        for (var entry : outputs.entrySet()) {
            var chanced = entry.getValue();
            if (random.nextFloat() < chanced.chance()) {
                int count = chanced.min();
                if (chanced.max() > chanced.min()) {
                    count += random.nextInt(chanced.max() - chanced.min() + 1);
                }
                // 生产力倍率影响输出数量
                count *= modifier;
                ItemStack outputStack = entry.getKey().copy();
                outputStack.setCount(count);

                // 尝试放入对应的输出槽
                if (slotIndex < reusableOutputSlots.size()) {
                    ItemStack remainder = reusableOutputSlots.get(slotIndex)
                            .insertItem(outputStack, Action.EXECUTE, AutomationType.INTERNAL);
                    if (!remainder.isEmpty()) {
                        // 当前槽放不下，尝试后续槽
                        for (int i = slotIndex + 1; i < reusableOutputSlots.size(); i++) {
                            remainder = reusableOutputSlots.get(i)
                                    .insertItem(remainder, Action.EXECUTE, AutomationType.INTERNAL);
                            if (remainder.isEmpty()) break;
                        }
                        if (!remainder.isEmpty()) {
                            ProductiveBeesGenesis.LOGGER.info("MEK离心机所有输出槽已满，丢弃: {}", remainder);
                        }
                    }
                }
            }
            slotIndex++;
        }

        // 处理流体输出（乘以生产力倍率）；流体槽满时静默丢弃（属正常状态，避免日志刷屏影响性能）
        FluidStack fluidOutput = recipe.getFluidOutputs();
        if (fluidOutputTank != null && !fluidOutput.isEmpty()) {
            FluidStack scaledFluid = fluidOutput.copy();
            scaledFluid.setAmount(scaledFluid.getAmount() * modifier);
            fluidOutputTank.insert(scaledFluid, Action.EXECUTE, AutomationType.INTERNAL);
        }

        // 消耗输入（乘以生产力倍率）
        accessor().productivebeesgenesis$getInputSlot().shrinkStack(modifier, Action.EXECUTE);
    }

    /**
     * 完成万象创世蜜脾/蜜脾块处理 — 转化为随机蜜脾/蜜脾块
     * <br/>
     * 万象创世蜜脾转化为随机蜜脾（最多3种，总数=生产力倍率）。
     * 万象创世蜜脾块转化为随机蜜脾块（最多3种，总数=生产力倍率*4）。
     * 使用MyriadCreationsEventHandler的随机类型选择和均匀分配算法。
     * <p>
     * 关键修复：
     * <ul>
     *   <li>按 bee_type 聚合产物后统一插入，同类型优先堆叠到同一槽</li>
     *   <li>不再预检输出槽类型数量，只以物理上能否完整插入作为暂停依据</li>
     *   <li>无法完全插入时返回 false，由调用方暂停；输入在全部产物插入成功后才会扣除</li>
     * </ul>
     *
     * @param input 万象创世蜜脾或蜜脾块
     * @param productivityModifier 生产力倍率
     * @return true 处理成功，false 应暂停等待输出槽空间
     */
    private boolean completeMyriadCreations(ItemStack input, int productivityModifier) {
        RandomSource random = level.getRandom();
        boolean isCombBlock = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
        int modifier = Math.max(1, productivityModifier);

        // 万象创世蜜脾块 = 4个蜜脾，输出总数乘以4
        int totalCount = isCombBlock ? modifier * 4 : modifier;

        // 限制种类数不超过3（输出槽数）和总数量
        int maxTypes = Math.min(3, totalCount);
        List<ResourceLocation> selectedTypes = MyriadCreationsEventHandler.selectDistinctBeeTypes(maxTypes, random);
        if (selectedTypes.isEmpty()) {
            // 缓存为空：不消耗输入，等待缓存重建后重试
            ProductiveBeesGenesis.LOGGER.warn("MEK离心机万象创世类型缓存为空，跳过本次处理（不消耗输入）");
            return true;
        }

        // 均匀分配totalCount到selectedTypes，已按 bee_type 聚合
        Map<ResourceLocation, Integer> allocation = MyriadCreationsEventHandler.allocateEvenly(totalCount, selectedTypes);

        // 构建输出槽列表
        Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();
        reusableOutputSlots.clear();
        reusableOutputSlots.add(accessor().productivebeesgenesis$getOutputSlot());
        reusableOutputSlots.add(secondaryOutputSlot);
        reusableOutputSlots.add(tertiaryOutputSlot);

        // 按 bee_type 聚合插入，同类型优先堆叠；任一类型无法完全插入则暂停，且不扣输入
        for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
            ItemStack output = new ItemStack(baseItem, entry.getValue());
            output.set(ModDataComponents.BEE_TYPE.get(), entry.getKey());

            ItemStack remainder = output;
            // 第一优先级：已有同类型槽位
            for (IInventorySlot slot : reusableOutputSlots) {
                if (slot == null) continue;
                if (MyriadCreationsEventHandler.isSameBeeType(slot.getStack(), output)) {
                    remainder = slot.insertItem(remainder, Action.EXECUTE, AutomationType.INTERNAL);
                    if (remainder.isEmpty()) break;
                }
            }
            // 第二优先级：空槽
            if (!remainder.isEmpty()) {
                for (IInventorySlot slot : reusableOutputSlots) {
                    if (slot == null) continue;
                    if (slot.getStack().isEmpty()) {
                        remainder = slot.insertItem(remainder, Action.EXECUTE, AutomationType.INTERNAL);
                        if (remainder.isEmpty()) break;
                    }
                }
            }
            // 仍有剩余说明物理上无法放下，暂停处理而非丢弃
            if (!remainder.isEmpty()) {
                ProductiveBeesGenesis.LOGGER.warn("MEK离心机万象创世产物无法完全插入，暂停：{}", remainder);
                return false;
            }
        }

        // 全部产物成功插入后才消耗输入（乘以生产力倍率）
        accessor().productivebeesgenesis$getInputSlot().shrinkStack(modifier, Action.EXECUTE);
        return true;
    }

    /**
     * 同步PB进度到客户端
     * <br/>
     * 父类通过SyncableInt同步operatingTicks和ticksRequired。
     * PB配方处理时operatingTicks不被更新，所以需要额外同步pbOperatingTicks、pbProcessing和pbProcessingTime。
     * pbProcessingTime在服务端计算（考虑速度升级），同步到客户端用于进度条显示。
     */
    @Override
    public void addContainerTrackers(mekanism.common.inventory.container.MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(mekanism.common.inventory.container.sync.SyncableInt.create(() -> pbOperatingTicks, value -> pbOperatingTicks = value));
        container.track(mekanism.common.inventory.container.sync.SyncableInt.create(() -> pbProcessing ? 1 : 0, value -> pbProcessing = value != 0));
        container.track(mekanism.common.inventory.container.sync.SyncableInt.create(() -> pbProcessingTime, value -> pbProcessingTime = value));
    }

    /**
     * 持久化PB配方处理进度
     * <br/>
     * 重写saveAdditional保存pbOperatingTicks，防止重启后进度丢失。
     */
    @Override
    public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
        super.saveAdditional(nbt, provider);
        if (pbOperatingTicks > 0) {
            nbt.putInt("PbProgress", pbOperatingTicks);
        }
    }

    /**
     * 加载PB配方处理进度
     * <br/>
     * 重写loadAdditional恢复pbOperatingTicks。
     */
    @Override
    public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
        super.loadAdditional(nbt, provider);
        pbOperatingTicks = nbt.getInt("PbProgress");
    }
}
