package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceAccount;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceManualOutput;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeIndex;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** 新取料的资源出口检查；不创建账户、不扣原料，不改变已付产物的恢复。 */
final class Ae2ResourceInputGate {
    enum Decision { ACCEPTED, REJECTED, DEFERRED }

    private static final int MAX_RESOURCES = 256;
    private static final int MAX_RECIPES = 256;
    private static final int MAX_PROBES_PER_TICK = 4;
    private static final int ACCEPTED_TICKS = 20;
    private static final int REJECTED_TICKS = 40;

    private static final class Route {
        AEKey key;
        boolean resolved;
        long checkedAt = Long.MIN_VALUE;
        boolean accepted;
    }

    private static final class RecipeCheck {
        int cursor;
        long checkedAt = Long.MIN_VALUE;
        long evaluatedTick = Long.MIN_VALUE;
        Decision decision = Decision.DEFERRED;
        int productivity;
        double stability;
        java.util.Map<ResourceKey, java.math.BigInteger> maximum;
    }

    private final LinkedHashMap<ResourceKey, Route> routes = new LinkedHashMap<>();
    private final IdentityHashMap<ResourceCentrifugeRecipe, Boolean> recipes = new IdentityHashMap<>();
    private final IdentityHashMap<ResourceCentrifugeRecipe, RecipeCheck> checks = new IdentityHashMap<>();
    private final LinkedHashSet<ResourceCentrifugeRecipe> waiting = new LinkedHashSet<>();
    private long refreshTick = Long.MIN_VALUE;
    private ServerLevel level;
    private BlockEntity host;
    private Ae2OutputStateHolder holder;
    private Ae2PushBuffers buffers;
    private MEStorage storage;
    private IActionSource actionSource;
    private ResourceRecipeIndex recipeIndex;
    private long recipeVersion = Long.MIN_VALUE;
    private long tick;
    private long probeTick = Long.MIN_VALUE;
    private int probes;
    private boolean meEnabled;
    private long nativeRevision;
    private com.ayoshiko.productivebeesgenesis.resource.ResourceSideConfig nativeConfig;
    private ResourceAccount account;
    private ResourceAccount.Reference source;
    private boolean accountReady = true;
    private volatile boolean invalidated;

    void prepare(IAe2InputHost inputHost, Ae2OutputStateHolder holder, Ae2PushBuffers buffers,
            MEStorage storage, long tick) {
        BlockEntity next = inputHost instanceof BlockEntity entity && inputHost instanceof IMekCentrifugeTile
                ? entity : null;
        ServerLevel nextLevel = next != null && next.getLevel() instanceof ServerLevel serverLevel
                ? serverLevel : null;
        var resourceHost = next == null ? null : ResourceHiveHost.existing(next);
        var nextSource = resourceHost == null ? null : resourceHost.reference();
        ResourceAccount nextAccount = null;
        boolean nextReady = true;
        if (nextSource != null) {
            try {
                nextAccount = resourceHost.account(next);
                nextReady = nextAccount.canExtract(nextSource);
            } catch (IllegalStateException unavailable) {
                nextReady = false;
            }
        }
        boolean enabled = inputHost.productivebeesgenesis$isOutputPushEnabled()
                && (resourceHost == null || resourceHost.meOutputEnabled());
        long version = ProductiveBeesGenesis.RECIPE_VERSION.get();
        long nextNativeRevision = resourceHost == null ? 0 : resourceHost.nativeConfig().revision();
        if (invalidated || host != next || this.storage != storage || recipeVersion != version || meEnabled != enabled
                || nativeRevision != nextNativeRevision
                || nativeConfig != (resourceHost == null ? null : resourceHost.nativeConfig())
                || account != nextAccount || !java.util.Objects.equals(source, nextSource) || accountReady != nextReady) {
            invalidated = false;
            routes.clear();
            recipes.clear();
            checks.clear();
            var managed = holder.getAe2GridNode();
            actionSource = managed instanceof IManagedGridNode node && node.getNode() != null
                    ? IActionSource.ofMachine(node::getNode) : IActionSource.empty();
        }
        host = next;
        level = nextLevel;
        recipeIndex = nextLevel == null ? null : ResourceRuntime.recipes(nextLevel.getServer());
        this.holder = holder;
        this.buffers = buffers;
        this.storage = storage;
        this.tick = tick;
        recipeVersion = version;
        meEnabled = enabled;
        nativeRevision = nextNativeRevision;
        nativeConfig = resourceHost == null ? null : resourceHost.nativeConfig();
        account = nextAccount;
        source = nextSource;
        accountReady = nextReady;
        if (probeTick != tick) {
            probeTick = tick;
            probes = 0;
        }
    }

    void clear() {
        invalidated = true;
    }

    boolean hasWaitingRecipes() { return !waiting.isEmpty(); }

    /** 不扫描 ME 库存；最多四份已拒绝配方轮转检查，恢复时撤销新取料的旧冷却。 */
    void refreshWaitingRecipes() {
        if (refreshTick == tick) return;
        refreshTick = tick;
        int count = Math.min(MAX_PROBES_PER_TICK, waiting.size());
        for (int i = 0; i < count; i++) {
            var recipe = waiting.iterator().next();
            waiting.remove(recipe);
            if (decision(recipe) == Decision.ACCEPTED) holder.onResourceOutputRecovered();
            else waiting.add(recipe);
        }
    }

    boolean resourcesAvailable(AEItemKey key) {
        if (recipeIndex == null || recipeIndex.centrifugeRecipes().isEmpty()) return true;
        try {
            var recipe = recipeIndex.centrifuge(key.getReadOnlyStack());
            return recipe == null || available(recipe.value());
        } catch (RuntimeException | LinkageError failure) {
            report(key, failure);
            return false;
        }
    }

    private boolean available(ResourceCentrifugeRecipe recipe) {
        var cached = recipes.get(recipe);
        if (cached != null) return cached;
        boolean available = true;
        for (var output : recipe.outputs()) {
            if (!ResourceRuntime.available(level.getServer(), output.key())) {
                available = false;
                break;
            }
        }
        if (recipes.size() < MAX_RECIPES) recipes.put(recipe, available);
        return available;
    }

    boolean canReceive(AEItemKey key) {
        return decision(key) == Decision.ACCEPTED;
    }

    Decision decision(AEItemKey key) {
        if (recipeIndex == null || recipeIndex.centrifugeRecipes().isEmpty()) return Decision.ACCEPTED;
        try {
            var recipe = recipeIndex.centrifuge(key.getReadOnlyStack());
            if (recipe == null) return Decision.ACCEPTED;
            var result = decision(recipe.value());
            if (result == Decision.ACCEPTED && account != null) {
                var check = checks.get(recipe.value());
                long units = recipe.value().units(key.getReadOnlyStack());
                if (!ResourceHiveHost.existing(host).fitsExact(account, scaledMaximum(check.maximum, units))) {
                    waiting.add(recipe.value());
                    return Decision.REJECTED;
                }
            }
            return result;
        } catch (RuntimeException | LinkageError failure) {
            report(key, failure);
            return Decision.REJECTED;
        }
    }

    Decision decision(ResourceCentrifugeRecipe recipe) {
        var result = evaluate(recipe);
		var resourceHost = host == null ? null : ResourceHiveHost.existing(host);
		if (resourceHost != null && result != Decision.DEFERRED) {
			resourceHost.productionMonitor().route(recipe.outputs(), result == Decision.REJECTED);
		}
        if (result == Decision.REJECTED && waiting.size() < MAX_RECIPES) waiting.add(recipe);
        else if (result == Decision.ACCEPTED) waiting.remove(recipe);
        return result;
    }

    private Decision evaluate(ResourceCentrifugeRecipe recipe) {
        try {
            if (!accountReady || !available(recipe)) return Decision.REJECTED;
            if (account != null) {
                for (var output : recipe.outputs()) {
                    if (!account.canExtract(source, output.key())) return Decision.REJECTED;
                }
            }
            var check = checks.get(recipe);
            if (check == null) {
                if (checks.size() >= MAX_RECIPES) checks.remove(checks.keySet().iterator().next());
                check = new RecipeCheck();
                checks.put(recipe, check);
            }
            if (account != null) {
                var context = host instanceof com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext value ? value : null;
                int productivity = context == null ? 1 : context.productivityModifier();
                double stability = context == null ? 0 : context.stabilityBonus();
                if (check.maximum == null || check.productivity != productivity || check.stability != stability) {
                    check.maximum = com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi
                            .planCentrifuging(recipe, productivity, stability, 0).maximumExact();
                    check.productivity = productivity;
                    check.stability = stability;
                }
                if (!ResourceHiveHost.existing(host).fitsExact(account, check.maximum)) return Decision.REJECTED;
            }
            if (check.evaluatedTick == tick) return check.decision;
            check.evaluatedTick = tick;
            if (check.checkedAt != Long.MIN_VALUE && tick >= check.checkedAt
                    && tick - check.checkedAt < (check.decision == Decision.ACCEPTED ? ACCEPTED_TICKS : REJECTED_TICKS)) {
                return check.decision;
            }
            check.checkedAt = Long.MIN_VALUE;
            check.decision = Decision.DEFERRED;
            var outputs = recipe.outputs();
            while (check.cursor < outputs.size()) {
                Decision result = probe(outputs.get(check.cursor).key());
                if (result == Decision.DEFERRED) return result;
                if (result == Decision.REJECTED) {
                    check.cursor = 0;
                    check.checkedAt = tick;
                    check.decision = result;
                    return result;
                }
                check.cursor++;
            }
            check.cursor = 0;
            check.checkedAt = tick;
            check.decision = Decision.ACCEPTED;
            return check.decision;
        } catch (RuntimeException | LinkageError failure) {
            report(recipe, failure);
            return Decision.REJECTED;
        }
    }

    private Decision probe(ResourceKey resource) {
        Route route = routes.get(resource);
        if (route != null && route.checkedAt != Long.MIN_VALUE && tick >= route.checkedAt
                && tick - route.checkedAt < (route.accepted ? ACCEPTED_TICKS : REJECTED_TICKS)) {
            return route.accepted ? Decision.ACCEPTED : Decision.REJECTED;
        }
        if (probes >= MAX_PROBES_PER_TICK) return Decision.DEFERRED;
        var costs = buffers.insertCostTracker;
        Object probeIdentity = storage == null ? level.getServer() : storage;
        if (Ae2GlobalInsertBudget.isExhausted(tick) || costs.isExhausted(tick)
                || !holder.tryAcquireNetworkWork(probeIdentity, tick)
                || !holder.tryAcquireResourceProbe(probeIdentity, tick)) return Decision.DEFERRED;
        probes++;
        if (route == null) {
            route = new Route();
            if (routes.size() >= MAX_RESOURCES) routes.remove(routes.keySet().iterator().next());
            routes.put(resource, route);
        }
        boolean accepted = false;
        try {
            if (!route.resolved) {
                route.key = Ae2ResourceKeyBridge.resolve(level.registryAccess(), resource);
                route.resolved = true;
            }
            if (meEnabled && storage != null && route.key != null && !ResourceTypes.EMC.equals(resource)) {
                long result = buffers.measuredStorage.prepare(storage, tick, costs)
                        .insert(route.key, 1, Actionable.SIMULATE, actionSource);
                if (result < 0 || result > 1) throw new IllegalStateException("Invalid resource acceptance probe");
                accepted = result == 1;
            }
            if (!accepted) {
                long started = System.nanoTime();
                try {
                    accepted = ResourceManualOutput.canReceiveNeighbor(host, resource);
                } finally {
                    long cost = Math.max(0, System.nanoTime() - started);
                    costs.recordForNetwork(probeIdentity, tick, cost);
                    holder.recordNetworkCost(probeIdentity, tick, cost, Ae2NetworkWorkCoordinator.HEALTHY_INSERT_NANOS);
                    Ae2GlobalInsertBudget.recordCost(tick, cost);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            report(resource, failure);
        }
        route.checkedAt = tick;
        route.accepted = accepted;
        return accepted ? Decision.ACCEPTED : Decision.REJECTED;
    }

    private static java.util.Map<ResourceKey, java.math.BigInteger> scaledMaximum(
            java.util.Map<ResourceKey, java.math.BigInteger> maximum, long units) {
        if (units == 1) return maximum;
        var result = new LinkedHashMap<ResourceKey, java.math.BigInteger>();
        maximum.forEach((key, value) -> result.put(key, value.multiply(java.math.BigInteger.valueOf(units))));
        return result;
    }

    private static void report(Object key, Throwable failure) {
        LogThrottle.warn("ae2_resource_input_gate",
                "扩展资源取料检查失败，本轮跳过且保留原料 key={}: {}", key, failure.toString());
    }
}
