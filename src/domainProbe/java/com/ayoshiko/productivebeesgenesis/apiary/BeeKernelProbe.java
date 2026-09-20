package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.MachineCapacityReader;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.WorkKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;

/** 真实注册表与物理生产适配器的隔离验证；不启动网络生产或外部物流。 */
public final class BeeKernelProbe {
	public static void verify(ServerLevel level, JsonObject report) {
		quantityBoundary(level, report);
		var pos = new BlockPos(12, 100, 12);
		var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary"));
		level.setBlockAndUpdate(pos, block.defaultBlockState());
		try {
			var hive = (TileEntityMekApiary) level.getBlockEntity(pos);
			hive.setDirectEjectEnabled(false); hive.setDirectAeOutputEnabled(false);
			hive.setDirectContainerOutputEnabled(false); hive.setCentrifugePriorityEnabled(false);
			var beeType = ResourceLocation.parse("productivebees:iron");
			for (int i = 0; i < 2; i++) {
				var data = new CompoundTag(); data.putString("id", "productivebees:configurable_bee");
				data.putString("type", beeType.toString()); data.putUUID("UUID", UUID.randomUUID());
				var traits = new CompoundTag(); traits.putString("bee_behavior", "behavior.metaturnal");
				traits.putString("bee_weather_tolerance", "weather_tolerance.any");
				traits.putString("bee_productivity", i == 0 ? "productivity.normal" : "productivity.very_high");
				var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", traits);
				data.put("neoforge:attachments", attachments);
				hive.getBeeSlot(i).setBeeData(data); hive.getBeeSlot(i).setBaseMinOccupationTicks(5);
			}
			hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK));
			require(hive.feederSlotManager.hasValidFlower(beeType), "Iron bee feeder fixture invalid");
			var produce = hive.produceProcessor.getCachedProduce(beeType, level, hive.feederSlotManager);
			require(produce.size() == 1 && produce.values().stream().allMatch(value -> value.min() == 1 && value.max() == 1 && value.chance() == 1), "Expected deterministic iron bee recipe");
			var outputKey = ProductKeyCodec.item(produce.keySet().iterator().next(), level.registryAccess());
			var work = new WorkKey(WorkKey.Kind.BEE_CYCLE, beeType.toString(), 1, "d13-probe");
			var capacity = MachineCapacityReader.apiary(hive, UUID.randomUUID(), 1,
					List.of(new MachineCapacityReader.BeeCycleQuery(work, 5))).alternatives().getFirst();
			require(capacity.cycleTicks() == 5, "Unexpected base fixture cycle time");
			hive.energyContainer().setEnergy(hive.energyContainer().getMaxEnergy());
			long energyBefore = hive.energyContainer().getEnergy(), unitCost = hive.energyContainer().getEnergyPerTick();
			var slots = hive.getSlotManager();
			var processor = new BeeSlotTickProcessor(hive, slots, hive.produceProcessor, hive.upgradeHandler,
					hive.feederSlotManager, new ApiaryBeeActivationCounter(hive.getBeeSlotCount()), new LogThrottle(),
					new ApiaryConversionProcessor(hive, slots, hive.feederSlotManager));
			for (int tick = 0; tick < 10; tick++) processor.tick();
			long count = 0;
			for (var slot : hive.getOutputSlots()) if (!slot.isEmpty()) {
				require(ProductKeyCodec.item(slot.getStack(), level.registryAccess()).equals(outputKey), "Physical result identity changed");
				count += slot.getCount();
			}
			require(count == 10, "Mixed genes must produce 2 normal + 8 very high combs; got " + count);
			require(energyBefore - hive.energyContainer().getEnergy() == unitCost * 20, "Physical energy debit differs from paid bee ticks");
			require(hive.getBeeSlot(0).getTicksInHive() == 0 && hive.getBeeSlot(1).getTicksInHive() == 0, "Physical cycle remainder changed");
			require(hive.getFeederSlots().getFirst().getCount() == 1, "Nonconsumable flower sample changed");
			var pending = new CompoundTag(); processor.savePendingCycles(pending);
			require(pending.isEmpty(), "Completed physical batch remained pending");
			report.addProperty("beeKernelPhysicalIronMixedGenes", true);
			report.addProperty("beeKernelPhysicalCombCount", count);
			report.addProperty("beeKernelPhysicalEnergyDebit", unitCost * 20);
		} finally { level.removeBlock(pos, false); }
	}
	private static void quantityBoundary(ServerLevel level, JsonObject report) {
		var template = new ItemStack(Items.IRON_INGOT);
		template.set(DataComponents.CUSTOM_NAME, Component.literal("d13 component identity"));
		var key = ProductKeyCodec.item(template, level.registryAccess());
		var outputs = Map.of(template, new ChancedOutput(Ingredient.of(Items.IRON_INGOT), Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
		var privateResults = new PagedProductAmounts();
		for (int i = 0; i < 3; i++) BeeProduceBatchSampler.sampleAmounts((item, count) -> {
			var actual = ProductKeyCodec.item(item, level.registryAccess());
			privateResults.set(actual, privateResults.amount(actual).add(ProductAmount.of(count)));
		}, outputs, Integer.MAX_VALUE, 1, 0, 0, new Random(13));
		var expected = BigInteger.valueOf(Integer.MAX_VALUE).pow(2).multiply(BigInteger.valueOf(3));
		require(privateResults.size() == 1 && privateResults.amount(key).exact().equals(expected), "Quantity aggregation truncated before physical boundary");
		var projected = new ArrayList<ItemStack>();
		BeeProduceBatchSampler.sampleInto(projected, outputs, Integer.MAX_VALUE, 1, 0, 0);
		require(projected.size() == 1 && projected.getFirst().getCount() == Integer.MAX_VALUE, "Legacy physical projection changed");
		require(template.getCount() == 1 && key.equals(ProductKeyCodec.item(template, level.registryAccess())), "Sampling mutated recipe template");
		report.addProperty("beeKernelExactAggregatedAmount", expected.toString());
		report.addProperty("beeKernelQuantityCallbacks", 3);
		report.addProperty("beeKernelPhysicalProjectionAndTemplate", true);
	}
	private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
	private BeeKernelProbe() { }
}
