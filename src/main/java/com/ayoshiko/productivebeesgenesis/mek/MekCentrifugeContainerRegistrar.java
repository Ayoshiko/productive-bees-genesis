package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.inventory.CentrifugeFluidTankMultipliers;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
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
	 * MEK离心机ContainerType默认创建器注册器
	 * <br/>
	 * {@code RegisterEvent} 是模组总线事件，NeoForge 1.21.1 自动判定总线，
	 * 不显式声明 {@code bus} 参数（该参数在 1.21.1 已过时）。
	 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public class MekCentrifugeContainerRegistrar {

	private MekCentrifugeContainerRegistrar() {}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void registerContainers(RegisterEvent event) {
		if (!event.getRegistryKey().equals(Registries.ITEM)) {
			return;
		}
		for (var entry : ModItems.ITEMS.getEntries()) {
			Item item = entry.get();
			if (item instanceof ItemBlockMekCentrifuge mekItem) {
				registerContainerCreators(mekItem);
			}
		}
	}

	private static void registerContainerCreators(ItemBlockMekCentrifuge item) {
		MekCentrifugeBlock<?, ?> block = item.getBlock();

		if (Attribute.has(block, AttributeEnergy.class)) {
			ContainerType.ENERGY.addDefaultCreators(null, item, () ->
					item.buildDefaultEnergyContainers(EnergyContainersBuilder.builder()).build(),
					MekanismConfig.storage, MekanismConfig.usage);
		}

		// ITEM — 根据工厂类型获取进程数
		// 可选依赖隔离：ME/EME 类引用封装在 compat 包的辅助类中，
		// 通过 isXxxLoaded() 守卫调用，本类不直接 import ME/EME 类，实现软依赖完全隔离。
		int processes = getProcesses(block);

		if (processes > 0) {
			int finalProcesses = processes;
			ContainerType.ITEM.addDefaultCreators(null, item, () ->
					buildFactoryItemSlots(finalProcesses));
		} else {
			ContainerType.ITEM.addDefaultCreators(null, item, MekCentrifugeContainerRegistrar::buildCentrifugeItemSlots);
		}

		boolean factory = processes > 0;
		int tankCount = factory ? processes : 1;
		ContainerType.FLUID.addDefaultCreators(null, item, () -> {
			FluidTanksBuilder builder = FluidTanksBuilder.builder();
			for (int tank = 0; tank < tankCount; tank++) {
				// 工厂拆卸组件包含全部并行罐；创建器也必须覆盖空罐及第二个以上的罐。
				// 单罐旧物品仍按自身附件长度读取，不需要重写原有保存数据。
				builder.addBasic(() -> getFluidCapacity(block));
			}
			return builder.build();
		});
	}

	/** 物品创建器覆盖最大保存布局，合成结果则采用当前服务器模式的可放置布局。 */
	public static int getCraftingFluidTankCount(ItemBlockMekCentrifuge item) {
		int processes = getProcesses(item.getBlock());
		return processes > 0 && ModConfig.SERVER.mekCentrifugeMultiFluidTank.get() ? processes : 1;
	}

	private static int getProcesses(MekCentrifugeBlock<?, ?> block) {
		FactoryTier tier = Attribute.getTier(block, FactoryTier.class);
		if (tier != null) {
			return tier.processes;
		}
		int processes = 0;
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			processes = MEContainerSlotHelper.getProcesses(block);
		}
		if (processes == 0 && MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			processes = EMEContainerSlotHelper.getProcesses(block);
		}
		return processes;
	}

	private static int getFluidCapacity(MekCentrifugeBlock<?, ?> block) {
		int processes = getProcesses(block);
		int baseCapacity = ModConfig.SERVER.mekCentrifugeFluidTankCapacity.get();
		if (processes == 0) {
			return baseCapacity;
		}
		if (ModConfig.SERVER.mekCentrifugeMultiFluidTank.get()) {
			return Integer.MAX_VALUE;
		}
		int multiplier;
		FactoryTier tier = Attribute.getTier(block, FactoryTier.class);
		if (tier != null) {
			if (tier.ordinal() >= 4 && MekCompatHooks.isEvolvedMekanismLoaded()) {
				multiplier = CentrifugeFluidTankMultipliers.forEMFactory(tier.ordinal() - 4).getAsInt();
			} else {
				multiplier = CentrifugeFluidTankMultipliers.forVanillaFactory(tier.ordinal()).getAsInt();
			}
		} else if (MekCompatHooks.isMekanismExtrasLoaded() && MEContainerSlotHelper.getProcesses(block) > 0) {
			multiplier = MEContainerSlotHelper.getCentrifugeFluidMultiplier(block);
		} else {
			multiplier = EMEContainerSlotHelper.getCentrifugeFluidMultiplier(block);
		}
		return SaturatingMath.saturatingToInt(SaturatingMath.saturatingMultiply(baseCapacity, processes, multiplier));
	}

	private static ItemSlotListCreator buildCentrifugeItemSlots() {
		return new ItemSlotListCreator(List.of(
				(type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
						ConstantPredicates.notExternal(), ConstantPredicates.alwaysTrueBi(), ConstantPredicates.alwaysTrue()),
				(type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
						ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(), ConstantPredicates.alwaysTrue()),
				(type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
						ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(), ConstantPredicates.alwaysTrue()),
				(type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
						ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(), ConstantPredicates.alwaysTrue()),
				createEnergySlotCreator()
		));
	}

	private static ItemSlotListCreator buildFactoryItemSlots(int processes) {
		List<IBasicContainerCreator<? extends ComponentBackedInventorySlot>> creators = new ArrayList<>();

		for (int i = 0; i < processes; i++) {
			creators.add((type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
					ConstantPredicates.notExternal(), ConstantPredicates.alwaysTrueBi(), ConstantPredicates.alwaysTrue()));
			creators.add((type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
					ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(), ConstantPredicates.alwaysTrue()));
			creators.add((type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
					ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(), ConstantPredicates.alwaysTrue()));
			creators.add((type, attachedTo, containerIndex) -> new ComponentBackedInventorySlot(attachedTo, containerIndex,
					ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(), ConstantPredicates.alwaysTrue()));
		}

		creators.add(createEnergySlotCreator());

		return new ItemSlotListCreator(creators);
	}

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
			if (item instanceof ItemBlockMekCentrifuge) {
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
