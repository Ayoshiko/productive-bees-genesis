package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;

import com.jerry.mekextras.common.block.attribute.ExtraAttributeTier;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;

import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeTier;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.api.text.EnumColor;
import mekanism.api.text.TextComponentUtil;
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
import net.minecraft.network.chat.TextColor;
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
		// EME工厂使用EMExtraAttributeFactoryType — 仅在 EME 已加载时检查，避免 NoClassDefFoundError
		else if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()
				&& Attribute.has(block, EMExtraAttributeFactoryType.class)) {
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
	 * 获取物品名称 — 为ME和EME等级添加颜色特效
	 * <br/>
	 * 原理：原版Mekanism工厂通过getTier()返回的BaseTier获取颜色。
	 * ME和EME使用独立的等级系统，需要在此方法中手动添加颜色。
	 * <p>
	 * 颜色对应：
	 * <ul>
	 *   <li>ME ABSOLUTE: 黄绿色 (237, 238, 70)</li>
	 *   <li>ME SUPREME: 红色 (166, 0, 2)</li>
	 *   <li>ME COSMIC: 青色 (75, 248, 255)</li>
	 *   <li>ME INFINITE: 品红色 (247, 135, 255)</li>
	 *   <li>EME ABSOLUTE_OVERCLOCKED: 动态黄绿色</li>
	 *   <li>EME SUPREME_QUANTUM: 动态红色</li>
	 *   <li>EME COSMIC_DENSE: 动态青色</li>
	 *   <li>EME INFINITE_MULTIVERSAL: 动态品红色</li>
	 * </ul>
	 */
	@NotNull
	@Override
	public Component getName(@NotNull ItemStack stack) {
		// 检查ME等级（Mekanism Extras）
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			ExtraAttributeTier<ExtraFactoryTier> meTier = Attribute.get(getBlock(), ExtraAttributeTier.class);
			if (meTier != null) {
				TextColor color = meTier.tier().getAdvanceTier().getColor();
				return TextComponentUtil.build(color, super.getName(stack));
			}
		}

		// 检查EME等级（Evolved Mekanism Extras）
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			EMExtraAttributeTier<EMExtraFactoryTier> emeTier = Attribute.get(getBlock(), EMExtraAttributeTier.class);
			if (emeTier != null) {
				TextColor color = TextColor.fromRgb(emeTier.tier().getEMExtraTier().getRgbSupplier().getAsInt());
				return TextComponentUtil.build(color, super.getName(stack));
			}
		}

		// 原版/EM等级使用默认行为（通过getTier()获取颜色）
		return super.getName(stack);
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
		// EME工厂 — 仅在 EME 已加载时检查，避免 NoClassDefFoundError
		else if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
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