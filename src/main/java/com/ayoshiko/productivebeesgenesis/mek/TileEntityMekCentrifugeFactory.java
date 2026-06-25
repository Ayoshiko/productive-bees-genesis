package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mekanism.api.IContentsListener;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.MathUtils;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.api.recipes.cache.OneInputCachedRecipe;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.inputs.InputHelper;
import mekanism.api.recipes.outputs.IOutputHandler;
import mekanism.api.recipes.outputs.OutputHelper;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.FactoryInputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.ISingleRecipeLookupHandler.ItemRecipeLookupHandler;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.factory.TileEntityItemToItemFactory;
import mekanism.common.upgrade.MachineUpgradeData;
import mekanism.common.util.InventoryUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityFactoryAccessor;

/**
 * 工厂版MEK离心机方块实体
 * <br/>
 * 继承Mekanism的TileEntityItemToItemFactory，复用多进程并行处理、排序、升级体系。
 * <p>
 * 双配方处理路径：
 * <ul>
 *   <li>SMELTING配方：走Mekanism标准CachedRecipe管线，输出到主输出槽</li>
 *   <li>PB CentrifugeRecipe：独立处理（不走CachedRecipe管线），支持概率多物品输出+流体输出</li>
 * </ul>
 * 每进程有3个输出槽（主输出+副输出1+副输出2），共享1个流体输出槽（容量随tier.processes缩放）。
 * SMELTING配方优先于PB配方（同一输入若有SMELTING配方则走SMELTING路径）。
 * <p>
 * PB配方处理逻辑委托给 {@link PbRecipeProcessor}，通过实现 {@link PbRecipeContext} 提供依赖。
 */
public class TileEntityMekCentrifugeFactory extends TileEntityItemToItemFactory<ItemStackToItemStackRecipe>
        implements ItemRecipeLookupHandler<ItemStackToItemStackRecipe>, PbRecipeContext {

    /** 副输出槽2 — 每进程第3个物品输出槽（ProcessInfo只支持1个secondary，第3个单独管理） */
    private OutputInventorySlot[] tertiaryOutputSlots;

    /** 流体输出槽 — 共享，接收PB配方的流体输出 */
    private IExtendedFluidTank fluidOutputTank;

    /** PB配方处理器 — 封装所有PB离心配方处理逻辑 */
    private final PbRecipeProcessor pbProcessor;

    public TileEntityMekCentrifugeFactory(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state, MekCentrifugeFactoryHelper.TRACKED_ERROR_TYPES, MekCentrifugeFactoryHelper.GLOBAL_ERROR_TYPES);
        // 初始化PB配方处理器（tier在super()中已通过presetVariables设置）
        pbProcessor = new PbRecipeProcessor(this, "工厂离心机");

        // energySlot是TileEntityFactory的包私有字段，通过Accessor Mixin访问
        // 副输出槽2注册、IO配置、流体侧面配置、FLUID弹出器由Helper统一处理
        EnergyInventorySlot energySlot = ((TileEntityFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
        ejectorComponent = MekCentrifugeFactoryHelper.setupTertiarySlotsAndIO(
                this, configComponent, inputSlots, outputSlots, tertiaryOutputSlots,
                tier.processes, energySlot, energyContainer, fluidOutputTank);
    }

    /**
     * 重写getInitialInventory — 调整energySlot位置
     * <br/>
     * 父类TileEntityFactory硬编码energySlot坐标为(7, 13)，适合1行输出槽的原版工厂。
     * 本项目离心机工厂有3行输出槽，原版4等级与EM高等级需要不同布局：
     * <ul>
     *   <li>原版4等级：保持父类(7, 13)，红石槽在红色输入槽左侧，与Mek原版一致</li>
     *   <li>EM高等级：一比一复刻EM原版TileEntityFactoryMixin公式，红石槽在物品栏左侧下方</li>
     * </ul>
     */
    @NotNull
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this);
        TileEntityFactoryAccessor accessor = (TileEntityFactoryAccessor) this;
        addSlots(builder, listener, () -> {
            listener.onContentsChanged();
            accessor.productivebeesgenesis$setSortingNeeded(true);
        });
        // 计算energySlot坐标：原版4等级保持默认(7,13)；EM高等级使用EM原版公式
        int energySlotX = 7;
        int energySlotY = 13;
        if (FactoryLayoutHelper.isEMHighTier(tier)) {
            int imageWidth = 176 + FactoryLayoutHelper.getImageWidthAddition(tier);
            int inventorySize = 9 * 20;
            int startInventory = 8 + (imageWidth / 2 - inventorySize / 2);
            energySlotX = startInventory - 22;
            energySlotY = 193;
        }
        EnergyInventorySlot energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener, energySlotX, energySlotY);
        accessor.productivebeesgenesis$setEnergySlot(energySlot);
        builder.addSlot(energySlot);
        return builder.build();
    }

    /**
     * 重写addSlots — 每进程添加3个输出槽
     * <br/>
     * 参考TileEntitySawingFactory，但添加2个副输出槽（而非1个）。
     * 主输出槽(y=57) + 副输出槽1(y=77) + 副输出槽2(y=97)。
     * ProcessInfo的secondaryOutputSlot设为副输出槽1，副输出槽2用单独数组管理。
     * <br/>
     * baseX/baseXMult通过 {@link FactoryLayoutHelper} 动态计算，统一支持原版4等级与EM扩展高等级。
     */
    @Override
    protected void addSlots(InventorySlotHelper builder, IContentsListener listener, IContentsListener updateSortingListener) {
        inputHandlers = new IInputHandler[tier.processes];
        outputHandlers = new IOutputHandler[tier.processes];
        processInfoSlots = new ProcessInfo[tier.processes];
        tertiaryOutputSlots = new OutputInventorySlot[tier.processes];

        // 通过FactoryLayoutHelper动态计算布局参数，支持原版4等级与EM高等级
        int baseX = FactoryLayoutHelper.getBaseX(tier);
        int baseXMult = FactoryLayoutHelper.getBaseXMult(tier);

        for (int i = 0; i < tier.processes; i++) {
            int xPos = baseX + (i * baseXMult);
            var lookupMonitor = recipeCacheLookupMonitors[i];
            IContentsListener updateSortingAndUnpause = () -> {
                updateSortingListener.onContentsChanged();
                lookupMonitor.unpause();
            };

            // 主输出槽
            OutputInventorySlot outputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 57);
            // 副输出槽1
            OutputInventorySlot secondaryOutputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 77);
            // 副输出槽2
            OutputInventorySlot tertiaryOutputSlot = OutputInventorySlot.at(updateSortingAndUnpause, xPos, 97);
            tertiaryOutputSlots[i] = tertiaryOutputSlot;

            // 输入槽（传入主输出和副输出1用于inputProducesOutput检查）
            FactoryInputInventorySlot inputSlot = FactoryInputInventorySlot.create(this, i, outputSlot, secondaryOutputSlot, lookupMonitor, xPos, 13);

            int index = i;
            builder.addSlot(inputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_MATCHING_RECIPE, getWarningCheck(RecipeError.NOT_ENOUGH_INPUT, index)));
            builder.addSlot(outputSlot).tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT, getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE, index)));
            builder.addSlot(secondaryOutputSlot);
            builder.addSlot(tertiaryOutputSlot);

            // SMELTING配方只使用主输出槽
            inputHandlers[i] = InputHelper.getInputHandler(inputSlot, RecipeError.NOT_ENOUGH_INPUT);
            outputHandlers[i] = OutputHelper.getOutputHandler(outputSlot, RecipeError.NOT_ENOUGH_OUTPUT_SPACE);
            processInfoSlots[i] = new ProcessInfo(i, inputSlot, outputSlot, secondaryOutputSlot);
        }
    }

    /**
     * 重写getInitialFluidTanks — 添加共享流体输出槽
     * <br/>
     * TileEntityFactory默认无流体槽，重写此方法添加输出槽。
     * 容量随tier.processes缩放（参考Mekanism原版化学工厂）：
     * BASIC(3进程)=30000mB, ADVANCED(5)=50000mB, ELITE(7)=70000mB, ULTIMATE(9)=90000mB。
     * PB配方的流体输出写入此槽。
     */
    @Nullable
    @Override
    protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
        // 容量 = 基础容量 * 进程数，与Mekanism原版化学工厂化学槽容量计算方式一致
        return MekCentrifugeFactoryHelper.createFluidOutputHolder(this, listener, tier.processes, t -> fluidOutputTank = t);
    }

    @Override
    public boolean isItemValidForSlot(@NotNull ItemStack stack) {
        return true;
    }

    /**
     * 重写isValidInputItem — 同时查找SMELTING和PB CentrifugeRecipe
     * <br/>
     * 输入物品只要有任一配方即可放入输入槽。
     */
    @Override
    public boolean isValidInputItem(@NotNull ItemStack stack) {
        return MekCentrifugeFactoryHelper.isValidInputItem(getRecipeType(), level, stack, pbProcessor);
    }

    @Override
    protected int getNeededInput(ItemStackToItemStackRecipe recipe, ItemStack inputStack) {
        return MekCentrifugeFactoryHelper.getNeededInput(recipe, inputStack);
    }

    @Override
    protected boolean isCachedRecipeValid(@Nullable CachedRecipe<ItemStackToItemStackRecipe> cached,
                                          @NotNull ItemStack stack) {
        return MekCentrifugeFactoryHelper.isCachedRecipeValid(cached, stack);
    }

    /**
     * 重写findRecipe — 只查SMELTING配方
     * <br/>
     * PB配方不走Mekanism管线，由tryProcessPbRecipe独立处理。
     */
    @Override
    protected ItemStackToItemStackRecipe findRecipe(int process, @NotNull ItemStack fallbackInput,
                                                    @NotNull IInventorySlot outputSlot,
                                                    @Nullable IInventorySlot secondaryOutputSlot) {
        return MekCentrifugeFactoryHelper.findSmeltingRecipe(getRecipeType(), level, fallbackInput, outputSlot);
    }

    /**
     * 重写inputProducesOutput — 支持PB配方输出兼容性检查
     * <br/>
     * 父类只检查SMELTING配方，PB物品在输出槽非空时被阻止分配到空进程（sortInventory门控）。
     * 重写后：SMELTING检查失败时回退到PB配方检查，验证PB配方输出与现有输出槽内容兼容。
     * <p>
     * 此方法在两处被调用：
     * 1. sortInventory() → addEmptySlotsAsTargets() — 排序时决定空进程能否接收物品
     * 2. FactoryInputInventorySlot构造函数 — 验证物品能否插入输入槽
     */
    @Override
    public boolean inputProducesOutput(int process, @NotNull ItemStack fallbackInput,
                                       @NotNull IInventorySlot outputSlot, @Nullable IInventorySlot secondaryOutputSlot,
                                       boolean updateCache) {
        // 先检查SMELTING配方（父类逻辑），不匹配时回退到PB配方兼容性检查
        return super.inputProducesOutput(process, fallbackInput, outputSlot, secondaryOutputSlot, updateCache)
                || MekCentrifugeFactoryHelper.checkPbOutputFallback(pbProcessor, fallbackInput, outputSlot, secondaryOutputSlot);
    }

    /**
     * 配置卡兼容性检查
     * <br/>
     * 原版/EM工厂方块有AttributeFactoryType属性，ME/EME工厂方块有EMExtraAttributeFactoryType属性，
     * MekanismUtils.isSameTypeFactory()无法识别ME/EME方块，导致配置卡粘贴失败。
     * 此方法增加对AttributeFactoryType和EMExtraAttributeFactoryType的兼容检查，
     * 允许同类型（如SMELTING）的工厂跨等级粘贴配置。
     */
    @Override
    public boolean isConfigurationDataCompatible(@NotNull Block blockType) {
        return super.isConfigurationDataCompatible(blockType)
                || MekCompatHooks.isConfigurationDataCompatible(getBlockHolder(), blockType);
    }

    @NotNull
    @Override
    public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
            SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
        return MekCentrifugeFactoryHelper.getSmeltingRecipeType();
    }

    /**
     * JEI配方查看器跳转支持
     * <br/>
     * 返回SMELTING类型，使JEI中点击配方时能正确跳转到熔炼配方类别。
     * 参考Mekanism原版TileEntityItemStackToItemStackFactory的实现。
     */
    @NotNull
    @Override
    public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
        return MekCentrifugeFactoryHelper.getSmeltingRecipeViewerType();
    }

    /**
     * 重写getRecipe — SMELTING配方查找
     * <br/>
     * PB输入物品无SMELTING配方时返回null，Mekanism管线跳过该进程，
     * 由onUpdateServer中的tryProcessPbRecipe接管。
     */
    @Nullable
    @Override
    public ItemStackToItemStackRecipe getRecipe(int cacheIndex) {
        return findFirstRecipe(inputHandlers[cacheIndex]);
    }

    @NotNull
    @Override
    public CachedRecipe<ItemStackToItemStackRecipe> createNewCachedRecipe(
            @NotNull ItemStackToItemStackRecipe recipe, int cacheIndex) {
        return OneInputCachedRecipe.itemToItem(
                recipe, recheckAllRecipeErrors[cacheIndex],
                inputHandlers[cacheIndex], outputHandlers[cacheIndex])
                .setErrorsChanged(errors -> errorTracker.onErrorsChanged(errors, cacheIndex))
                .setCanHolderFunction(this::canFunction)
                .setActive(active -> setActiveState(active, cacheIndex))
                .setEnergyRequirements(energyContainer::getEnergyPerTick, energyContainer)
                .setRequiredTicks(this::getTicksRequired)
                .setOnFinish(this::markForSave)
                .setBaselineMaxOperations(this::getOperationsPerTick)
                .setOperatingTicksChanged(operatingTicks -> progress[cacheIndex] = operatingTicks);
    }

    /**
     * 重写onUpdateServer — 先走SMELTING管线，再处理PB配方
     * <br/>
     * super.onUpdateServer()处理SMELTING配方（PB输入因getRecipe返回null被跳过），
     * 并消耗SMELTING能量。之后由Helper统一处理PB配方独立处理、
     * 激活状态重算和总能量消耗计算（基于super前后能量差，避免SMELTING消耗被双重计算）。
     * <p>
     * SMELTING配方检查结果缓存优化（输入变更时才重新查询）由PbRecipeProcessor管理。
     */
    @Override
    protected boolean onUpdateServer() {
        // super前保存能量，由Helper基于能量差计算总消耗（SMELTING + PB）
        TileEntityFactoryAccessor accessor = (TileEntityFactoryAccessor) this;
        long energyBeforeSuper = energyContainer.getEnergy();
        boolean sendUpdatePacket = super.onUpdateServer();
        return MekCentrifugeFactoryHelper.processPbRecipesAndUpdate(
                sendUpdatePacket,
                energyBeforeSuper,
                energyContainer,
                tier.processes,
                inputSlots,
                pbProcessor,
                i -> setActiveState(true, i),
                accessor.productivebeesgenesis$getActiveStates(),
                getActive(),
                this::setActive,
                v -> accessor.productivebeesgenesis$setLastUsage(v)
        );
    }

    /**
     * 重写getScaledProgress — PB处理时返回PB进度
     * <br/>
     * PB配方处理时progress[]不被Mekanism管线更新，需要用pbOperatingTicks计算进度。
     * 使用同步的pbProcessingTime避免客户端重新计算（客户端无法访问升级组件）。
     */
    @Override
    public double getScaledProgress(int i, int process) {
        return MekCentrifugeFactoryHelper.getScaledProgress(i, process, pbProcessor, () -> super.getScaledProgress(i, process));
    }

    /**
     * 同步PB进度到客户端
     * <br/>
     * 每进程的pbOperatingTicks、pbProcessing和pbProcessingTime需要同步给客户端用于GUI显示。
     */
    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        pbProcessor.addContainerTrackers(container);
    }

    /**
     * 持久化PB配方处理进度
     * <br/>
     * 防止重启后PB处理进度丢失。
     */
    @Override
    public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
        super.saveAdditional(nbt, provider);
        pbProcessor.saveAdditional(nbt);
    }

    /** 加载PB配方处理进度 */
    @Override
    public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
        super.loadAdditional(nbt, provider);
        pbProcessor.loadAdditional(nbt);
    }

    @NotNull
    @Override
    public MachineUpgradeData getUpgradeData(HolderLookup.Provider provider) {
        EnergyInventorySlot energySlot = ((TileEntityFactoryAccessor) this).productivebeesgenesis$getEnergySlot();
        return new MachineUpgradeData(provider, redstone, getControlType(),
                getEnergyContainer(), progress, energySlot, inputSlots, outputSlots,
                isSorting(), getComponents());
    }

    // ===== PbRecipeContext 接口实现 =====

    @Override
    public Level level() {
        return level;
    }

    @Override
    public MachineEnergyContainer<?> energyContainer() {
        return energyContainer;
    }

    @Override
    public IInventorySlot inputSlot(int process) {
        return inputSlots.get(process);
    }

    @Override
    public IInventorySlot primaryOutputSlot(int process) {
        return processInfoSlots[process].outputSlot();
    }

    @Override
    public IInventorySlot secondaryOutputSlot(int process) {
        return processInfoSlots[process].secondaryOutputSlot();
    }

    @Override
    public IInventorySlot tertiaryOutputSlot(int process) {
        return tertiaryOutputSlots[process];
    }

    @Override
    public IExtendedFluidTank fluidOutputTank() {
        return fluidOutputTank;
    }

    @Override
    public int processes() {
        return tier.processes;
    }

    @Override
    public int baseTicksRequired() {
        return BASE_TICKS_REQUIRED;
    }

    @Override
    public void setPbActiveState(boolean active, int process) {
        setActiveState(active, process);
    }

    @Override
    public int productivityModifier() {
        return 1;
    }

    @Override
    public int operationsPerTick() {
        return MekanismUtils.getOperationsPerTick(this, BASE_TICKS_REQUIRED, 1);
    }

    @Override
    public int getTicksForBase(int baseTime) {
        return MekanismUtils.getTicks(this, baseTime);
    }

    @Override
    public boolean containsSmeltingInput(ItemStack input) {
        return MekCentrifugeFactoryHelper.containsSmeltingInput(getRecipeType(), level, input);
    }

    // ===== GUI暴露方法 =====

    /** 获取副输出槽1 — GUI显示用 */
    @Nullable
    public IInventorySlot getSecondaryOutputSlot(int processIndex) {
        return processInfoSlots[processIndex].secondaryOutputSlot();
    }

    /** 获取副输出槽2 — GUI显示用 */
    @NotNull
    public IInventorySlot getTertiaryOutputSlot(int processIndex) {
        return tertiaryOutputSlots[processIndex];
    }

    /** 获取流体输出槽 — GUI显示用 */
    @NotNull
    public IExtendedFluidTank getFluidOutputTank() {
        return fluidOutputTank;
    }
}
