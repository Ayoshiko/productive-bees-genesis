package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** PB 13.13.5 的单笼数据投影；不调用实体捕捉／放出／背包投掷回调。 */
public final class VerifiedCageProjection {
	/** 只支持一个真实普通／加固蜂笼，拒绝堆叠与其它自定义容器。 */
	public static boolean supported(ItemStack cage) {
		return cage.getCount() == 1 && (cage.is(ModItems.BEE_CAGE.get()) || cage.is(ModItems.STURDY_BEE_CAGE.get()));
	}

	/** 从装蜂笼读取完整数据；格式错误时拒绝，不能自动补出一只默认蜜蜂。 */
	public static CompoundTag contents(ItemStack cage) {
		if (!supported(cage)) throw new IllegalArgumentException("A single PB cage is required");
		var custom = cage.get(DataComponents.CUSTOM_DATA);
		if (custom == null) throw new IllegalArgumentException("Empty bee cage");
		var data = custom.copyTag();
		if (!data.contains("entity", Tag.TAG_STRING) || data.getString("entity").isBlank()
				|| data.contains("id") && (!data.contains("id", Tag.TAG_STRING)
				|| !data.getString("entity").equals(data.getString("id")))) {
			throw new IllegalArgumentException("Invalid or conflicting bee entity identity");
		}
		return data;
	}

	/** 单个空笼接收蜜蜂；保留其它物品组件，未知空笼 CUSTOM_DATA 不覆盖。 */
	public static ItemStack fill(ItemStack cage, BeeRecord bee) {
		if (!supported(cage)) throw new IllegalArgumentException("A single PB cage is required");
		var custom = cage.get(DataComponents.CUSTOM_DATA);
		if (custom != null && !custom.copyTag().isEmpty()) throw new IllegalArgumentException("Cage is not empty");
		var data = bee.originalSlot().copy().getCompound("entity_data");
		if (!data.contains("entity")) {
			if (!data.contains("id", Tag.TAG_STRING) || data.getString("id").isBlank()) {
				throw new IllegalArgumentException("Missing preserved bee entity identity");
			}
			data.putString("entity", data.getString("id"));
		}
		var filled = cage.copy();
		filled.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		contents(filled);
		return filled;
	}

	/** 普通笼消耗；加固笼原槽返还并保留非实体组件，不保留第二份蜜蜂数据。 */
	public static ItemStack afterRelease(ItemStack cage) {
		contents(cage);
		if (!cage.is(ModItems.STURDY_BEE_CAGE.get())) return ItemStack.EMPTY;
		var empty = cage.copy();
		empty.remove(DataComponents.CUSTOM_DATA);
		return empty;
	}

	private VerifiedCageProjection() { }
}
