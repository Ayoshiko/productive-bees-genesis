package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.NetworkCentrifugeService;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter;
import java.math.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

/** 当前产物页按完整输入键查询全网条件吞吐；与正式分配共用封存 Profile，不持有预约或资产根。 */
@EventBusSubscriber(modid = "productivebeesgenesis", bus = EventBusSubscriber.Bus.MOD)
public final class CoreCentrifugeIndex {
	private static volatile Object settings = new Object();
	private static final int MAX_QUERIES = 72;
	private final LinkedHashMap<ProductKey, Query> queries = new LinkedHashMap<>(16, 0.75f, true);
	private Object catalog, topology, configuration;
	private long recipes = -1, policy = -1;
	private static final class Query {
		MemberCapabilitySnapshot.Origin cursor;
		final Map<StaticCentrifugeAdapter.Profile, Optional<WorkCapacity>> profiles = new HashMap<>();
		CapacityPoolIndex.QueryBuilder builder;
		TerminalView.Processing result;
		boolean collected;
		long members, lanes, completedAt;
	}
	@SubscribeEvent public static void configChanged(ModConfigEvent event) { settings = new Object(); }
	public void clear() { queries.clear(); catalog = null; topology = null; configuration = null; }
	/** 一个终端服务步最多读取四个成员；没有查看者时不扫描库存或配方目录。 */
	public TerminalView.Processing step(NetworkCoreBlockEntity core, ProductKey input, long now) {
		if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) throw new IllegalStateException("Capacity belongs to the server thread");
		var authority = core.ownership().readyAuthority(); var view = core.topology();
		if (authority == null || view == null || !view.valid()) { clear(); return null; }
		var root = authority.checkpoint(); long epoch = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (catalog != root.ownedMachines().capabilityToken() || topology != view || configuration != settings
				|| recipes != epoch || policy != root.policyRevision()) {
			clear(); catalog = root.ownedMachines().capabilityToken(); topology = view; configuration = settings;
			recipes = epoch; policy = root.policyRevision();
		}
		if (input.kind() != ProductKey.Kind.ITEM) return null;
		var compiled = RuntimeProductPolicies.peek(level);
		if (compiled == null || compiled.revision() != root.policyRevision()) return null;
		var query = queries.computeIfAbsent(input, ignored -> new Query());
		if (queries.size() > MAX_QUERIES) queries.remove(queries.keySet().iterator().next());
		// 红石条件无需资产修订；完成的展示定期重采样，未完成查询不因时间被反复清零。
		if (query.result != null && now - query.completedAt >= 100) { query = new Query(); queries.put(input, query); }
		if (query.result != null) return query.result;
		var registry = new ProductPolicyRegistry(compiled);
		var service = new NetworkCentrifugeService(authority, NetworkPersistence.directory(level.getServer()), registry);
		for (int unit = 0; unit < 4 && !query.collected; unit++) {
			var member = TerminalScope.CENTRIFUGE.next(root.ownedMachines(), query.cursor, false);
			if (member == null) { query.collected = true; break; }
			query.cursor = member.claim().origin();
			try {
				var profile = service.profile(level, member.claim().member());
				var capacity = query.profiles.get(profile);
				if (capacity == null) {
					try {
						var plan = StaticCentrifugeAdapter.compile(level, registry, input, 0, profile);
						var key = new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, plan.recipe(), epoch, "selected_input");
						capacity = Optional.of(new WorkCapacity(key, plan.maxParallel(), plan.cycleTicks(), plan.unitEnergyPerTick(),
								plan.energyPerTick(plan.maxParallel()), plan.productivity(), plan.stability(), Map.of("discardByproducts", profile.discardByproducts() ? 1 : 0)));
					} catch (IllegalArgumentException | ArithmeticException unsupported) { capacity = Optional.empty(); }
					query.profiles.put(profile, capacity);
				}
				if (capacity.isEmpty()) continue;
				if (query.builder == null) query.builder = new CapacityPoolIndex.QueryBuilder(capacity.get().work());
				int lanes = member.workLayout().centrifugeLanes();
				query.builder.add(capacity.get(), member.claim().member(), root.revision(), lanes);
				query.members++; query.lanes += lanes;
			} catch (IllegalArgumentException unsupported) { /* 不可用或未支持的载体不贡献可执行能力。 */ }
		}
		if (!query.collected) return null;
		var summary = query.builder == null ? new CapacityPoolIndex.Summary(ExactRate.ZERO, BigInteger.ZERO, List.of()) : query.builder.finishStep(4);
		if (summary == null) return null;
		var rate = summary.operationsPerTick().multiply(20);
		String perSecond = new BigDecimal(rate.numerator()).divide(new BigDecimal(rate.denominator()), new MathContext(6)).stripTrailingZeros().toString();
		query.result = new TerminalView.Processing(query.members, query.lanes, perSecond, summary.fullLoadEnergyPerTick().toString());
		query.completedAt = now; query.builder = null; query.profiles.clear(); return query.result;
	}
	public TerminalView.Processing cached(ProductKey input) { var query = queries.get(input); return query == null ? null : query.result; }
}
