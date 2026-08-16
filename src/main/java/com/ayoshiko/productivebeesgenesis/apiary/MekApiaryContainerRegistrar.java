package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import mekanism.api.AutomationType;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.attachments.containers.creator.BaseContainerCreator;
import mekanism.common.attachments.containers.creator.IBasicContainerCreator;
import mekanism.common.attachments.containers.energy.EnergyContainersBuilder;
import mekanism.common.attachments.containers.fluid.FluidTanksBuilder;
import mekanism.common.attachments.containers.item.AttachedItems;
import mekanism.common.attachments.containers.item.ComponentBackedInventorySlot;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeEnergy;
import mekanism.common.config.MekanismConfig;
import mekanism.common.integration.energy.EnergyCompatUtils;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.ArrayList;
import java.util.List;

/**
	 * 通用机械蜂箱 ContainerType 默认创建器注册器
	 * <br/>
	 * Task 2：参考 {@link com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeContainerRegistrar} 实现，
	 * 为蜂箱注册 ENERGY/FLUID/ITEM 三种容器创建器，使 Shift tooltip 能显示电量、流体、物品栏信息。
	 * <p>
	 * 原理：MEK 的 {@link mekanism.common.item.block.ItemBlockTooltip#addDetails} 通过
	 * {@link mekanism.common.util.StorageUtils#getStoredFluidFromAttachment} 和
	 * {@link mekanism.common.util.StorageUtils#addStoredEnergy} 读取 ItemStack 上的
	 * ATTACHED_FLUIDS / ATTACHED_ENERGY DataComponent。
	 * 若未注册容器创建器，扳手拆卸后这些组件不存在，tooltip 无法显示存储信息。
	 * <p>
	 * 注册流程：
	 * <ol>
	 *   <li>{@link #registerContainers} — RegisterEvent 期间注册容器创建器到 knownDefaultCreators</li>
	 *   <li>{@link #modifyDefaultComponents} — ModifyDefaultComponentsEvent 期间添加默认 DataComponents</li>
	 * </ol>
	 * 扳手拆卸时 {@link mekanism.common.tile.base.TileEntityMekanism#collectImplicitComponents}
	 * 自动将 TileEntity 中的容器数据复制到 ItemStack 的 attachment 组件。
	 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public class MekApiaryContainerRegistrar {

	private MekApiaryContainerRegistrar() {}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void registerContainers(RegisterEvent event) {
		if (!event.getRegistryKey().equals(Registries.ITEM)) {
			return;
		}
		for (var entry : ModItems.ITEMS.getEntries()) {
			Item item = entry.get();
			if (item instanceof ItemBlockMekApiary mekItem) {
				registerContainerCreators(mekItem);
			}
		}
	}

	private static void registerContainerCreators(ItemBlockMekApiary item) {
		MekApiaryBlock<?, ?> block = item.getBlock();

		// ENERGY — 能量容器创建器（蜂箱支持 ENERGY 升级，走动态容量分支）
		if (Attribute.has(block, AttributeEnergy.class)) {
			ContainerType.ENERGY.addDefaultCreators(null, item, () ->
					item.buildDefaultEnergyContainers(EnergyContainersBuilder.builder()).build(),
					MekanismConfig.storage, MekanismConfig.usage);
		}

		// FLUID — 流体容器创建器（容量根据工厂等级动态确定）
		int fluidCapacity = getFluidCapacity(block);
		ContainerType.FLUID.addDefaultCreators(null, item, () ->
				FluidTanksBuilder.builder()
						.addBasic(fluidCapacity)
						.build());

		// ITEM — 物品容器创建器（槽位数根据工厂等级动态确定）
		int outputSlotCount = getOutputSlotCount(block);
		ContainerType.ITEM.addDefaultCreators(null, item, () ->
				buildApiaryItemSlots(outputSlotCount));
	}

	/**
	 * 获取蜂箱的流体罐容量
	 * <br/>
	 * 基础版：256,000 mB
	 * 工厂版：根据工厂等级递增（原版 256K/512K/768K/1,024K，ME/EME 按等级继续递增）
	 * <p>
	 * ME/EME 等级通过 compat 包辅助类（MEContainerSlotHelper/EMEContainerSlotHelper）识别，
	 * 本类不直接 import ME/EME 类，实现软依赖完全隔离。
	 * 模式与离心机 {@link com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeContainerRegistrar} 一致。
	 */
	private static int getFluidCapacity(MekApiaryBlock<?, ?> block) {
		FactoryTier tier = Attribute.getTier(block, FactoryTier.class);
		if (tier != null) {
			return FactoryApiaryConfig.forTier(tier).fluidTankCapacity;
		}
		// ME 等级（Mekanism Extras）— 通过 compat 辅助类隔离软依赖
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			int capacity = MEContainerSlotHelper.getFluidCapacity(block);
			if (capacity >= 0) {
				return capacity;
			}
		}
		// EME 等级（Evolved Mekanism Extras）— 通过 compat 辅助类隔离软依赖
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			int capacity = EMEContainerSlotHelper.getFluidCapacity(block);
			if (capacity >= 0) {
				return capacity;
			}
		}
		// 基础版蜂箱
		return ApiarySlotManager.DEFAULT_FLUID_TANK_CAPACITY;
	}

	/**
	 * 获取蜂箱的输出槽数量（用于构建物品容器创建器）
	 * <br/>
	 * 基础版：18 个物理输出槽（3×3，每页 9 格）
	 * 工厂版：由 {@link FactoryApiaryConfig} 根据蜜蜂列数动态对齐并创建两页物理槽（ME/EME 同样适用）
	 * 物品槽位总数 = 输出槽 + 3（1蜂笼输入 + 1蜂笼输出 + 1能量槽）
	 * <p>
	 * ME/EME 等级识别与 {@link #getFluidCapacity} 一致，通过 compat 辅助类隔离软依赖。
	 */
	private static int getOutputSlotCount(MekApiaryBlock<?, ?> block) {
		FactoryTier tier = Attribute.getTier(block, FactoryTier.class);
		if (tier != null) {
			return FactoryApiaryConfig.forTier(tier).outputSlotCount;
		}
		// ME 等级（Mekanism Extras）— 通过 compat 辅助类隔离软依赖
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			int count = MEContainerSlotHelper.getOutputSlotCount(block);
			if (count >= 0) {
				return count;
			}
		}
		// EME 等级（Evolved Mekanism Extras）— 通过 compat 辅助类隔离软依赖
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			int count = EMEContainerSlotHelper.getOutputSlotCount(block);
			if (count >= 0) {
				return count;
			}
		}
		// 基础版蜂箱：两页物理输出库存，容器每页显示 3×3。
		return ApiarySlotManager.DEFAULT_OUTPUT_SLOT_COUNT * 2;
	}

	/**
	 * 构建蜂箱物品槽位创建器列表
	 * <br/>
	 * 槽位顺序与 {@link ApiarySlotManager} 一致：
	 * <ol>
	 *   <li>蜂笼输入槽（InputInventorySlot）— notExternal 插入谓词</li>
	 *   <li>蜂笼输出槽（OutputInventorySlot）— internalOnly 提取谓词</li>
	 *   <li>输出槽 × N（OutputInventorySlot）— internalOnly 提取谓词</li>
	 *   <li>能量槽（EnergyInventorySlot）— 特殊能量谓词</li>
	 * </ol>
	 */
	private static ItemSlotListCreator buildApiaryItemSlots(int outputSlots) {
		List<IBasicContainerCreator<? extends ComponentBackedInventorySlot>> creators = new ArrayList<>();
		// 蜂笼输入槽
		creators.add((type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
				ConstantPredicates.notExternal(), ConstantPredicates.alwaysTrueBi(), ConstantPredicates.alwaysTrue()));
		// 蜂笼输出槽
		creators.add((type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
				ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(), ConstantPredicates.alwaysTrue()));
		// 输出槽 × N
		for (int i = 0; i < outputSlots; i++) {
			creators.add((type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
					ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(), ConstantPredicates.alwaysTrue()));
		}
		// 能量槽
		creators.add(createEnergySlotCreator());
		return new ItemSlotListCreator(creators);
	}

	/**
	 * 创建能量槽创建器 — 与离心机一致
	 * <br/>
	 * 插入谓词：手动操作或可转换能量的物品
	 * 提取谓词：有严格能量处理器的物品
	 */
	private static IBasicContainerCreator<ComponentBackedInventorySlot> createEnergySlotCreator() {
		return (type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
				// 模块 3 修复：INTERNAL 模式(合成升级路径)下允许能量物品插入能量槽，
				// 使 MekanismShapedRecipe.assemble 中的 insertItemStacked 能成功转移内部能量物品
				(stack, automationType) -> automationType == AutomationType.MANUAL
						|| (automationType == AutomationType.INTERNAL
								&& (EnergyCompatUtils.hasStrictEnergyHandler(stack)
										|| EnergyInventorySlot.getPotentialConversion(null, stack) > 0L))
						|| (!EnergyCompatUtils.hasStrictEnergyHandler(stack)
								&& EnergyInventorySlot.getPotentialConversion(null, stack) == 0L),
				(stack, automationType) -> EnergyCompatUtils.hasStrictEnergyHandler(stack)
						|| EnergyInventorySlot.getPotentialConversion(null, stack) > 0L,
				stack -> EnergyCompatUtils.hasStrictEnergyHandler(stack)
						|| EnergyInventorySlot.getPotentialConversion(null, stack) > 0L);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void modifyDefaultComponents(ModifyDefaultComponentsEvent event) {
		for (var entry : ModItems.ITEMS.getEntries()) {
			Item item = entry.get();
			if (item instanceof ItemBlockMekApiary) {
				if (ContainerType.anySupports(entry)) {
					event.modify(entry.get(), builder -> {
						for (ContainerType<?, ?, ?> type : ContainerType.TYPES) {
							type.addDefault(entry, builder);
						}
					});
				}
			}
		}
	}

	private static class ItemSlotListCreator extends BaseContainerCreator<AttachedItems, ComponentBackedInventorySlot> {
		ItemSlotListCreator(List<IBasicContainerCreator<? extends ComponentBackedInventorySlot>> creators) {
			super(creators);
		}

		@Override
		public AttachedItems initStorage(int containers) {
			return AttachedItems.create(containers);
		}
	}
}
