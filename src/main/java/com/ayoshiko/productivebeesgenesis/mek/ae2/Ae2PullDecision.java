package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** 单轮筛选的可复用结果，不跨配置变更或库存快照缓存。 */
final class Ae2PullDecision {

	boolean admitted;
	boolean marked;
	boolean unlimited;
	long reserveFloor = -1L;
	void set(boolean admitted, boolean marked, boolean unlimited, long reserveFloor) {
		this.admitted = admitted;
		this.marked = marked;
		this.unlimited = unlimited;
		this.reserveFloor = reserveFloor;
	}
}
