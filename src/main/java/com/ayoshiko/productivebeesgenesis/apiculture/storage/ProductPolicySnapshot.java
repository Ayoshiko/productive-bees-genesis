package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
	private final int descriptorCount;
	private final Map<ProductKey, Boolean> generated;

	public ProductPolicySnapshot(long revision, Collection<AllowedProductDescriptor> products, Collection<DynamicProductRule> dynamic) {
		if (revision < 0) throw new IllegalArgumentException("Negative policy revision");
		this.revision = revision;
		Map<ProductKey, List<AllowedProductDescriptor>> byProduct = new ConcurrentHashMap<>();
		for (var product : products) byProduct.computeIfAbsent(AllowedProductDescriptor.policyIdentity(product.template()), ignored -> new ArrayList<>()).add(product);
		byProduct.replaceAll((key, values) -> List.copyOf(values));
		this.products = Map.copyOf(byProduct);
		this.descriptorCount = products.size();
		Map<Domain, List<DynamicProductRule>> byDynamic = new ConcurrentHashMap<>();
		var ids = ConcurrentHashMap.<String>newKeySet();
		for (var rule : dynamic) {
			if (!ids.add(rule.adapterId())) throw new IllegalArgumentException("Duplicate dynamic rule id");
			byDynamic.computeIfAbsent(new Domain(rule.kind(), rule.id()), ignored -> new ArrayList<>()).add(rule);
		}
		byDynamic.replaceAll((key, values) -> List.copyOf(values));
		this.dynamic = Map.copyOf(byDynamic);
		this.generated = Map.of();
	}
	private ProductPolicySnapshot(long revision, Map<ProductKey, List<AllowedProductDescriptor>> products,
			Map<Domain, List<DynamicProductRule>> dynamic, int descriptorCount) {
		this(revision, products, dynamic, descriptorCount, Map.of());
	}
	private ProductPolicySnapshot(long revision, Map<ProductKey, List<AllowedProductDescriptor>> products,
			Map<Domain, List<DynamicProductRule>> dynamic, int descriptorCount, Map<ProductKey, Boolean> generated) {
		if (revision < 0) throw new IllegalArgumentException("Negative policy revision");
		this.revision = revision; this.products = products; this.dynamic = dynamic; this.descriptorCount = descriptorCount;
		this.generated = generated;
	}
	/** 同一配方代际的静态目录共享不可变索引，网络 revision 不触发重复编译。 */
	public ProductPolicySnapshot withRevision(long revision) {
		return revision == this.revision ? this : new ProductPolicySnapshot(revision, products, dynamic, descriptorCount, generated);
	}
	/** 仅由真实服务器生成入口提交完整键；不枚举物品注册表，也不扩大组件匹配。 */
	public ProductPolicySnapshot withVerifiedGenerated(Collection<ProductKey> keys) {
		var next = SnapshotRecords.fork(generated, java.util.Comparator.comparingInt(ProductKey::hashCode).thenComparing(ProductKey::orderingKey));
		boolean changed = false;
		for (var key : keys) if (!generated.containsKey(key)) {
			next.put(key, true);
			changed = true;
		}
		return changed ? new ProductPolicySnapshot(revision, products, dynamic, descriptorCount, next.snapshot()) : this;
	}
	public boolean verifiedGenerated(ProductKey key) { return generated.containsKey(key); }
	public boolean accepts(ProductKey key) {
		return verifiedGenerated(key) || descriptors(key).stream().anyMatch(rule -> rule.accepts(key));
	}
	/** 单次使用的构建器；发布转移私有索引所有权，不在最后一步复制整个目录。 */
	public static final class Builder {
		private final Thread owner = Thread.currentThread();
		private Map<ProductKey, List<AllowedProductDescriptor>> products = new ConcurrentHashMap<>();
		private int count;
		public void add(AllowedProductDescriptor descriptor) {
			checkOpen();
			products.computeIfAbsent(AllowedProductDescriptor.policyIdentity(descriptor.template()), ignored -> new ArrayList<>()).add(descriptor);
			count = Math.incrementExact(count);
		}
		public ProductPolicySnapshot build(long revision) {
			checkOpen();
			var result = new ProductPolicySnapshot(revision, Collections.unmodifiableMap(products), Map.of(), count);
			products = null; return result;
		}
		private void checkOpen() {
			if (Thread.currentThread() != owner || products == null) throw new IllegalStateException("Policy builder is closed or belongs to another thread");
		}
	}
	public long revision() { return revision; }
	public List<AllowedProductDescriptor> descriptors(ProductKey key) { return Collections.unmodifiableList(products.getOrDefault(AllowedProductDescriptor.policyIdentity(key), List.of())); }
	public List<DynamicProductRule> dynamicRules(ProductKey key) { return dynamic.getOrDefault(Domain.of(key), List.of()); }
	public int descriptorCount() { return descriptorCount; }
}
