package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternProviderTarget;
import java.util.Set;

/**
 * 为本模组离心机补充样板原料可见性及共享提交次数预算，实际插入仍委托 AE2 原路径。
 *
 * <p>这是可选 AE2 兼容层的内部适配器，不是供其他模组调用的公共 API。</p>
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CentrifugePatternProviderTarget implements PatternProviderTarget {

	private final CentrifugeExternalAeStorage storage;
	private final PatternProviderTarget delegate;

	private CentrifugePatternProviderTarget(CentrifugeExternalAeStorage storage, PatternProviderTarget delegate) {
		this.storage = storage;
		this.delegate = delegate;
	}

	/**
	 * 在目标存储属于本模组离心机时附加原料检测，否则返回原目标。
	 *
	 * @param storage 样板供应器当前连接的 ME 存储
	 * @param target AE2 原生样板供应目标
	 * @return 仅在本模组离心机存储上包装后的目标
	 */
	public static PatternProviderTarget wrap(MEStorage storage, PatternProviderTarget target) {
		return storage instanceof CentrifugeExternalAeStorage centrifuge
				? new CentrifugePatternProviderTarget(centrifuge, target) : target;
	}

	/** {@inheritDoc} */
	@Override
	public long insert(AEKey what, long amount, Actionable mode) {
		// 模拟始终返回真实容量；仅限制真实提交的调用次数，不裁剪第三方选定的批次大小。
		// AE2 将零接收的原料保留在 sendList，供应器 busy 后停止继续派单，下一真实刻可恢复。
		if (mode == Actionable.MODULATE && amount > 0 && CentrifugeDispatchScope.externalPushOverBudget()) {
			return 0L;
		}
		return delegate.insert(what, amount, mode);
	}

	/** {@inheritDoc} */
	@Override
	public boolean containsPatternInput(Set<AEKey> patternInputs) {
		return storage.containsPatternInput(patternInputs) || delegate.containsPatternInput(patternInputs);
	}
}
