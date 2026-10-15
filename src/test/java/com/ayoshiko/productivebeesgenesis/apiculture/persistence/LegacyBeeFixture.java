package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import net.minecraft.nbt.*;

/** 构造真实旧字段形状；不得仅改版本号让新字段伪装成旧档。 */
public final class LegacyBeeFixture {
	public static void scalar(CompoundTag bee, int schema, boolean controlled) {
		var plan = bee.getCompound("plan");
		if (plan.contains("outputs")) {
			var outputs = plan.getList("outputs", 10); CompoundTag item = null, fluid = null;
			for (var raw : outputs) {
				var output = (CompoundTag) raw;
				if (output.getString("basis").equals("ROLL")) {
					if (item != null || output.getInt("min") != output.getInt("max") || output.getFloat("chance") != 1) throw new IllegalArgumentException("Not a scalar fixture");
					item = output;
				} else { if (fluid != null) throw new IllegalArgumentException("Multiple fluids"); fluid = output; }
			}
			if (item == null || !bee.getCompound("sampling").isEmpty()) throw new IllegalArgumentException("Not a legacy bee");
			plan.put("output", item.getCompound("key").copy()); plan.put("sourceOutput", item.getCompound("source").copy()); plan.putInt("count", item.getInt("min"));
			plan.put("fluidOutput", fluid == null ? new CompoundTag() : fluid.getCompound("key").copy()); plan.putInt("fluidPerCycle", fluid == null ? 0 : fluid.getInt("min"));
			bee.putLong("frozen", 0); bee.putLong("frozenFluid", 0);
			for (var raw : bee.getList("frozenOutputs", 10)) {
				var entry = (CompoundTag) raw; var key = entry.getCompound("key");
				if (key.equals(plan.getCompound("output"))) bee.put("frozen", entry.get("amount").copy());
				else if (key.equals(plan.getCompound("fluidOutput"))) bee.put("frozenFluid", entry.get("amount").copy());
				else throw new IllegalArgumentException("Foreign legacy balance");
			}
			plan.remove("outputs"); bee.remove("frozenOutputs"); bee.remove("sampling");
		}
		if (!controlled || schema < 9) bee.remove("enabled");
		if (schema < 10) { bee.remove("frozenFluid"); plan.remove("fluidOutput"); plan.remove("fluidPerCycle"); }
		if (schema < 8) plan.remove("sourceOutput");
		if (schema == 6) { bee.remove("seed"); bee.remove("cursor"); plan.remove("multiplier"); }
	}
	private LegacyBeeFixture() { }
}
