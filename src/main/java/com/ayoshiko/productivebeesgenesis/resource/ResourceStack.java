package com.ayoshiko.productivebeesgenesis.resource;

import java.util.Objects;

/** 不可变的通用资源堆栈；数量使用原始单位，不持有外部可变栈或可兑付物品。 */
public record ResourceStack(ResourceKey key, java.math.BigInteger exactAmount) {
    public ResourceStack(ResourceKey key, long amount) { this(key, java.math.BigInteger.valueOf(amount)); }
    public long amount() { return exactAmount.longValueExact(); }
	public ResourceStack {
		Objects.requireNonNull(key);
		if (exactAmount == null || exactAmount.signum() <= 0) throw new IllegalArgumentException("Resource stack amount must be positive");
	}

	/** 将模板用于当前资源配方；新生产仍会核验依赖、类型和完整键身份。 */
	public ResourceProductionRecipe.Output output(double chance) {
		return new ResourceProductionRecipe.Output(key, exactAmount, chance);
	}
}
