package com.ayoshiko.productivebeesgenesis.resource;

/** 只有全部 PB 资源注入点通过变换后检查，目标类才返回就绪。 */
public interface PbResourceBridge {
	/** 由 Mixin 合同检查设置，不能以单个事件钩子存在推断资源桥完整。 */
	boolean productivebeesgenesis$resourceBridgeReady();
	/** 子类物品延迟钩子必须另外通过严格注入。 */
	default boolean productivebeesgenesis$mixedBridgeReady() { return false; }
}
