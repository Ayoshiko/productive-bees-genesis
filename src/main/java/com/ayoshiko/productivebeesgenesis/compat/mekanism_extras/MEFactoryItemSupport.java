package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.jerry.mekextras.common.block.attribute.ExtraAttributeTier;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import mekanism.api.text.TextComponentUtil;
import mekanism.common.block.attribute.Attribute;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/** Mekanism Extras item-name support, isolated from always-loaded item classes. */
public final class MEFactoryItemSupport {

	private MEFactoryItemSupport() {
	}

	@Nullable
	public static Component colorizeName(Block block, Component baseName) {
		ExtraAttributeTier<ExtraFactoryTier> tier = Attribute.get(block, ExtraAttributeTier.class);
		if (tier == null) return null;
		TextColor color = tier.tier().getAdvanceTier().getColor();
		return TextComponentUtil.build(color, baseName);
	}
}
