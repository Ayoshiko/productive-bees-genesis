package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import java.util.ArrayList;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.HoneycombItem;
import cy.jdkdigital.productivebees.util.BeeHelper;

/** 只读封存数据和成员原位置；不构造隐藏机器，不执行物理生产、喂食或输出。 */
public final class StaticApiaryAdapter {
	/** 只含计算参数，载体负责真实升级资产与资格；公式由各载体的已验证能力输入给出。 */
	public record Profile(float time, long energy, float productivity, boolean combBlock) {
		public Profile {
			if (!Float.isFinite(time) || time <= 0 || energy < 0 || !Float.isFinite(productivity) || productivity <= 0)
				throw new IllegalArgumentException("Invalid apiary profile");
		}
	}
	public record CagedBee(AssetImage original, StaticBeePlan plan) { }
	private static Profile profile(SealedApiaryProfile value) { return new Profile(value.time(), value.energy(), value.productivity(), value.combBlock()); }
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
		var caged = caged(level, profile(new SealedApiaryProfile(hive, record.assets())), index, 3, data, recipeRevision);
		return BeeRosterChange.insert(record.bees(), index, caged.original(), caged.plan());
	}
	/** 新载体复用真实蜂数据与静态配方；容量由入口明确提供，基础蜂箱继续只给三槽。 */
	public static CagedBee caged(ServerLevel level, Profile profile, int index, int slots, CompoundTag data, long recipeRevision) {
		if (!level.getServer().isSameThread() || index < 0 || index >= slots) throw new IllegalArgumentException("Invalid caged bee slot");
		var slot = new CompoundTag();
		slot.putInt("slot_index", index); slot.put("entity_data", data.copy());
		slot.putInt("ticks_in_hive", 0); slot.putInt("min_occupation_ticks", 0);
		slot.putInt("base_min_occupation_ticks", 0); slot.putBoolean("has_nectar", data.getBoolean("HasNectar"));
		slot.putString("state", BeeState.IDLE.name()); slot.putFloat("progress", 0);
		return new CagedBee(new AssetImage(slot), compilePlan(level, profile, slot, recipeRevision, 0));
	}
	public static void validateUpgrades(TileEntityMekApiary hive, AssetImage image) {
		new SealedApiaryProfile(hive, image);
	}
	public static com.ayoshiko.productivebeesgenesis.apiculture.capacity.UpgradeCapacity upgradeCapacity(TileEntityMekApiary hive, AssetImage image) {
		var profile = new SealedApiaryProfile(hive, image);
		return new com.ayoshiko.productivebeesgenesis.apiculture.capacity.UpgradeCapacity(profile.time(), profile.energy(),
				image.copy().getLong("energyCapacity"), 1, profile.productivity(), 0, profile.combBlock(), false);
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
		return cycle(profile(new SealedApiaryProfile(hive, record.assets())), bee, hive.getLevel().registryAccess());
	}
	public static BeeWorkExecutor.Cycle cycle(Profile profile, BeeRecord bee, HolderLookup.Provider registries) {
		return new BeeWorkExecutor.Cycle(BeeProgressPlan.cycleTicks(bee.originalSlot().copy().getInt("base_min_occupation_ticks"),
				ModConfig.SERVER.apiaryProcessingTime.get(), profile.time(), false), profile.energy(), profile.productivity(),
				output(bee.plan().sourceOutput(), profile.combBlock(), registries));
	}
	/** 物理机无法接收旧能力或网络随机游标；小数倍率的部分周期必须先结清。 */
	public static boolean returnReady(TileEntityMekApiary hive, OwnedMachineRecord record) {
		if (record.bees() == null) return true;
		if (!record.bees().drained()) return false;
		if (record.bees().bees().stream().noneMatch(bee -> bee.progress() > 0)) return true;
		var profile = new SealedApiaryProfile(hive, record.assets());
		return record.bees().bees().stream().allMatch(bee -> bee.progress() == 0 || cycle(profile(profile), bee, hive.getLevel().registryAccess()).matches(bee.plan())
				&& bee.plan().productionMultiplier() == Math.floor(bee.plan().productionMultiplier()));
	}
	/** 只解析下一周期的固定模板；PB 映射错误向外传播，不能在扣费后降级换键。 */
	public static ProductKey output(ProductKey source, boolean combBlock, HolderLookup.Provider registries) {
		if (!combBlock) return source;
		var stack = ProductKeyCodec.item(source, 1, registries);
		if (!(stack.getItem() instanceof HoneycombItem)) return source;
		var block = BeeHelper.getCombBlockFromHoneyComb(stack);
		return block.isEmpty() ? source : ProductKeyCodec.item(block, registries);
	}
	private static StaticBeePlan compilePlan(ServerLevel level, SealedApiaryProfile profile, CompoundTag slot,
			long recipeRevision, long capabilityRevision) {
		int index = slot.getInt("slot_index");
		if (index < 0 || index >= 3) throw new IllegalArgumentException("Only three basic apiary slots are supported");
		return compilePlan(level, profile(profile), slot, recipeRevision, capabilityRevision);
	}
	private static StaticBeePlan compilePlan(ServerLevel level, Profile profile, CompoundTag slot, long recipeRevision, long capabilityRevision) {
		var data = slot.getCompound("entity_data");
		if (BeeNbtHelper.resolveEntityType(data) != cy.jdkdigital.productivebees.init.ModEntities.CONFIGURABLE_BEE.get()
				|| !IRON.equals(BeeNbtHelper.resolveBeeTypeKey(data)) || data.getBoolean("HasConverted")) {
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
		var source = ProductKeyCodec.item(entry.getKey(), level.registryAccess());
		return new StaticBeePlan(IRON.toString(), holder.id().toString(), recipeRevision, capabilityRevision,
				BeeProgressPlan.cycleTicks(slot.getInt("base_min_occupation_ticks"), ModConfig.SERVER.apiaryProcessingTime.get(), profile.time(), false),
				profile.energy(), BeeProductivityGene.readLevel(data), BalanceConfig.apiaryBeeGenesAffectWork(),
				BeeWorkConditionEvaluator.readTraits(data), output(source, profile.combBlock(), level.registryAccess()), output.min(), profile.productivity(), source);
	}
	public static boolean currentPlan(ServerLevel level, TileEntityMekApiary hive, BeeRecord bee) {
		return !hive.isFeederConversionEnabled() && currentPlan(level, bee);
	}
	public static boolean currentPlan(ServerLevel level, BeeRecord bee) {
		var plan = bee.plan(); var pref = BeeInfoHelper.getFlowerPreference(IRON);
		if (!IRON.toString().equals(plan.beeType()) || !BeeInfoHelper.FlowerPreference.TYPE_BLOCKS.equals(pref.flowerType())
				|| !pref.hasFlowerDefinition() || plan.genesAffectWork() != BalanceConfig.apiaryBeeGenesAffectWork()) return false;
		var outputs = BeeInfoHelper.getBeeProduce(level, IRON);
		if (outputs.size() != 1) return false;
		var entry = outputs.entrySet().iterator().next(); var value = entry.getValue();
		return value.chance() == 1 && value.min() == plan.count() && value.max() == plan.count()
				&& plan.sourceOutput().equals(ProductKeyCodec.item(entry.getKey(), level.registryAccess()));
	}
	private StaticApiaryAdapter() { }
}
