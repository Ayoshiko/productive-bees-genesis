package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter.Profile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** 每个网络按相同封存能力共享续查，成员只保留组引用；不持有世界或生产资产。 */
final class CentrifugeInputSearches {
	enum Result { SEARCHING, READY, EMPTY, RESERVED }
	static final class Cursor {
		ProductKey after, input;
		int ruleOffset, examined, remaining = -1;
		SchedulerCheckpoint scheduler;
		ProcessingRule rule;
		ReserveAllowanceScan reserve;
		LedgerCheckpoint ledger, scanLedger, exhausted;
		boolean reserved;
		void restart(SchedulerCheckpoint next) {
			scheduler = next; ruleOffset = examined = 0; remaining = -1;
			nextInput(); rule = null; reserved = false; scanLedger = exhausted = null;
		}
		void nextInput() { input = null; reserve = null; ledger = null; }
		/** 每步至多检查一个键。无输入结论仅复用于同一库存根、规则游标和能力组。 */
		Result select(ProcessingStockIndex.View stock, RuntimeProcessingRules rules,
				SchedulerCheckpoint current, Predicate<ProductKey> accepts) {
			if (scheduler != current || exhausted != null && exhausted != stock.ledger()) restart(current);
			if (exhausted == stock.ledger()) return reserved ? Result.RESERVED : Result.EMPTY;
			if (scanLedger == null) scanLedger = stock.ledger();
			if (ruleOffset >= rules.size()) {
				// 扫描期间新增的键可能位于游标之前；不能把旧扫描的空结论盖到新库存根上。
				if (scanLedger != stock.ledger()) { restart(current); return Result.SEARCHING; }
				exhausted = stock.ledger(); return reserved ? Result.RESERVED : Result.EMPTY;
			}
			if (input != null) return Result.READY;
			rule = rules.rule((rules.first(current) + ruleOffset) % rules.size());
			var exact = rule != null && rule.selector() instanceof ProcessingRule.Match match
					&& match.matcher().mode() == ProductMatcher.Mode.EXACT ? match.matcher().template() : null;
			if (remaining < 0) { remaining = exact == null ? stock.size() : 1; examined = 0; }
			if (examined >= remaining) { ruleOffset++; remaining = -1; return Result.SEARCHING; }
			var key = exact == null ? stock.nextKey(after) : exact;
			if (key == null) key = stock.nextKey(null);
			examined++; after = key;
			if (key == null || stock.amount(key).isZero() || !accepts.test(key)) return Result.SEARCHING;
			input = key; return Result.READY;
		}
	}
	private static final class Group {
		final Cursor cursor = new Cursor();
		int members;
	}
	private final Thread owner = Thread.currentThread();
	private final Map<Profile, Group> groups = new HashMap<>();
	private final Map<UUID, Profile> members = new HashMap<>();
	Cursor cursor(UUID member, Profile profile) {
		check();
		if (!profile.equals(members.get(member))) {
			forget(member);
			members.put(member, profile); groups.computeIfAbsent(profile, ignored -> new Group()).members++;
		}
		return groups.get(profile).cursor;
	}
	void forget(UUID member) {
		check(); var profile = members.remove(member);
		if (profile != null && --groups.get(profile).members == 0) groups.remove(profile);
	}
	void clear() { check(); members.clear(); groups.clear(); }
	/** 短暂失联撤销候选与额度，仅保留无授权含义的轮转位置，避免长配方总从首键重来。 */
	void invalidate() {
		check();
		for (var group : groups.values()) group.cursor.restart(null);
	}
	int groups() { check(); return groups.size(); }
	private void check() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Centrifuge searches belong to the server thread");
	}
}
