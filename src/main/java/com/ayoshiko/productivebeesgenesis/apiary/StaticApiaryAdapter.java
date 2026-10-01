package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import java.util.ArrayList;
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
		var profile = new SealedApiaryProfile(hive, record.assets());
		var counts = PendingBeeCycles.read(extra, 3).counts();
		var source = extra.getList(BeeAssetProjection.SLOTS, Tag.TAG_COMPOUND); var bees = new ArrayList<BeeRecord>();
		for (var raw : source) {
			var slot = (CompoundTag) raw; int index = slot.getInt("slot_index");
			var plan = compilePlan(level, profile, slot, recipeRevision, capabilityRevision);
			bees.add(new BeeRecord(BeeRecord.identity(record.claim().member(), index), record.claim().member(), index,
					new AssetImage(slot), plan, 0, slot.getInt("ticks_in_hive"), counts[index], ProductAmount.ZERO));
		}
		for (int i = 0; i < counts.length; i++) {
			int slot = i;
			if (counts[i] > 0 && bees.stream().noneMatch(bee -> bee.slot() == slot)) throw new IllegalArgumentException("Orphaned paid bee cycles");
		}
		return new BeeMemberState(record.claim().member(), 0, image.getLong("energy"), image.getLong("energyCapacity"), bees,
				StaticFeedingAdapter.migrate(record.assets(), level.registryAccess()));
	}
	/** 将蜂笼完整数据编译为新的静态蜂计划，不创建实体或修改托管物理蜂位。 */
	public static BeeRosterChange insertCaged(ServerLevel level, TileEntityMekApiary hive,
			OwnedMachineRecord record, int index, CompoundTag data, long recipeRevision) {
		if (!level.getServer().isSameThread() || hive.getClass() != TileEntityMekApiary.class
				|| record.phase() != OwnedMachineRecord.Phase.OWNED || record.bees() == null) {
			throw new IllegalArgumentException("Only activated basic apiaries accept caged bees");
		}
		var profile = new SealedApiaryProfile(hive, record.assets());
		var slot = new CompoundTag();
		slot.putInt("slot_index", index); slot.put("entity_data", data.copy());
		slot.putInt("ticks_in_hive", 0); slot.putInt("min_occupation_ticks", 0);
		slot.putInt("base_min_occupation_ticks", 0); slot.putBoolean("has_nectar", data.getBoolean("HasNectar"));
		slot.putString("state", BeeState.IDLE.name()); slot.putFloat("progress", 0);
		return BeeRosterChange.insert(record.bees(), index, new AssetImage(slot), compilePlan(level, profile, slot, recipeRevision, 0));
	}
	public static void validateUpgrades(TileEntityMekApiary hive, AssetImage image) {
		new SealedApiaryProfile(hive, image);
	}
	public static long energyCapacity(TileEntityMekApiary hive, int installed) {
		if (hive.getClass() != TileEntityMekApiary.class || installed < 0 || installed > mekanism.api.Upgrade.ENERGY.getMax())
			throw new IllegalArgumentException("Unsupported apiary energy capacity request");
		long base = hive.energyContainer().getBaseMaxEnergy();
		return com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling.normalCapacity(base,
				mekanism.common.util.MekanismUtils.getMaxEnergy(installed, base));
	}
	/** 只在周期起点读取当前升级，不在每个进行中的 tick 复制封存映像。 */
	public static BeeWorkExecutor.Cycle cycle(TileEntityMekApiary hive, OwnedMachineRecord record, BeeRecord bee) {
		return cycle(new SealedApiaryProfile(hive, record.assets()), bee);
	}
	private static BeeWorkExecutor.Cycle cycle(SealedApiaryProfile profile, BeeRecord bee) {
		return new BeeWorkExecutor.Cycle(BeeProgressPlan.cycleTicks(bee.originalSlot().copy().getInt("base_min_occupation_ticks"),
				ModConfig.SERVER.apiaryProcessingTime.get(), profile.time(), false), profile.energy(), profile.productivity());
	}
	/** 物理机无法接收旧能力或网络随机游标；小数倍率的部分周期必须先结清。 */
	public static boolean returnReady(TileEntityMekApiary hive, OwnedMachineRecord record) {
		if (record.bees() == null) return true;
		if (!record.bees().drained()) return false;
		if (record.bees().bees().stream().noneMatch(bee -> bee.progress() > 0)) return true;
		var profile = new SealedApiaryProfile(hive, record.assets());
		return record.bees().bees().stream().allMatch(bee -> bee.progress() == 0 || cycle(profile, bee).matches(bee.plan())
				&& bee.plan().productionMultiplier() == Math.floor(bee.plan().productionMultiplier()));
	}
	private static StaticBeePlan compilePlan(ServerLevel level, SealedApiaryProfile profile, CompoundTag slot,
			long recipeRevision, long capabilityRevision) {
		var data = slot.getCompound("entity_data"); int index = slot.getInt("slot_index");
		if (BeeNbtHelper.resolveEntityType(data) != cy.jdkdigital.productivebees.init.ModEntities.CONFIGURABLE_BEE.get()
				|| !IRON.equals(BeeNbtHelper.resolveBeeTypeKey(data)) || data.getBoolean("HasConverted") || index < 0 || index >= 3) {
			throw new IllegalArgumentException("Only unconverted static iron bees are supported");
		}
		var pref = BeeInfoHelper.getFlowerPreference(IRON);
		if (!BeeInfoHelper.FlowerPreference.TYPE_BLOCKS.equals(pref.flowerType()) || !pref.hasFlowerDefinition()) {
			throw new IllegalArgumentException("Unsupported iron bee flower definition");
		}
		var holder = BeeInfoHelper.getBeeProductionRecipe(level, IRON);
		if (holder == null || holder.value().ingredient.get() == null || !IRON.equals(holder.value().ingredient.get().getBeeType())) {
			throw new IllegalArgumentException("Missing static iron recipe");
		}
		var outputs = holder.value().getRecipeOutputs();
		if (outputs.size() != 1) throw new IllegalArgumentException("Static iron adapter requires exactly one output");
		var entry = outputs.entrySet().iterator().next(); var output = entry.getValue();
		if (entry.getKey().isEmpty() || output.chance() != 1 || output.min() < 1 || output.min() != output.max()) {
			throw new IllegalArgumentException("Random output requires a dedicated adapter");
		}
		return new StaticBeePlan(IRON.toString(), holder.id().toString(), recipeRevision, capabilityRevision,
				BeeProgressPlan.cycleTicks(slot.getInt("base_min_occupation_ticks"), ModConfig.SERVER.apiaryProcessingTime.get(), profile.time(), false),
				profile.energy(), BeeProductivityGene.readLevel(data), BalanceConfig.apiaryBeeGenesAffectWork(),
				BeeWorkConditionEvaluator.readTraits(data), ProductKeyCodec.item(entry.getKey(), level.registryAccess()), output.min(), profile.productivity());
	}
	public static boolean currentPlan(ServerLevel level, TileEntityMekApiary hive, BeeRecord bee) {
		var plan = bee.plan(); var pref = BeeInfoHelper.getFlowerPreference(IRON);
		if (hive.isFeederConversionEnabled() || !BeeInfoHelper.FlowerPreference.TYPE_BLOCKS.equals(pref.flowerType())
				|| !pref.hasFlowerDefinition() || plan.genesAffectWork() != BalanceConfig.apiaryBeeGenesAffectWork()) return false;
		var outputs = BeeInfoHelper.getBeeProduce(level, IRON);
		if (outputs.size() != 1) return false;
		var entry = outputs.entrySet().iterator().next(); var value = entry.getValue();
		return value.chance() == 1 && value.min() == plan.count() && value.max() == plan.count()
				&& plan.output().equals(ProductKeyCodec.item(entry.getKey(), level.registryAccess()));
	}
	private StaticApiaryAdapter() { }
}
