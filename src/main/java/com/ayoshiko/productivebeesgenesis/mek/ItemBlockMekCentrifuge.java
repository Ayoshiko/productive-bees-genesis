package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;

import mekanism.api.text.EnumColor;
import mekanism.common.MekanismLang;
import mekanism.common.attachments.containers.energy.EnergyContainersBuilder;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeFactoryType;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeFactoryType;
import mekanism.common.block.interfaces.IHasDescription;
import mekanism.common.item.block.ItemBlockTooltip;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.tier.FactoryTier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

/**
 * MEK离心机BlockItem — 一比一复制Mekanism原版tooltip系统
 * <br/>
 * 继承ItemBlockTooltip，实现与Mekanism原版机器完全一致的tooltip行为：
 * <ul>
 *   <li>默认：显示统计信息 + "按住Shift查看详情" + "按住Shift+N查看描述"</li>
 *   <li>按住Shift：显示拥有者、安全等级、配方类型、已储能、存有物品、升级</li>
 *   <li>按住Shift+N：显示方块描述</li>
 * </ul>
 * 工厂版额外添加SORTING DataComponent和配方类型显示。
 */
public class ItemBlockMekCentrifuge extends ItemBlockTooltip<MekCentrifugeBlock<?, ?>> {

	public ItemBlockMekCentrifuge(MekCentrifugeBlock<?, ?> block, Item.Properties properties) {
		super(block, true, addFactoryComponents(block, properties));
	}

	/**
	 * 为工厂方块添加默认DataComponents
	 * <br/>
	 * 为工厂方块添加SORTING DataComponent（工厂自动排序开关）。
	 * EJECTOR和SIDE_CONFIG已在machineItemProperties中设置，此处不再重复添加，
	 * 避免覆盖离心机专用侧面配置（MEK_CENTRIFUGE_SIDE_CONFIG包含流体右侧输出）。
	 * SORTING必须在此处注册默认值，否则拆下机器时组件无法序列化到ItemStack导致Shift tooltip崩溃。
	 */
	private static Item.Properties addFactoryComponents(MekCentrifugeBlock<?, ?> block, Item.Properties properties) {
		// 原版/EM/ME工厂使用AttributeFactoryType
		if (Attribute.has(block, AttributeFactoryType.class)) {
			properties.component(MekanismDataComponents.SORTING, false);
			// EJECTOR和SIDE_CONFIG已在machineItemProperties中设置，此处不再覆盖
			// 避免覆盖离心机专用侧面配置（MEK_CENTRIFUGE_SIDE_CONFIG包含流体右侧输出）
		}
		// EME工厂使用EMExtraAttributeFactoryType
		else if (Attribute.has(block, EMExtraAttributeFactoryType.class)) {
			properties.component(MekanismDataComponents.SORTING, false);
			// EJECTOR和SIDE_CONFIG已在machineItemProperties中设置，此处不再覆盖
		}
		return properties;
	}

	/**
	 * 获取工厂等级 — 用于物品名称颜色渲染
	 * <br/>
	 * 原理：ItemBlockMekanism.getName()根据tier的BaseTier颜色渲染物品名称。
	 * 返回null时使用默认颜色。仅原版/EM工厂有FactoryTier，
	 * ME/EME工厂使用独立等级系统，此处返回null。
	 */
	@Override
	public FactoryTier getTier() {
		return Attribute.getTier(getBlock(), FactoryTier.class);
	}

	/**
	 * 添加类型特定详情 — 显示配方类型
	 * <br/>
	 * 原理：一比一复制ItemBlockFactory.addTypeDetails()，
	 * 从AttributeFactoryType读取工厂的配方类型（如Smelting），
	 * 显示为"Recipe type: Smelting"。
	 */
	@Override
	protected void addTypeDetails(@NotNull ItemStack stack, @NotNull Item.TooltipContext context,
								   @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
		// 原版/EM/ME工厂
		AttributeFactoryType factoryType = Attribute.get(getBlock(), AttributeFactoryType.class);
		if (factoryType != null) {
			tooltip.add(MekanismLang.FACTORY_TYPE.translateColored(
					EnumColor.INDIGO, EnumColor.GRAY, factoryType.getFactoryType()));
		}
		// EME工厂
		else {
			EMExtraAttributeFactoryType emeFactoryType = Attribute.get(getBlock(), EMExtraAttributeFactoryType.class);
			if (emeFactoryType != null) {
				tooltip.add(MekanismLang.FACTORY_TYPE.translateColored(
						EnumColor.INDIGO, EnumColor.GRAY, emeFactoryType.getFactoryType()));
			}
		}
		super.addTypeDetails(stack, context, tooltip, flag);
	}

	/** 公开父类protected方法，供MekCentrifugeContainerRegistrar调用 */
	public EnergyContainersBuilder buildDefaultEnergyContainers(EnergyContainersBuilder builder) {
		return addDefaultEnergyContainers(builder);
	}
}