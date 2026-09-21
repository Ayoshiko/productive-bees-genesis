package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRule;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;

/** 当前自动离心只接输入规则；目标库存须先具备完整在制统计，不能退化为无条件处理。 */
public final class RuntimeProcessingMatch {
	public static boolean accepts(ProcessingRule rule, ProductKey input) {
		if (input.kind() != ProductKey.Kind.ITEM) return false;
		if (rule == null) return true;
		return switch (rule.selector()) {
			case ProcessingRule.Match match -> match.matcher().matches(input);
			case ProcessingRule.Tag tag -> tag.kind() == input.kind() && BuiltInRegistries.ITEM.getHolder(net.minecraft.resources.ResourceKey.create(Registries.ITEM, input.id()))
					.map(holder -> holder.is(TagKey.create(Registries.ITEM, tag.tag()))).orElse(false);
			case ProcessingRule.Goal ignored -> false;
		};
	}
	private RuntimeProcessingMatch() { }
}
