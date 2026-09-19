package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;

/** 仅供离线夹具准备；生产调用方必须跨服务器 tick 观察句柄。 */
final class ProbeOpenAwait {
	static NetworkSavedData await(NetworkDirectory directory, NetworkOpenHandle handle) throws Exception {
		long deadline = System.nanoTime() + 30_000_000_000L;
		while (handle.pending()) {
			DomainProbeServer.require(System.nanoTime() < deadline, "Fixture open timed out: " + handle.failure());
			directory.tick(); Thread.sleep(1);
		}
		return handle.ready();
	}
	private ProbeOpenAwait() { }
}
