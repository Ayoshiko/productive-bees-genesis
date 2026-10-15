package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;

/** 一项配方分布；同轮的物品共享生产力轮数，独立流体按完成周期计量。 */
public record BeeOutputPlan(ProductKey source, ProductKey output, int minimum, int maximum, float chance, Basis basis) {
	public enum Basis { ROLL, CYCLE }
	public BeeOutputPlan {
		Objects.requireNonNull(source); Objects.requireNonNull(output); Objects.requireNonNull(basis);
		if (source.kind() != output.kind() || minimum < 0 || maximum < minimum || !Float.isFinite(chance) || chance < 0 || chance > 1
				|| basis == Basis.ROLL && output.kind() != ProductKey.Kind.ITEM
				|| basis == Basis.CYCLE && (minimum != maximum || chance != 1 || !source.equals(output)))
			throw new IllegalArgumentException("Invalid bee output distribution");
	}
	public boolean deterministic() { return chance == 0 || chance == 1 && minimum == maximum; }
	public BeeOutputPlan mapped(ProductKey key) { return output.equals(key) ? this : new BeeOutputPlan(source, key, minimum, maximum, chance, basis); }
	public boolean sameRecipe(BeeOutputPlan other) { return source.equals(other.source) && minimum == other.minimum && maximum == other.maximum && chance == other.chance && basis == other.basis; }
}
