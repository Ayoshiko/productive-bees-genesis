package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.jerry.mekextras.common.block.attribute.ExtraAttributeTier;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeTier;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
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
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.minecraft.core.registries.Registries;

/**
 * MEK离心机ContainerType默认创建器注册器
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
		// 可选依赖守卫：ME/EME 类字面量在对应模组未加载时会触发 NoClassDefFoundError，
		// 必须用 isXxxLoaded() 守卫将类引用包裹在条件块内，JVM 不会加载未到达路径中的类。
		int processes = 0;
		FactoryTier factoryTier = Attribute.getTier(block, FactoryTier.class);
		if (factoryTier != null) {
			processes = factoryTier.processes;
		} else {
			if (MekCompatHooks.isMekanismExtrasLoaded()) {
				ExtraAttributeTier<?> extraAttrTier = Attribute.get(block, ExtraAttributeTier.class);
				if (extraAttrTier != null && extraAttrTier.tier() instanceof ExtraFactoryTier eft) {
					processes = eft.processes;
				}
			}
			if (processes == 0 && MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
				EMExtraAttributeTier<?> emeAttrTier = Attribute.get(block, EMExtraAttributeTier.class);
				if (emeAttrTier != null && emeAttrTier.tier() instanceof EMExtraFactoryTier emeft) {
					processes = emeft.processes;
				}
			}
		}

		if (processes > 0) {
			int finalProcesses = processes;
			ContainerType.ITEM.addDefaultCreators(null, item, () ->
					buildFactoryItemSlots(finalProcesses));
		} else {
			ContainerType.ITEM.addDefaultCreators(null, item, MekCentrifugeContainerRegistrar::buildCentrifugeItemSlots);
		}

		ContainerType.FLUID.addDefaultCreators(null, item, () ->
				FluidTanksBuilder.builder()
						.addBasic(10000)
						.build());
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
				(stack, automationType) -> automationType == AutomationType.MANUAL
						|| !EnergyCompatUtils.hasStrictEnergyHandler(stack)
						&& EnergyInventorySlot.getPotentialConversion(null, stack) == 0L,
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