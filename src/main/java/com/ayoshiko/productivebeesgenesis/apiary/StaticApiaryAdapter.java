package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import java.util.ArrayList;
import mekanism.api.Upgrade;
import mekanism.api.SerializerHelper;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 只读封存数据和成员原位置；不构造隐藏机器，不执行物理生产、喂食或输出。 */
public final class StaticApiaryAdapter {
	private static final ResourceLocation IRON = ResourceLocation.parse("productivebees:iron");
	public static BeeMemberState compile(ServerLevel level, TileEntityMekApiary hive, OwnedMachineRecord record, long recipeRevision, long capabilityRevision) {
		if (!level.getServer().isSameThread() || hive.getClass() != TileEntityMekApiary.class
				|| record.phase() != OwnedMachineRecord.Phase.OWNED || record.bees() != null) throw new IllegalArgumentException("Only sealed basic apiaries are supported");
		var image = record.assets().copy(); var extra = image.getCompound("extra");
		if (!extra.contains(ApiaryNbtSerializer.NBT_KEY_FEEDER_CONVERSION, Tag.TAG_BYTE) || extra.getBoolean(ApiaryNbtSerializer.NBT_KEY_FEEDER_CONVERSION))
			throw new IllegalArgumentException("Disable feeder conversion before static iron production");
		if (!Upgrade.buildMap(image.getCompound("upgrades")).isEmpty() || !extra.getCompound(ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS).isEmpty())
			throw new IllegalArgumentException("Production upgrades require a dedicated network adapter");
		var counts = PendingBeeCycles.read(extra, 3).counts();
		var source = extra.getList(BeeAssetProjection.SLOTS, Tag.TAG_COMPOUND); var bees = new ArrayList<BeeRecord>();
		for (var raw : source) {
			var slot = (CompoundTag) raw; int index = slot.getInt("slot_index"); var data = slot.getCompound("entity_data");
			if (BeeNbtHelper.resolveEntityType(data) != cy.jdkdigital.productivebees.init.ModEntities.CONFIGURABLE_BEE.get() || !IRON.equals(BeeNbtHelper.resolveBeeTypeKey(data))
					|| data.getBoolean("HasConverted") || index < 0 || index >= 3)
				throw new IllegalArgumentException("Only unconverted static iron bees are supported");
			var pref = BeeInfoHelper.getFlowerPreference(IRON);
			if (!BeeInfoHelper.FlowerPreference.TYPE_BLOCKS.equals(pref.flowerType()) || !pref.hasFlowerDefinition()) throw new IllegalArgumentException("Unsupported iron bee flower definition");
			var holder = level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get()).stream()
					.filter(value -> value.value().ingredient.get() != null && IRON.equals(value.value().ingredient.get().getBeeType())).findFirst().orElseThrow();
			var outputs = holder.value().getRecipeOutputs();
			if (outputs.size() != 1) throw new IllegalArgumentException("Static iron adapter requires exactly one output");
			var entry = outputs.entrySet().iterator().next(); var output = entry.getValue();
			if (entry.getKey().isEmpty() || output.chance() != 1 || output.min() < 1 || output.min() != output.max()) throw new IllegalArgumentException("Random output requires a dedicated adapter");
			var key = ProductKeyCodec.item(entry.getKey(), level.registryAccess());
			var plan = new StaticBeePlan(IRON.toString(), holder.id().toString(), recipeRevision, capabilityRevision,
					BeeProgressPlan.cycleTicks(slot.getInt("base_min_occupation_ticks"), ModConfig.SERVER.apiaryProcessingTime.get(), 1, false),
					hive.energyContainer().getEnergyPerTick(), BeeProductivityGene.readLevel(data), BalanceConfig.apiaryBeeGenesAffectWork(),
					BeeWorkConditionEvaluator.readTraits(data), key, output.min());
			bees.add(new BeeRecord(BeeRecord.identity(record.claim().member(), index), record.claim().member(), index,
					new AssetImage(slot), plan, 0, slot.getInt("ticks_in_hive"), counts[index], ProductAmount.ZERO));
		}
		for (int i = 0; i < counts.length; i++) {
			int slot = i;
			if (counts[i] > 0 && bees.stream().noneMatch(bee -> bee.slot() == slot)) throw new IllegalArgumentException("Orphaned paid bee cycles");
		}
		return new BeeMemberState(record.claim().member(), 0, image.getLong("energy"), image.getLong("energyCapacity"), bees);
	}
	public static boolean flower(ServerLevel level, AssetImage residual) {
		var extra = residual.copy().getCompound("extra");
		var slots = extra.getList("productivebeesgenesis_feeder_slots", Tag.TAG_COMPOUND);
		long[] disabled = extra.getLongArray("productivebeesgenesis_feeder_disabled");
		var pref = BeeInfoHelper.getFlowerPreference(IRON);
		for (int i = 0; i < slots.size(); i++) {
			if (i / 64 < disabled.length && (disabled[i / 64] & 1L << (i % 64)) != 0) continue;
			var stack = SerializerHelper.parseOversizedOptional(level.registryAccess(), slots.getCompound(i).getCompound("item"));
			if (!stack.isEmpty() && BlockFlowerMatcher.matches(stack, pref)) return true;
		}
		return false;
	}
	public static boolean currentPlan(ServerLevel level, TileEntityMekApiary hive, BeeRecord bee) {
		var plan = bee.plan(); var pref = BeeInfoHelper.getFlowerPreference(IRON);
		if (hive.isFeederConversionEnabled() || !BeeInfoHelper.FlowerPreference.TYPE_BLOCKS.equals(pref.flowerType())
				|| !pref.hasFlowerDefinition() || plan.genesAffectWork() != BalanceConfig.apiaryBeeGenesAffectWork()
				|| plan.energyPerTick() != hive.energyContainer().getEnergyPerTick()
				|| plan.cycleTicks() != BeeProgressPlan.cycleTicks(bee.originalSlot().copy().getInt("base_min_occupation_ticks"), ModConfig.SERVER.apiaryProcessingTime.get(), 1, false)) return false;
		var outputs = BeeInfoHelper.getBeeProduce(level, IRON);
		if (outputs.size() != 1) return false;
		var entry = outputs.entrySet().iterator().next(); var value = entry.getValue();
		return value.chance() == 1 && value.min() == plan.count() && value.max() == plan.count()
				&& plan.output().equals(ProductKeyCodec.item(entry.getKey(), level.registryAccess()));
	}
	private StaticApiaryAdapter() { }
}
