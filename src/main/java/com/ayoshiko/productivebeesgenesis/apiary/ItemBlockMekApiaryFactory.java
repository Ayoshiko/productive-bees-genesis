package com.ayoshiko.productivebeesgenesis.apiary;

import com.jerry.mekextras.common.block.attribute.ExtraAttributeTier;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;

import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeTier;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;

import mekanism.api.text.TextComponentUtil;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.tier.FactoryTier;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.NotNull;

/**
 * 工厂版通用机械蜂箱 BlockItem
 * <br/>
 * 继承 {@link ItemBlockMekApiary}（而非直接继承 ItemBlockTooltip），
 * 共享基础版的 tooltip 防御性修复（Bug 7：addTypeDetails/addDetails 的 try-catch 包装），
 * 额外添加 {@link MekanismDataComponents#SORTING} 组件以支持工厂排序开关。
 * <p>
 * 与离心机工厂 {@code ItemBlockMekCentrifuge} 不同：
 * 蜂箱工厂使用 {@code createMachine()} 而非 {@code createFactoryMachine()}，
 * 因此 BlockType 没有 AttributeFactoryType，无法通过 Attribute 检查区分工厂。
 * 本类直接为所有蜂箱工厂方块添加 SORTING 组件。
 * <p>
 * 重写 {@link #getTier()} 返回 {@link FactoryTier}，供 MEK 原版名称颜色渲染使用
 * （Basic 绿/Advanced 橙/Elite 青/Ultimate 品红，颜色来自 BaseTier 源码）。
 */
public class ItemBlockMekApiaryFactory extends ItemBlockMekApiary {

	public ItemBlockMekApiaryFactory(MekApiaryBlock<?, ?> block, Item.Properties properties) {
		super(block, addSortingComponent(properties));
	}

	/**
	 * 添加 SORTING DataComponent
	 * <br/>
	 * SORTING 必须在此处注册默认值，否则拆下机器时组件无法序列化到 ItemStack 导致 Shift tooltip 崩溃。
	 * 蜂箱工厂无 AttributeFactoryType，直接添加（所有蜂箱工厂方块都需要排序功能）。
	 */
	private static Item.Properties addSortingComponent(Item.Properties properties) {
		properties.component(MekanismDataComponents.SORTING, false);
		return properties;
	}

	/**
	 * 获取工厂等级 — 用于物品名称颜色渲染
	 * <br/>
	 * 原理：ItemBlockMekanism.getName() 根据 tier 的 BaseTier 颜色渲染物品名称。
	 * 颜色来自 {@link mekanism.api.tier.BaseTier} 源码：
	 * Basic(95,255,184)/Advanced(255,128,106)/Elite(75,248,255)/Ultimate(247,135,255)。
	 */
	@Override
	public FactoryTier getTier() {
		return Attribute.getTier(getBlock(), FactoryTier.class);
	}

	/**
	 * 获取物品名称 — 为 ME 和 EME 等级添加颜色特效
	 * <br/>
	 * 原理：原版 Mekanism 工厂通过 {@code getTier()} 返回的 BaseTier 颜色渲染物品名称。
	 * ME 和 EME 使用独立的等级系统（无 FactoryTier），需要在此方法手动添加颜色。
	 * <p>
	 * 实现与离心机 {@link com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge#getName} 一致：
	 * <ul>
	 *   <li>ME 等级（ABSOLUTE/SUPREME/COSMIC/INFINITE）：通过 {@link ExtraAttributeTier} 获取颜色</li>
	 *   <li>EME 等级（ABSOLUTE_OVERCLOCKED 等）：通过 {@link EMExtraAttributeTier} 获取颜色</li>
	 *   <li>原版 4 等级：使用默认行为（通过 {@link #getTier()} 获取颜色）</li>
	 * </ul>
	 * <p>
	 * 守卫检查：所有 ME/EME 类引用必须用 {@code isXxxLoaded()} 守卫，
	 * 避免模组未加载时触发 NoClassDefFoundError。
	 */
	@NotNull
	@Override
	public Component getName(@NotNull ItemStack stack) {
		// 检查 ME 等级（Mekanism Extras）— 守卫避免 ME 未加载时引用 ME 类
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			ExtraAttributeTier<ExtraFactoryTier> meTier = Attribute.get(getBlock(), ExtraAttributeTier.class);
			if (meTier != null) {
				TextColor color = meTier.tier().getAdvanceTier().getColor();
				return TextComponentUtil.build(color, super.getName(stack));
			}
		}

		// 检查 EME 等级（Evolved Mekanism Extras）— 守卫避免 EME 未加载时引用 EME 类
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			EMExtraAttributeTier<EMExtraFactoryTier> emeTier = Attribute.get(getBlock(), EMExtraAttributeTier.class);
			if (emeTier != null) {
				TextColor color = TextColor.fromRgb(emeTier.tier().getEMExtraTier().getRgbSupplier().getAsInt());
				return TextComponentUtil.build(color, super.getName(stack));
			}
		}

		// 原版 4 等级使用默认行为（通过 getTier() 获取 BaseTier 颜色）
		return super.getName(stack);
	}
}
