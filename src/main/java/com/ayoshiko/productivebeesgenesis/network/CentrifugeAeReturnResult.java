package com.ayoshiko.productivebeesgenesis.network;

/** AE2 返还结果；保持纯 Java/Minecraft 类型，供可选依赖边界两侧安全传递。 */
record CentrifugeAeReturnResult(boolean online, long returnedToAe, long pending, boolean retryFailed) {

	static CentrifugeAeReturnResult offline() {
		return new CentrifugeAeReturnResult(false, 0L, 0L, false);
	}

	static CentrifugeAeReturnResult online(long returnedToAe, long pending) {
		return online(returnedToAe, pending, false);
	}

	static CentrifugeAeReturnResult online(long returnedToAe, long pending, boolean retryFailed) {
		return new CentrifugeAeReturnResult(true, Math.max(0L, returnedToAe), Math.max(0L, pending), retryFailed);
	}
}
