package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/** 配方和标签快照编译为按基础类型检索的候选，不在选择时遍历所有服务器配方。 */
public final class ProcessingRuleIndex {
	public record TagId(ProductKey.Kind kind, ResourceLocation id) { }
	public record Binding(ProcessingRule rule, ProcessingRecipe recipe) { }
	private record Domain(ProductKey.Kind kind, ResourceLocation id) { }
	private final List<ProcessingRule> rules;
	private final List<ProcessingRule> configuredRules;
	private final Map<Domain, List<Binding>> byInput;
	public ProcessingRuleIndex(Collection<ProcessingRule> rules, Collection<ProcessingRecipe> recipes,
			Map<TagId, Set<ResourceLocation>> tags) {
		var ids = ConcurrentHashMap.<String>newKeySet();
		for (var rule : rules) if (!ids.add(rule.id())) throw new IllegalArgumentException("Duplicate processing rule identity");
		this.configuredRules = List.copyOf(rules);
		this.rules = rules.stream().filter(ProcessingRule::enabled)
				.sorted(Comparator.comparingInt(ProcessingRule::priority).reversed().thenComparing(ProcessingRule::id)).toList();
		Map<Domain, List<Binding>> compiled = new ConcurrentHashMap<>();
		for (var recipe : recipes) {
			var input = recipe.input().template();
			for (var rule : this.rules) {
				boolean matches = switch (rule.selector()) {
					case ProcessingRule.Match match -> match.matcher().template().kind() == input.kind() && match.matcher().template().id().equals(input.id());
					case ProcessingRule.Tag tag -> tag.kind() == input.kind() && tags.getOrDefault(new TagId(tag.kind(), tag.tag()), Set.of()).contains(input.id());
					case ProcessingRule.Goal goal -> recipe.possibleOutputs().contains(goal.product());
				};
				if (matches) compiled.computeIfAbsent(new Domain(input.kind(), input.id()), ignored -> new ArrayList<>()).add(new Binding(rule, recipe));
			}
		}
		compiled.replaceAll((domain, bindings) -> bindings.stream()
				.sorted(Comparator.comparing((Binding b) -> b.rule().id()).thenComparing(b -> b.recipe().work().toString())
						.thenComparing(b -> b.recipe().input().template().orderingKey())).toList());
		byInput = Map.copyOf(compiled);
	}
	public List<ProcessingRule> rules() { return rules; }
	public List<ProcessingRule> configuredRules() { return configuredRules; }
	public List<Binding> candidates(ProductKey key) {
		return byInput.getOrDefault(new Domain(key.kind(), key.id()), List.of()).stream()
				.filter(binding -> binding.recipe().input().matches(key)
						&& (!(binding.rule().selector() instanceof ProcessingRule.Match match) || match.matcher().matches(key))).toList();
	}
}
