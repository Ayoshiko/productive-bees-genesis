package com.ayoshiko.productivebeesgenesis.resource;

import java.util.Objects;

/** 数值资源的只读／提取视图；可选能力只能委托本类，不取得可写账户或外部提交回调。 */
public final class ResourceScalarOutput {
	private ResourceOutputAccess port;
	private final ResourceKey key;

	/** 使用固定不可变身份绑定视图；所有世界访问仍经产物口校验服务器线程和代际。 */
	public ResourceScalarOutput(ResourceOutputAccess port, ResourceKey key) {
		this.port = Objects.requireNonNull(port);
		this.key = Objects.requireNonNull(key);
		if (!ResourceTypes.declared(key)) throw new IllegalArgumentException("Unsupported native resource identity");
	}

	/** 撤销缓存能力，不再持有世界实体。 */
	public void invalidate() { port = null; }

	private ResourceAccount account() {
		var account = port == null ? null : port.account();
		var type = ResourceNativeType.of(key);
		if (type != null && (port == null || !port.outputAllowed(type))) return null;
		return account != null && account.canExtract(port.reference(), key) && ResourceTypes.available(key) ? account : null;
	}

	/** 返回可用原始单位；未决交接／无效来源／缺少资源依赖均不可提取。 */
	public long stored() { return storedExact().min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(); }

    public java.math.BigInteger storedExact() {
        var account = account();
        return account == null ? java.math.BigInteger.ZERO : account.availableExact().getOrDefault(key, java.math.BigInteger.ZERO);
    }

    public java.math.BigInteger extractExact(java.math.BigInteger maximum, boolean simulate) {
        var account = account();
        if (account == null || maximum.signum() <= 0) return java.math.BigInteger.ZERO;
        var amount = maximum.min(account.availableExact().getOrDefault(key, java.math.BigInteger.ZERO));
        if (!simulate && amount.signum() > 0) account.extractExact(port.reference(), key, amount);
        return amount;
    }

	/** 返回当前账户的单身份容量，不授予修改容量的能力。 */
	public long capacity() { return account() == null ? 0 : port.capacity(); }

	/** 是否处于合法、可提取状态；不要求当前余额非零。 */
	public boolean canExtract() { return account() != null; }

	/** 原生拉取不调用外部代码；模拟只读，实际扣除与返回量完全相同。 */
	public long extract(long maximum, boolean simulate) {
		var account = account();
		if (account == null || maximum <= 0) return 0;
		long amount = Math.min(maximum, account.available().getOrDefault(key, 0L));
		if (!simulate && amount > 0) account.extract(port.reference(), key, amount);
		return amount;
	}
}
