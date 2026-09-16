package com.ayoshiko.productivebeesgenesis.compat.emextras;

import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeFactoryType;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeTier;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.api.text.EnumColor;
import mekanism.api.text.TextComponentUtil;
import mekanism.common.MekanismLang;
import mekanism.common.block.attribute.Attribute;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Evolved Mekanism Extras item metadata support, isolated from always-loaded item classes. */
public final class EMEFactoryItemSupport {

	private EMEFactoryItemSupport() {
	}

	public static boolean hasFactoryType(Block block) {
		return Attribute.has(block, EMExtraAttributeFactoryType.class);
	}

	@Nullable
	public static Component colorizeName(Block block, Component baseName) {
		EMExtraAttributeTier<EMExtraFactoryTier> tier = Attribute.get(block, EMExtraAttributeTier.class);
		if (tier == null) return null;
		TextColor color = TextColor.fromRgb(tier.tier().getEMExtraTier().getRgbSupplier().getAsInt());
		return TextComponentUtil.build(color, baseName);
	}

	public static boolean appendFactoryType(Block block, List<Component> tooltip) {
		EMExtraAttributeFactoryType factoryType = Attribute.get(block, EMExtraAttributeFactoryType.class);
		if (factoryType == null) return false;
		tooltip.add(MekanismLang.FACTORY_TYPE.translateColored(
				EnumColor.INDIGO, EnumColor.GRAY, factoryType.getFactoryType()));
		return true;
	}
}
