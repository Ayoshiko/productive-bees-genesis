package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeProductionEffects;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import cy.jdkdigital.productivebees.common.item.Gene;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** 在世界线程从真实蜂数据和唯一转换索引编译纯周期计划。 */
public final class ApiaryProductionEffects {
	public record Upgrades(int samplers, boolean typeOnly, boolean fullPurity, boolean essence, boolean discard) {
		public static final Upgrades NONE = new Upgrades(0, false, false, false, false);
		public Upgrades {
			if (samplers < 0) throw new IllegalArgumentException("Negative gene sampler count");
		}
		public static Upgrades from(Map<PbUpgradeType, Integer> counts) {
			return new Upgrades(counts.getOrDefault(PbUpgradeType.GENE_SAMPLER, 0),
					counts.getOrDefault(PbUpgradeType.GENE_TYPE_ONLY, 0) > 0,
					counts.getOrDefault(PbUpgradeType.GENE_FULL_PURITY, 0) > 0,
					counts.getOrDefault(PbUpgradeType.ESSENCE_CONVERSION, 0) > 0,
					counts.getOrDefault(PbUpgradeType.USELESS_BYPRODUCT, 0) > 0);
		}
	}
	public static BeeProductionEffects compile(ServerLevel level, Upgrades upgrades, CompoundTag bee,
			String type, List<ProductKey> outputs) {
		var genes = new ArrayList<ProductKey>();
		float chance = 0;
		if (upgrades.samplers() > 0 && bee.getInt("Age") >= 0) {
			double configured = ProductiveBeesConfig.UPGRADES.samplerChance.get();
			if (!Double.isFinite(configured)) throw new IllegalArgumentException("Invalid gene sampler probability");
			chance = (float) Math.max(0, Math.min(1, configured * upgrades.samplers()));
			if (chance > 0) {
				var profile = GeneSampleProfile.fromBeeData(bee);
				for (var attribute : GeneAttribute.values()) {
					if (upgrades.typeOnly() && attribute != GeneAttribute.TYPE) continue;
					String value = attribute == GeneAttribute.TYPE ? type : profile.value(attribute).getSerializedName();
					int purities = upgrades.fullPurity() ? 1 : 4;
					for (int index = 0; index < purities; index++)
						genes.add(ProductKeyCodec.item(Gene.getStack(attribute, value, 1,
								upgrades.fullPurity() ? 100 : index + 1), level.registryAccess()));
				}
			}
		}
		var effects = new BeeProductionEffects(upgrades.essence(), upgrades.discard(), chance, genes, List.of(), java.util.Set.of());
		if (!effects.enabled()) return effects;
		return rules(level, effects, outputs);
	}
	public static BeeProductionEffects rules(ServerLevel level, BeeProductionEffects effects, List<ProductKey> outputs) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Compile bee effects on the server thread");
		var removed = new HashSet<>(effects.discarded());
		var conversions = new LinkedHashMap<ProductKey, BeeProductionEffects.Conversion>();
		for (var rule : effects.conversions()) conversions.put(rule.source(), rule);
		var snapshot = effects.essence() ? EssenceConversionRecipeIndex.requireSnapshot(level) : null;
		for (var key : outputs) {
			if (key.kind() != ProductKey.Kind.ITEM) {
				if (effects.discardByproducts()) removed.add(key);
				continue;
			}
			var stack = ProductKeyCodec.item(key, 1, level.registryAccess());
			if (effects.discardByproducts() && UselessByproductUpgradeHelper.isPollenPuff(stack)) removed.add(key);
			if (!effects.essence()) continue;
			var rule = snapshot.find(stack);
			if (rule == null) continue;
			conversions.putIfAbsent(key, new BeeProductionEffects.Conversion(key,
					ProductKeyCodec.item(rule.result(), level.registryAccess()), rule.inputCount(), rule.result().getCount(), rule.continueChain()));
			if (rule.continueChain()) {
				var next = snapshot.find(rule.result());
				if (next != null) {
					var source = ProductKeyCodec.item(rule.result(), level.registryAccess());
					conversions.putIfAbsent(source, new BeeProductionEffects.Conversion(source,
							ProductKeyCodec.item(next.result(), level.registryAccess()), next.inputCount(), next.result().getCount(), false));
				}
			}
		}
		return effects.withRules(List.copyOf(conversions.values()), removed);
	}
	private ApiaryProductionEffects() { }
}
