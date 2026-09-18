package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** 仅由服务端适配器编译的无副作用结果谓词，禁止捕获 Level／可变物品或客户端声明。 */
public record DynamicProductRule(String adapterId, ProductKey.Kind kind, ResourceLocation id,
		boolean requiresDiscovery, Predicate<ProductKey> validator) {
	public DynamicProductRule {
		if (adapterId == null || adapterId.isBlank()) throw new IllegalArgumentException("Missing adapter id");
		Objects.requireNonNull(kind);
		Objects.requireNonNull(id);
		Objects.requireNonNull(validator);
	}
	public boolean matchesDomain(ProductKey key) { return kind == key.kind() && id.equals(key.id()); }
}
