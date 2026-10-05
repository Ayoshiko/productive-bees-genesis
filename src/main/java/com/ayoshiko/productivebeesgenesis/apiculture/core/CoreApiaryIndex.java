package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeProgressPlan;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.ApiaryRank;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.*;
import net.minecraft.server.level.ServerLevel;

/** 核心级能力索引；首次／失效后按共享终端工作预算重建，普通生产和入蜂不重排。 */
final class CoreApiaryIndex {
	private record Rules(int ticks, float mek, float time, float alpha, float beta, float gamma, float omega) {
		static Rules current() {
			return new Rules(ModConfig.SERVER.apiaryProcessingTime.get(), mekanism.common.config.MekanismConfig.general.maxUpgradeMultiplier.get(),
					PbUpgradeConfig.timeBonus(), PbUpgradeType.PRODUCTIVITY.getProductivityFactor(), PbUpgradeType.PRODUCTIVITY_2.getProductivityFactor(),
					PbUpgradeType.PRODUCTIVITY_3.getProductivityFactor(), PbUpgradeType.PRODUCTIVITY_4.getProductivityFactor());
		}
	}
	private Object catalog, topology;
	private long policy;
	private Rules rules;
	private Origin cursor;
	private TreeMap<ApiaryRank, UUID> sorted;
	private HashMap<UUID, ApiaryRank> members;
	private ApiaryRank.Order result;
	/** 调用者每次只领一个全服服务步；最多解析四台真实成员。 */
	ApiaryRank.Order step(NetworkCoreBlockEntity core) {
		var level = (ServerLevel) core.getLevel();
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Apiary order belongs to the server thread");
		var authority = core.ownership().readyAuthority(); var view = core.topology();
		if (authority == null || view == null || !view.valid()) { clear(); return null; }
		var current = authority.checkpoint(); var currentRules = Rules.current();
		if (catalog != current.ownedMachines().capabilityToken() || topology != view || policy != current.policyRevision() || !currentRules.equals(rules)) {
			clear(); catalog = current.ownedMachines().capabilityToken(); topology = view; policy = current.policyRevision(); rules = currentRules;
			sorted = new TreeMap<>(); members = new HashMap<>();
		}
		if (result != null) return result;
		var directory = NetworkPersistence.directory(level.getServer());
		for (int i = 0; i < 4; i++) {
			var record = current.ownedMachines().activeEntry(TerminalScope.APIARY.machine(), cursor, false);
			if (record == null) {
				result = new ApiaryRank.Order(new Object(), Collections.unmodifiableNavigableMap(sorted), Collections.unmodifiableMap(members));
				return result;
			}
			var origin = record.claim().origin();
			// 未就绪成员仍可管理，排在已知能力之后；自动输入另作实时资格校验。
			int ticks = Integer.MAX_VALUE; float productivity = Float.MIN_NORMAL;
			var hive = record.bees() == null ? null : ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class);
			if (hive != null) {
				var capacity = StaticApiaryAdapter.upgradeCapacity(hive, record.assets());
				ticks = BeeProgressPlan.cycleTicks(0, rules.ticks(), capacity.timeFactor(), false);
				productivity = capacity.productivity();
			}
			var rank = new ApiaryRank(0, ticks, productivity, origin, record.claim().member());
			sorted.put(rank, rank.member()); members.put(rank.member(), rank);
			// 能力解析失败不能越过该成员，刷新时仍须处理同一候选。
			cursor = origin;
		}
		return null;
	}
	void clear() { catalog = null; topology = null; rules = null; cursor = null; sorted = null; members = null; result = null; }
}
