package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/** 一次配方重载编译出的不可变索引，按注册类型定位，查询不扫描所有配方。 */
public final class ProductPolicySnapshot {
	private record Domain(ProductKey.Kind kind, ResourceLocation id) {
		static Domain of(ProductKey key) { return new Domain(key.kind(), key.id()); }
	}
	private final long revision;
	private final Map<ProductKey, List<AllowedProductDescriptor>> products;
	private final Map<Domain, List<DynamicProductRule>> dynamic;

	public ProductPolicySnapshot(long revision, Collection<AllowedProductDescriptor> products, Collection<DynamicProductRule> dynamic) {
		if (revision < 0) throw new IllegalArgumentException("Negative policy revision");
		this.revision = revision;
		Map<ProductKey, List<AllowedProductDescriptor>> byProduct = new ConcurrentHashMap<>();
		for (var product : products) byProduct.computeIfAbsent(AllowedProductDescriptor.policyIdentity(product.template()), ignored -> new ArrayList<>()).add(product);
		byProduct.replaceAll((key, values) -> List.copyOf(values));
		this.products = Map.copyOf(byProduct);
		Map<Domain, List<DynamicProductRule>> byDynamic = new ConcurrentHashMap<>();
		var ids = ConcurrentHashMap.<String>newKeySet();
		for (var rule : dynamic) {
			if (!ids.add(rule.adapterId())) throw new IllegalArgumentException("Duplicate dynamic rule id");
			byDynamic.computeIfAbsent(new Domain(rule.kind(), rule.id()), ignored -> new ArrayList<>()).add(rule);
		}
		byDynamic.replaceAll((key, values) -> List.copyOf(values));
		this.dynamic = Map.copyOf(byDynamic);
	}
	public long revision() { return revision; }
	public List<AllowedProductDescriptor> descriptors(ProductKey key) { return products.getOrDefault(AllowedProductDescriptor.policyIdentity(key), List.of()); }
	public List<DynamicProductRule> dynamicRules(ProductKey key) { return dynamic.getOrDefault(Domain.of(key), List.of()); }
	public int descriptorCount() { return products.values().stream().mapToInt(List::size).sum(); }
}
