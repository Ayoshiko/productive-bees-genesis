package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import cy.jdkdigital.productivebees.init.ModTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class ByproductTagSnapshotMinecraftTest {
	@Test
	void tagReloadReplacesMembershipAndEmptyStacksRemainRejected() {
		var original = BuiltInRegistries.ITEM.getTags().collect(Collectors.toMap(
				entry -> entry.getFirst(), entry -> entry.getSecond().stream().toList()));
		try {
			var tags = new HashMap<>(original);
			tags.put(ModTags.Common.WAXES, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.DIAMOND)));
			BuiltInRegistries.ITEM.bindTags(tags);
			UselessByproductUpgradeHelper.invalidateCache();
			for (int i = 0; i < 256; i++) {
				assertTrue(UselessByproductUpgradeHelper.isWax(new ItemStack(Items.DIAMOND)));
				assertFalse(UselessByproductUpgradeHelper.isWax(new ItemStack(Items.EMERALD)));
			}
			tags.put(ModTags.Common.WAXES, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.EMERALD)));
			BuiltInRegistries.ITEM.bindTags(tags);
			UselessByproductUpgradeHelper.invalidateCache();
			assertFalse(UselessByproductUpgradeHelper.isWax(new ItemStack(Items.DIAMOND)));
			assertTrue(UselessByproductUpgradeHelper.isWax(new ItemStack(Items.EMERALD)));
			assertFalse(UselessByproductUpgradeHelper.isWax(ItemStack.EMPTY));
		} finally {
			BuiltInRegistries.ITEM.bindTags(original);
			UselessByproductUpgradeHelper.invalidateCache();
		}
	}
}
