package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

/** 原供应器实例的运行期锁版本；饱和后禁用旧页面解锁，不写入存档。 */
public interface ProviderCraftingLockVersion {
	long productivebeesgenesis$lockVersion();
}
