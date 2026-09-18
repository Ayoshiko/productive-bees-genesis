package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;

final class RuleRecordCodec {
	private final ProductRecordCodec products;
	RuleRecordCodec(ProductRecordCodec products) { this.products = products; }
	static CompoundTag matcher(ProductMatcher matcher) {
		var tag = new CompoundTag(); tag.putString("mode", matcher.mode().name()); tag.put("template", ProductRecordCodec.key(matcher.template())); return tag;
	}
	private ProductMatcher readMatcher(CompoundTag tag) {
		return new ProductMatcher(StrictNbt.choice(tag, "mode", ProductMatcher.Mode.class), products.readKey(StrictNbt.compound(tag, "template")));
	}
	static CompoundTag limit(ReserveLimit limit) {
		var tag = new CompoundTag(); tag.putString("mode", limit.mode().name()); tag.put("floor", ProductRecordCodec.amount(limit.floor())); return tag;
	}
	private static ReserveLimit readLimit(CompoundTag tag) {
		return new ReserveLimit(StrictNbt.choice(tag, "mode", ReserveLimit.Mode.class), ProductRecordCodec.readAmount(tag, "floor"));
	}
	static CompoundTag layer(ReservePolicy.Layer layer) {
		var tag = new CompoundTag(); tag.put("fallback", limit(layer.fallback())); var entries = new ListTag();
		layer.entries().forEach((matcher, limit) -> {
			var entry = new CompoundTag(); entry.put("matcher", matcher(matcher)); entry.put("limit", limit(limit)); entries.add(entry);
		});
		tag.put("entries", entries); return tag;
	}
	private ReservePolicy.Layer readLayer(CompoundTag tag) {
		Map<ProductMatcher, ReserveLimit> entries = new ConcurrentHashMap<>();
		for (var raw : StrictNbt.list(tag, "entries")) {
			var entry = (CompoundTag) raw;
			if (entries.putIfAbsent(readMatcher(StrictNbt.compound(entry, "matcher")), readLimit(StrictNbt.compound(entry, "limit"))) != null) {
				throw new IllegalArgumentException("Duplicate reserve matcher");
			}
		}
		return new ReservePolicy.Layer(readLimit(StrictNbt.compound(tag, "fallback")), entries);
	}
	static CompoundTag rule(ProcessingRule rule) {
		var tag = new CompoundTag(); tag.putString("id", rule.id()); tag.putLong("revision", rule.revision()); tag.putBoolean("enabled", rule.enabled());
		tag.putInt("priority", rule.priority()); tag.putInt("weight", rule.weight()); tag.putInt("batch", rule.batchLimit());
		var selector = new CompoundTag();
		switch (rule.selector()) {
			case ProcessingRule.Match match -> { selector.putString("type", "match"); selector.put("matcher", matcher(match.matcher())); }
			case ProcessingRule.Tag match -> { selector.putString("type", "tag"); selector.putString("kind", match.kind().name()); selector.putString("id", match.tag().toString()); }
			case ProcessingRule.Goal goal -> {
				selector.putString("type", "goal"); selector.put("product", ProductRecordCodec.key(goal.product()));
				selector.put("lower", ProductRecordCodec.amount(goal.lower())); selector.put("upper", ProductRecordCodec.amount(goal.upper()));
			}
		}
		tag.put("selector", selector); tag.putString("scope", rule.reserves().scope().name());
		tag.put("global", layer(rule.reserves().global())); tag.put("local", layer(rule.reserves().rule())); return tag;
	}
	ProcessingRule readRule(CompoundTag tag) {
		var selectorTag = StrictNbt.compound(tag, "selector");
		ProcessingRule.Selector selector = switch (StrictNbt.string(selectorTag, "type")) {
			case "match" -> new ProcessingRule.Match(readMatcher(StrictNbt.compound(selectorTag, "matcher")));
			case "tag" -> new ProcessingRule.Tag(StrictNbt.choice(selectorTag, "kind", ProductKey.Kind.class), ResourceLocation.parse(StrictNbt.string(selectorTag, "id")));
			case "goal" -> new ProcessingRule.Goal(products.readKey(StrictNbt.compound(selectorTag, "product")), ProductRecordCodec.readAmount(selectorTag, "lower"), ProductRecordCodec.readAmount(selectorTag, "upper"));
			default -> throw new IllegalArgumentException("Unknown rule selector");
		};
		var reserves = new ReservePolicy(StrictNbt.choice(tag, "scope", ReservePolicy.Scope.class), readLayer(StrictNbt.compound(tag, "global")), readLayer(StrictNbt.compound(tag, "local")));
		return new ProcessingRule(StrictNbt.string(tag, "id"), StrictNbt.number(tag, "revision"), StrictNbt.bool(tag, "enabled"), StrictNbt.integer(tag, "priority"),
				StrictNbt.integer(tag, "weight"), StrictNbt.integer(tag, "batch"), selector, reserves);
	}
	static CompoundTag scheduler(SchedulerCheckpoint state) {
		var tag = new CompoundTag(); tag.putString("mode", state.mode().name()); tag.putString("cursor", state.cursorRule()); tag.putInt("used", state.used());
		var rules = new ListTag(); state.rules().forEach(rule -> rules.add(rule(rule))); tag.put("rules", rules);
		var watermarks = new CompoundTag(); state.watermarks().forEach(watermarks::putBoolean); tag.put("watermarks", watermarks); return tag;
	}
	SchedulerCheckpoint readScheduler(CompoundTag tag) {
		var rules = new ArrayList<ProcessingRule>(); StrictNbt.list(tag, "rules").forEach(raw -> rules.add(readRule((CompoundTag) raw)));
		Map<String, Boolean> watermarks = new ConcurrentHashMap<>(); var encoded = StrictNbt.compound(tag, "watermarks");
		for (String key : encoded.getAllKeys()) watermarks.put(key, StrictNbt.bool(encoded, key));
		return new SchedulerCheckpoint(rules, StrictNbt.choice(tag, "mode", ProcessingRuleScheduler.Mode.class), watermarks, StrictNbt.string(tag, "cursor"), StrictNbt.integer(tag, "used"));
	}
}
