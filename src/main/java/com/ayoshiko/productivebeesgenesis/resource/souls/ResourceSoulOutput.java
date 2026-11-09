package com.ayoshiko.productivebeesgenesis.resource.souls;

import com.ayoshiko.productivebeesgenesis.resource.ResourceScalarOutput;
import com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler;

/** 同质灵魂的只出不入能力；唯一虚拟罐直接读取原账户，不保存镜像。 */
public final class ResourceSoulOutput implements ISoulHandler {
	private final ResourceScalarOutput access;

	/** 只由有依赖守卫的资源产物接口创建。 */
	public ResourceSoulOutput(ResourceScalarOutput access) { this.access = access; }

	@Override public int getSoulTanks() { return 1; }
	@Override public int getSoulInTank(int tank) {
		return tank == 0 ? (int) Math.min(Integer.MAX_VALUE, access.stored()) : 0;
	}
	@Override public int getTankCapacity(int tank) {
		return tank == 0 ? (int) Math.min(Integer.MAX_VALUE, access.capacity()) : 0;
	}
	@Override public int fill(int amount, Action action) { return 0; }
	@Override public int drain(int amount, Action action) {
		return action == null ? 0 : (int) access.extract(amount, action.simulate());
	}
}
