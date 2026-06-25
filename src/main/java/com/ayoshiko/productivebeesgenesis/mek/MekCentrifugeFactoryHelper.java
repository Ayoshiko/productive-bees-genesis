package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.MathUtils;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.interfaces.ISideConfiguration;
import mekanism.common.util.InventoryUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.TriPredicate;

/**
 * MEK离心机工厂公共逻辑辅助工具类
 * <br/>
 * 抽取三个工厂（原版/ME/EME）的公共方法，消除重复代码。
 * 三个工厂继承不同的Mekanism父类，无法通过继承抽取基类，
 * 因此采用静态工具类模式（参考 {@link com.ayoshiko.productivebeesgenesis.client.screen.GuiMekCentrifugeFactoryHelper}）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：只负责工厂公共逻辑，不涉及槽位布局、侧面配置等工厂特有代码</li>
 *   <li>依赖倒置：方法接收 {@link PbRecipeContext} 相关参数和函数式接口，不依赖具体TileEntity</li>
 *   <li>开闭原则：新增工厂类型时只需调用Helper，不修改Helper</li>
 * </ul>
 */
public final class MekCentrifugeFactoryHelper {

    private MekCentrifugeFactoryHelper() {
    }

    // ===== 公共常量 =====

    /** 输出检查谓词 — 验证SMELTING配方输出与现有输出槽物品是否可堆叠 */
    public static final TriPredicate<ItemStackToItemStackRecipe, ItemStack, ItemStack> OUTPUT_CHECK =
            (recipe, input, output) -> InventoryUtils.areItemsStackable(recipe.getOutput(input), output);

    /** 工厂跟踪的配方错误类型（原版工厂构造函数使用） */
    public static final List<RecipeError> TRACKED_ERROR_TYPES = List.of(
            RecipeError.NOT_ENOUGH_ENERGY,
            RecipeError.NOT_ENOUGH_INPUT,
            RecipeError.NOT_ENOUGH_OUTPUT_SPACE,
            RecipeError.INPUT_DOESNT_PRODUCE_OUTPUT
    );

    /** 全局配方错误类型（原版工厂构造函数使用） */
    public static final Set<RecipeError> GLOBAL_ERROR_TYPES = Set.of(RecipeError.NOT_ENOUGH_ENERGY);

    // ===== 配方类型常量 =====

    /** 返回SMELTING配方类型（Mekanism原版熔炼配方） */
    @NotNull
    public static IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
            SingleItem<ItemStackToItemStackRecipe>> getSmeltingRecipeType() {
        return MekanismRecipeType.SMELTING;
    }

    /** 返回SMELTING JEI配方查看器类型 */
    @NotNull
    public static IRecipeViewerRecipeType<ItemStackToItemStackRecipe> getSmeltingRecipeViewerType() {
        return RecipeViewerRecipeType.SMELTING;
    }

    // ===== 配方相关纯函数 =====

    /** 获取配方所需输入数量 */
    public static int getNeededInput(@NotNull ItemStackToItemStackRecipe recipe, @NotNull ItemStack inputStack) {
        return MathUtils.clampToInt(recipe.getInput().getNeededAmount(inputStack));
    }

    /** 检查缓存的配方是否对当前输入仍然有效 */
    public static boolean isCachedRecipeValid(@Nullable CachedRecipe<ItemStackToItemStackRecipe> cached,
                                              @NotNull ItemStack stack) {
        if (cached == null) return false;
        return cached.getRecipe().getInput().testType(stack);
    }

    /** 检查输入物品是否有SMELTING配方 */
    public static boolean containsSmeltingInput(
            @NotNull IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
                    SingleItem<ItemStackToItemStackRecipe>> recipeType,
            @NotNull Level level, @NotNull ItemStack input) {
        return recipeType.getInputCache().containsInput(level, input);
    }

    /** 检查输入物品是否有效（同时查找SMELTING和PB配方） */
    public static boolean isValidInputItem(
            @NotNull IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
                    SingleItem<ItemStackToItemStackRecipe>> recipeType,
            @NotNull Level level, @NotNull ItemStack stack,
            @NotNull PbRecipeProcessor pbProcessor) {
        if (containsSmeltingInput(recipeType, level, stack)) return true;
        return pbProcessor.findPbRecipe(stack) != null;
    }

    /** 查找SMELTING配方（PB配方不走Mekanism管线，由tryProcessPbRecipe独立处理） */
    @Nullable
    public static ItemStackToItemStackRecipe findSmeltingRecipe(
            @NotNull IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
                    SingleItem<ItemStackToItemStackRecipe>> recipeType,
            @NotNull Level level, @NotNull ItemStack fallbackInput,
            @NotNull IInventorySlot outputSlot) {
        return recipeType.getInputCache().findTypeBasedRecipe(
                level, fallbackInput, outputSlot.getStack(), OUTPUT_CHECK);
    }

    /**
     * PB配方输出兼容性回退检查
     * <br/>
     * SMELTING检查失败后调用，验证PB配方输出与现有输出槽内容兼容。
     * 用于 inputProducesOutput 的回退路径。
     */
    public static boolean checkPbOutputFallback(@NotNull PbRecipeProcessor pbProcessor,
                                                @NotNull ItemStack fallbackInput,
                                                @NotNull IInventorySlot outputSlot,
                                                @Nullable IInventorySlot secondaryOutputSlot) {
        RecipeHolder<CentrifugeRecipe> pbRecipe = pbProcessor.findPbRecipe(fallbackInput);
        if (pbRecipe == null) {
            return false;
        }
        return pbProcessor.isPbOutputCompatible(pbRecipe.value(), outputSlot, secondaryOutputSlot);
    }

    // ===== onUpdateServer 公共逻辑 =====

    /**
     * 处理PB配方并更新整体激活状态
     * <br/>
     * 抽取自三个工厂的 onUpdateServer 方法。super.onUpdateServer() 处理SMELTING配方后调用此方法。
     * <ul>
     *   <li>遍历所有进程，对非SMELTING输入独立处理PB配方</li>
     *   <li>重新计算整体激活状态（PB进程在super中被设为false）</li>
     *   <li>基于实际能量差计算总消耗（SMELTING + PB），不依赖getLastUsage</li>
     * </ul>
     * SMELTING配方检查结果缓存优化（输入变更时才重新查询）由PbRecipeProcessor管理。
     *
     * @param sendUpdatePacket  super.onUpdateServer() 的返回值
     * @param energyBeforeSuper super.onUpdateServer() 调用前的能量值（用于计算总消耗）
     * @param energyContainer   能量容器
     * @param processes         进程数
     * @param inputSlots        输入槽列表
     * @param pbProcessor       PB配方处理器
     * @param activateProcess   激活进程（process -> setActiveState(true, process)）
     * @param activeStates      激活状态数组（从Accessor获取）
     * @param currentActive     当前整体激活状态（getActive()）
     * @param setActive         设置整体激活状态（this::setActive）
     * @param setLastUsage      设置最近能量使用（从Accessor获取）
     * @return sendUpdatePacket（原样返回）
     */
    public static boolean processPbRecipesAndUpdate(
            boolean sendUpdatePacket,
            long energyBeforeSuper,
            @NotNull MachineEnergyContainer<?> energyContainer,
            int processes,
            @NotNull List<IInventorySlot> inputSlots,
            @NotNull PbRecipeProcessor pbProcessor,
            @NotNull IntConsumer activateProcess,
            @NotNull boolean[] activeStates,
            boolean currentActive,
            @NotNull Consumer<Boolean> setActive,
            @NotNull LongConsumer setLastUsage) {

        // PB配方独立处理 — 只处理非SMELTING配方且输入不为空的进程
        for (int i = 0; i < processes; i++) {
            ItemStack input = inputSlots.get(i).getStack();
            if (input.isEmpty()) {
                // 空输入：重置缓存并跳过
                pbProcessor.resetSmeltingCache(i);
                continue;
            }
            // 缓存SMELTING配方检查结果，输入变更时才重新查询
            if (pbProcessor.hasSmeltingRecipe(i, input)) {
                // SMELTING配方由super处理，跳过PB路径
                continue;
            }
            if (pbProcessor.tryProcessPbRecipe(i)) {
                activateProcess.accept(i);
            }
        }

        // 重新计算整体激活状态（super已根据activeStates调用setActive，但PB进程在super中被设为false）
        boolean isActive = false;
        for (boolean state : activeStates) {
            if (state) {
                isActive = true;
                break;
            }
        }
        if (isActive != currentActive) {
            setActive.accept(isActive);
        }

        // 计算总能量消耗（SMELTING + PB），基于实际能量差，不依赖getLastUsage
        long totalUsage = energyBeforeSuper - energyContainer.getEnergy();
        if (totalUsage > 0) {
            setLastUsage.accept(totalUsage);
        }

        return sendUpdatePacket;
    }

    // ===== 进度获取 =====

    /**
     * 获取缩放进度（PB处理时返回PB进度，否则返回super进度）
     * <br/>
     * PB配方处理时progress[]不被Mekanism管线更新，需要用pbOperatingTicks计算进度。
     * 使用同步的pbProcessingTime避免客户端重新计算（客户端无法访问升级组件）。
     *
     * @param i              进度缩放因子
     * @param process        进程索引
     * @param pbProcessor    PB配方处理器
     * @param superProgress  super.getScaledProgress 的供应商（延迟调用以避免不必要计算）
     */
    public static double getScaledProgress(int i, int process,
                                           @NotNull PbRecipeProcessor pbProcessor,
                                           @NotNull DoubleSupplier superProgress) {
        if (pbProcessor.isPbProcessing(process)) {
            return pbProcessor.getPbScaledProgress(i, process);
        }
        return superProgress.getAsDouble();
    }

    // ===== 流体输出槽创建 =====

    /**
     * 创建共享流体输出槽并构建FluidTankHolder
     * <br/>
     * 抽取自三个工厂的 getInitialFluidTanks 方法。容量随进程数缩放
     * （参考Mekanism原版化学工厂化学槽容量计算方式）。
     *
     * @param factory    工厂实例（提供侧面配置，实现ISideConfiguration）
     * @param listener   内容变更监听器
     * @param processes  进程数（容量 = 基础容量 × 进程数）
     * @param tankSetter 流体槽赋值回调（工厂用于赋值给fluidOutputTank字段）
     * @return 构建好的IFluidTankHolder，工厂直接返回
     */
    @NotNull
    public static IFluidTankHolder createFluidOutputHolder(
            @NotNull ISideConfiguration factory,
            @NotNull IContentsListener listener,
            int processes,
            @NotNull Consumer<IExtendedFluidTank> tankSetter) {
        FluidTankHelper helper = FluidTankHelper.forSideWithConfig(factory);
        IExtendedFluidTank tank = BasicFluidTank.output(
                ModConfig.COMMON.mekCentrifugeFluidTankCapacity.get() * processes, listener);
        tankSetter.accept(tank);
        helper.addTank(tank);
        return helper.build();
    }

    // ===== 构造函数公共逻辑 =====

    /**
     * 注册副输出槽2并配置工厂IO与弹出器
     * <br/>
     * 抽取自三个工厂构造函数的公共逻辑：
     * <ul>
     *   <li>将tertiaryOutputSlots加入outputSlots列表（参与侧面配置和弹出器）</li>
     *   <li>重新调用setupItemIOConfig，将tertiaryOutputSlots注册到OUTPUT DataType
     *       （父类构造函数中的setupItemIOConfig不包含tertiaryOutputSlots）</li>
     *   <li>配置流体输出侧面（右侧）</li>
     *   <li>重写ejectorComponent添加FLUID弹出（父类只配置了ITEM）</li>
     * </ul>
     * 各工厂energySlot获取方式不同（原版/ME用Accessor，EME用getEnergySlot()），
     * 由调用方获取后传入。
     *
     * @param factory             工厂实例（用于构造TileComponentEjector）
     * @param configComponent     配置组件（工厂的configComponent字段）
     * @param inputSlots          输入槽列表
     * @param outputSlots         输出槽列表（方法会向其追加tertiaryOutputSlots）
     * @param tertiaryOutputSlots 副输出槽2数组
     * @param processes           进程数
     * @param energySlot          能量槽（调用方通过Accessor或getEnergySlot()获取）
     * @param energyContainer     能量容器
     * @param fluidOutputTank     流体输出槽
     * @return 配置好的TileComponentEjector，调用方赋值给ejectorComponent字段
     */
    @NotNull
    public static TileComponentEjector setupTertiarySlotsAndIO(
            @NotNull TileEntityMekanism factory,
            @NotNull TileComponentConfig configComponent,
            @NotNull List<IInventorySlot> inputSlots,
            @NotNull List<IInventorySlot> outputSlots,
            @NotNull IInventorySlot[] tertiaryOutputSlots,
            int processes,
            @NotNull EnergyInventorySlot energySlot,
            @NotNull MachineEnergyContainer<?> energyContainer,
            @NotNull IExtendedFluidTank fluidOutputTank) {
        // 将副输出槽2加入outputSlots列表，使其参与侧面配置和弹出器
        for (int i = 0; i < processes; i++) {
            outputSlots.add(tertiaryOutputSlots[i]);
        }
        // 重新调用setupItemIOConfig，将tertiaryOutputSlots注册到OUTPUT DataType
        configComponent.setupItemIOConfig(inputSlots, outputSlots, energySlot, false);
        configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);
        // 配置流体侧面（TileEntityFactory默认不配置流体），作为输出配置（右侧）
        configComponent.setupOutputConfig(TransmissionType.FLUID, fluidOutputTank, RelativeSide.RIGHT);
        // 重写ejectorComponent添加FLUID弹出（父类TileEntityFactory只配置了ITEM）
        TileComponentEjector ejector = new TileComponentEjector(factory);
        ejector.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
        return ejector;
    }
}
