package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeProgressPlan;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.FactoryTierConfigService;
import com.ayoshiko.productivebeesgenesis.config.FactoryTierKey;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 主线程显式只读采集；不注册 ticker、AE 节点或接管旧机器。 */
public final class MachineCapacityReader {
	public record BeeCycleQuery(WorkKey work, int baseOccupationTicks) {
		public BeeCycleQuery {
			if (work.kind() != WorkKey.Kind.BEE_CYCLE) throw new IllegalArgumentException("Not bee work");
		}
	}
	public record FactoryBufferLimits(int inputStackMultiplier, int outputStackMultiplier,
			int fluidTankMultiplier, int apiaryOutputStackMultiplier) { }

	private MachineCapacityReader() { }

	/** 容量配置用于单独展示与交接校验，绝不能再次乘入生产并行数。 */
	public static FactoryBufferLimits factoryBufferLimits(FactoryTierKey tier) {
		var config = FactoryTierConfigService.current();
		return new FactoryBufferLimits(config.centrifugeInputStack(tier), config.centrifugeOutputStack(tier),
				config.centrifugeFluidTank(tier), config.apiaryOutputStack(tier));
	}

	public static MemberCapabilitySnapshot apiary(TileEntityMekApiary tile, UUID memberId, long revision,
			List<BeeCycleQuery> queries) {
		requireServerThread(tile);
		var upgrades = tile.getApiaryUpgradeHandler();
		boolean creative = upgrades.hasCreativeUpgrade();
		float time = upgrades.getTimeMultiplier();
		long energy = creative ? 0 : Math.max(0, tile.energyContainer().getEnergyPerTick());
		Map<String, Integer> effects = effects(tile, tile);
		List<WorkCapacity> work = new ArrayList<>();
		for (BeeCycleQuery query : queries) {
			int ticks = BeeProgressPlan.cycleTicks(query.baseOccupationTicks(), ModConfig.SERVER.apiaryProcessingTime.get(), time, creative);
			work.add(new WorkCapacity(query.work(), upgrades.getStackProductionCount(), ticks,
					energy, energy, upgrades.getProductivityMultiplier(), 0, effects));
		}
		return snapshot(tile, memberId, revision, tile.canFunction(), tile.getBeeSlotCount(), tile.getBeeSlotCount(), work);
	}

	/** 对已解析的普通 PB 配方分别评估满载能力，特殊动态配方由后续适配器提供。 */
	public static MemberCapabilitySnapshot centrifuge(PbRecipeContext context, UUID memberId, long revision,
			long recipeRevision, List<RecipeHolder<CentrifugeRecipe>> recipes) {
		if (!(context instanceof TileEntityMekanism tile) || !(tile instanceof IPbUpgradeProvider upgrades)
				|| tile instanceof TileEntityMekApiary) throw new IllegalArgumentException("Not a centrifuge member");
		requireServerThread(tile);
		long energy = context.hasCreativeUpgrade() ? 0 : Math.max(0, context.energyContainer().getEnergyPerTick());
		int operations = Math.max(1, context.operationsPerTick());
		int configuredLimit = ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
		if (configuredLimit > 0 && operations > 1) operations = Math.min(operations, configuredLimit);
		int parallel = SaturatingMath.saturatingToInt(SaturatingMath.saturatingMultiply(
				operations, Math.max(1, context.productivityParallelModifier())));
		long cost = MekCentrifugeEnergyScaling.batchEnergyCost(energy, parallel, 1);
		Map<String, Integer> effects = effects(tile, upgrades);
		List<WorkCapacity> work = new ArrayList<>();
		for (RecipeHolder<CentrifugeRecipe> recipe : recipes) {
			int base = recipe.value().getProcessingTime();
			int ticks = Math.max(1, context.getTicksForBase(base > 0 ? base : context.baseTicksRequired()));
			work.add(new WorkCapacity(new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, recipe.id().toString(),
					recipeRevision, "ordinary_pb_recipe"), parallel, ticks, energy, cost,
					context.productivityModifier(), context.stabilityBonus(), effects));
		}
		return snapshot(tile, memberId, revision, context.canFunction(), 0, context.processes(), work);
	}

	private static Map<String, Integer> effects(TileEntityMekanism tile, IPbUpgradeProvider provider) {
		Map<String, Integer> effects = new ConcurrentHashMap<>();
		for (Upgrade upgrade : Upgrade.values()) {
			int count = tile.getComponent().getUpgrades(upgrade);
			if (count > 0) effects.put("mek:" + upgrade.name(), count);
		}
		for (PbUpgradeType type : PbUpgradeType.values()) {
			int count = provider.getPbUpgradeInstalledCount(type);
			if (count > 0) effects.put("pb:" + type.name(), count);
		}
		effects.put("balance:" + BalanceConfig.preset().name(), 1);
		effects.put("beeGenesAffectWork", BalanceConfig.apiaryBeeGenesAffectWork() ? 1 : 0);
		effects.put("centrifugeProductivityAffectsOutput", BalanceConfig.centrifugeProductivityAffectsOutput() ? 1 : 0);
		if (tile instanceof TileEntityMekApiary hive) {
			effects.put("feederConversionEnabled", hive.isFeederConversionEnabled() ? 1 : 0);
		}
		return Map.copyOf(effects);
	}

	private static MemberCapabilitySnapshot snapshot(BlockEntity tile, UUID id, long revision,
			boolean allowed, int bees, int lanes, List<WorkCapacity> work) {
		var pos = tile.getBlockPos();
		var origin = new MemberCapabilitySnapshot.Origin(tile.getLevel().dimension().location().toString(),
				pos.getX(), pos.getY(), pos.getZ());
		return new MemberCapabilitySnapshot(id, revision, BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).toString(),
				origin, allowed ? MemberCapabilitySnapshot.Availability.ONLINE : MemberCapabilitySnapshot.Availability.REDSTONE_PAUSED,
				bees, lanes, work);
	}

	private static void requireServerThread(BlockEntity tile) {
		if (!(tile.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
				|| tile.isRemoved() || !level.hasChunk(tile.getBlockPos().getX() >> 4, tile.getBlockPos().getZ() >> 4)
				|| level.getBlockEntity(tile.getBlockPos()) != tile) {
			throw new IllegalStateException("Capability capture requires a live member on its server thread");
		}
		if (!BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).getNamespace().equals("productivebeesgenesis")) {
			throw new IllegalArgumentException("No adapter for foreign member");
		}
	}
}
