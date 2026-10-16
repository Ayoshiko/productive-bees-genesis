package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeRecipePlan;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.DynamicBeeAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 每台控制器的可丢弃计划缓存；只在服务器线程使用，卸载时清理，不持有世界。 */
final class MachineProduction {
	private ProductPolicySnapshot snapshot;
	private ProductPolicyRegistry policy;
	private StaticCentrifugeAdapter.Profile centrifuge;
	private boolean genes;
	private final Map<ProductKey, Optional<CentrifugeRecipePlan>> recipes = new HashMap<>();
	private final Map<StaticBeePlan, Boolean> bees = new IdentityHashMap<>();
	private final BeeWorkExecutor.Status[] beeStatus = new BeeWorkExecutor.Status[6];
	BeeWorkExecutor.Status beeStatus(int slot) { return beeStatus[slot]; }
	CombinedMachineWork advance(ServerLevel level, CombinedMachineWork work, BlockPos origin,
			Consumer<CombinedMachineWork> publish, Supplier<CombinedMachineWork> currentWork) {
		var published = RuntimeProductPolicies.peek(level); var profile = MachineUpgradeProfiles.centrifuge(work.upgrades());
		if (snapshot != published || !profile.equals(centrifuge) || genes != BalanceConfig.apiaryBeeGenesAffectWork()) {
			snapshot = published; centrifuge = profile; genes = BalanceConfig.apiaryBeeGenesAffectWork();
			policy = published == null ? null : new ProductPolicyRegistry(published); recipes.clear(); bees.clear();
		}
		var environment = new BeeWorkConditions.Environment(level.dimensionType().hasFixedTime(), level.isNight(), level.isRaining(), level.isThundering());
		Arrays.fill(beeStatus, null);
		for (var original : work.bees()) {
			var bee = work.bees().stream().filter(value -> value.id().equals(original.id())).findFirst().orElse(null);
			if (bee == null) continue;
			var context = new BeeWorkExecutor.Context(true, false, false, bee.plan().recipeRevision(), bee.plan().capabilityRevision(), environment);
			if (bee.pendingCycles() > 0) work = work.advanceBee(bee.slot(), bee.revision(), context, 0, 8, null).apply(work);
			work = settleBee(level, work, bee.slot()); bee = work.bee(bee.slot());
			beeStatus[bee.slot()] = bee.drained() ? BeeWorkExecutor.Status.STALE_PLAN : BeeWorkExecutor.Status.DRAIN_FIRST;
			if (bee.generation() != null && bee.generation().phase() != BeeGeneration.Phase.READY)
				beeStatus[bee.slot()] = BeeWorkExecutor.Status.GENERATION_UNKNOWN;
			if (policy != null && bee.drained()) {
				if (bees.size() >= 32) bees.clear();
				var candidate = bee;
				boolean current = bees.computeIfAbsent(bee.plan(), ignored -> StaticApiaryAdapter.currentPlan(level, candidate));
				boolean flower = current && StaticFeedingAdapter.flower(work.feeding(), bee.slot(), ResourceLocation.parse(bee.plan().beeType()), level.registryAccess());
				context = new BeeWorkExecutor.Context(true, current, flower, bee.plan().recipeRevision(), bee.plan().capabilityRevision(), environment);
				BeeWorkExecutor.Cycle cycle = null;
				if (bee.progress() == 0 && current) {
					try {
						cycle = StaticApiaryAdapter.cycle(MachineUpgradeProfiles.apiary(work.upgrades()), bee, level);
					} catch (com.ayoshiko.productivebeesgenesis.util.EssenceConversionRecipeIndex.RecipesUnavailableException exception) {
						beeStatus[bee.slot()] = BeeWorkExecutor.Status.STALE_PLAN;
						continue;
					}
				}
				context = DynamicBeeAdapter.prepare(level, bee, work.feeding(), 1, cycle, context);
				var step = work.stepBee(bee.slot(), bee.revision(), context, 1, 8, cycle);
				work = step.change().apply(work);
				var started = work.bee(bee.slot());
				if (bee.generation() == null && started.generation() != null && started.generation().phase() == BeeGeneration.Phase.STARTED) {
					publish.accept(work);
					var generated = DynamicBeeAdapter.generate(level, origin, started);
					work = currentWork.get();
					if (!DynamicBeeAdapter.sameRequest(started, work.bee(bee.slot()))) throw new IllegalStateException("Machine bee generation owner changed");
					work = work.resolveGeneration(bee.slot(), generated).apply(work);
					publish.accept(work);
					RuntimeProductPolicies.recordGenerated(level, generated);
				}
				work = settleBee(level, work, bee.slot());
				beeStatus[bee.slot()] = !work.bee(bee.slot()).drained() ? BeeWorkExecutor.Status.DRAIN_FIRST
						: !current ? BeeWorkExecutor.Status.STALE_PLAN : step.status();
			}
		}
		for (int lane = 0; lane < work.lanes(); lane++) {
			if (!work.centrifuges().containsKey(lane) && policy != null) work = assign(level, work, lane);
			work = work.advanceCentrifuge(lane, 1, true).apply(work);
			work = work.freezeCentrifuge(lane).apply(work);
			work = work.settleCentrifuge(lane, key -> limit(level, key)).apply(work);
		}
		return work;
	}
	private CombinedMachineWork assign(ServerLevel level, CombinedMachineWork work, int lane) {
		// 只枚举本机 27 格；正负结果同样缓存，配方/能力变化或卸载时失效。
		for (var cell : work.buffer().items()) {
			if (cell.key() == null) continue;
			if (recipes.size() >= 128) recipes.clear();
			var plan = recipes.computeIfAbsent(cell.key(), key -> {
				try { return Optional.of(StaticCentrifugeAdapter.compile(level, policy, key, work.upgrades().revision(), centrifuge)); }
				catch (IllegalArgumentException unsupported) { return Optional.empty(); }
			}).orElse(null);
			if (plan == null) continue;
			var id = UUID.nameUUIDFromBytes((work.machine() + ":job:" + work.generation() + ":" + work.revision() + ":" + lane).getBytes(StandardCharsets.UTF_8));
			var change = work.assignCentrifuge(lane, plan, plan.maxParallel(), id, id.getMostSignificantBits() ^ id.getLeastSignificantBits());
			if (change.changed()) return change.apply(work);
		}
		return work;
	}
	private static CombinedMachineWork settleBee(ServerLevel level, CombinedMachineWork work, int slot) {
		RuntimeProductPolicies.recordProduced(level, work.bee(slot));
		return work.settleBee(slot, key -> limit(level, key)).apply(work);
	}
	private static int limit(ServerLevel level, ProductKey key) { return Math.min(64, ProductKeyCodec.item(key, 1, level.registryAccess()).getMaxStackSize()); }
}
