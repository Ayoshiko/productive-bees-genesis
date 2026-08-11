package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.api.Upgrade;
import mekanism.common.block.attribute.AttributeUpgradeSupport;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
	 * MekanismEmpowered 升级支持构建器
	 * <br/>
	 * <b>类加载安全</b>：本类通过反射访问 MekanismEmpowered 的 API 类
	 * {@code dev.lapis256.mekanism_empowered.api.MekEmpUpgrade}，
	 * 仅在 {@link MekCompatHooks#isMekanismEmpoweredLoaded()} 为 true 时由
	 * {@link MekUpgradeSupport} 委托加载。
	 * 未安装 MekanismEmpowered 时本类不会被加载，避免 NoClassDefFoundError。
	 * <p>
	 * MekanismEmpowered 提供 6 种升级（均为标准 {@link Upgrade} 类型，通过反射获取实例）：
	 * <ul>
	 *   <li>EMPOWERED_SPEED — 强化速度</li>
	 *   <li>EMPOWERED_ENERGY — 强化能量</li>
	 *   <li>IO_CAPACITY — IO 容量</li>
	 *   <li>AUTO_INSERTER — 自动插入器</li>
	 *   <li>FAST_ITEM_INSERT — 快速物品插入</li>
	 *   <li>FAST_ITEM_EJECT — 快速物品弹出</li>
	 * </ul>
	 * <p>
	 * 升级集合分层（与 MekEmp 的 MekEmpUpgrades 一致）：
	 * <ul>
	 *   <li>机器基础：EMPOWERED_SPEED + EMPOWERED_ENERGY + IO_CAPACITY + AUTO_INSERTER</li>
	 *   <li>物品输入机器：机器基础 + FAST_ITEM_INSERT</li>
	 *   <li>物品输出机器：机器基础 + FAST_ITEM_EJECT</li>
	 *   <li>物品输入输出机器：机器基础 + FAST_ITEM_INSERT + FAST_ITEM_EJECT（全 6 种）</li>
	 * </ul>
	 */
final class MekEmpUpgradeSupport {

	/** MekEmpUpgrade 全限定类名（可选依赖，反射访问） */
	private static final String MEK_EMP_UPGRADE_CLASS = "dev.lapis256.mekanism_empowered.api.MekEmpUpgrade";

	// ===== 反射方法缓存（volatile + 双重检查） =====
	private static volatile Class<?> cachedMekEmpUpgradeClass;
	private static volatile Method cachedGetEmpoweredSpeed;
	private static volatile Method cachedGetEmpoweredEnergy;
	private static volatile Method cachedGetIoCapacity;
	private static volatile Method cachedGetAutoInserter;
	private static volatile Method cachedGetFastItemInsert;
	private static volatile Method cachedGetFastItemEject;

	private MekEmpUpgradeSupport() {}

	/**
	 * 创建包含 MekEmp 物品输入输出机器升级的 AttributeUpgradeSupport
	 * <br/>
	 * 适用于离心机（有物品输入和输出）：全部 6 种 MekEmp 升级。
	 * 合并基础原版升级（SPEED/ENERGY/MUFFLING）+ MEKExtras（STACK/CREATIVE，若加载）+ MekEmp 全部升级。
	 *
	 * @param baseUpgrades 基础升级列表（原版 + 可选 MEKExtras），不可为 null
	 * @return 包含 MekEmp 全部升级的 AttributeUpgradeSupport 实例，反射失败时返回 baseUpgrades
	 */
	static AttributeUpgradeSupport createItemInOutMachineUpgrades(List<Upgrade> baseUpgrades) {
		List<Upgrade> combined = new ArrayList<>(baseUpgrades);
		List<Upgrade> mekEmpUpgrades = collectAllUpgrades();
		for (Upgrade u : mekEmpUpgrades) {
			if (!combined.contains(u)) {
				combined.add(u);
			}
		}
		return AttributeUpgradeSupport.create(combined.toArray(new Upgrade[0]));
	}

	/**
	 * 创建包含 MekEmp 物品输出机器升级的 AttributeUpgradeSupport
	 * <br/>
	 * 适用于蜂箱（有物品输出，无管道输入）：机器基础 + FAST_ITEM_EJECT。
	 *
	 * @param baseUpgrades 基础升级列表，不可为 null
	 * @return 包含 MekEmp 输出机器升级的 AttributeUpgradeSupport 实例，反射失败时返回 baseUpgrades
	 */
	static AttributeUpgradeSupport createItemOutputMachineUpgrades(List<Upgrade> baseUpgrades) {
		List<Upgrade> combined = new ArrayList<>(baseUpgrades);
		// 机器基础升级
		addMachineBaseUpgrades(combined);
		// 物品输出额外升级
		Upgrade fastItemEject = invokeGetUpgrade("getFAST_ITEM_EJECT", "FAST_ITEM_EJECT");
		if (fastItemEject != null && !combined.contains(fastItemEject)) {
			combined.add(fastItemEject);
		}
		return AttributeUpgradeSupport.create(combined.toArray(new Upgrade[0]));
	}

	/**
	 * 收集 MekEmp 全部 6 种升级
	 * <br/>
	 * 反射获取每个升级实例，任一获取失败时跳过该升级（不影响其他升级）。
	 *
	 * @return MekEmp 升级列表（可能为空或部分），反射完全失败时返回空列表
	 */
	private static List<Upgrade> collectAllUpgrades() {
		List<Upgrade> upgrades = new ArrayList<>(6);
		addMachineBaseUpgrades(upgrades);
		Upgrade fastItemInsert = invokeGetUpgrade("getFAST_ITEM_INSERT", "FAST_ITEM_INSERT");
		if (fastItemInsert != null && !upgrades.contains(fastItemInsert)) {
			upgrades.add(fastItemInsert);
		}
		Upgrade fastItemEject = invokeGetUpgrade("getFAST_ITEM_EJECT", "FAST_ITEM_EJECT");
		if (fastItemEject != null && !upgrades.contains(fastItemEject)) {
			upgrades.add(fastItemEject);
		}
		return upgrades;
	}

	/**
	 * 添加 MekEmp 机器基础升级（EMPOWERED_SPEED + EMPOWERED_ENERGY + IO_CAPACITY + AUTO_INSERTER）
	 *
	 * @param upgrades 升级列表（会被修改）
	 */
	private static void addMachineBaseUpgrades(List<Upgrade> upgrades) {
		Upgrade empoweredSpeed = invokeGetUpgrade("getEMPOWERED_SPEED", "EMPOWERED_SPEED");
		if (empoweredSpeed != null && !upgrades.contains(empoweredSpeed)) {
			upgrades.add(empoweredSpeed);
		}
		Upgrade empoweredEnergy = invokeGetUpgrade("getEMPOWERED_ENERGY", "EMPOWERED_ENERGY");
		if (empoweredEnergy != null && !upgrades.contains(empoweredEnergy)) {
			upgrades.add(empoweredEnergy);
		}
		Upgrade ioCapacity = invokeGetUpgrade("getIO_CAPACITY", "IO_CAPACITY");
		if (ioCapacity != null && !upgrades.contains(ioCapacity)) {
			upgrades.add(ioCapacity);
		}
		Upgrade autoInserter = invokeGetUpgrade("getAUTO_INSERTER", "AUTO_INSERTER");
		if (autoInserter != null && !upgrades.contains(autoInserter)) {
			upgrades.add(autoInserter);
		}
	}

	/**
	 * 反射调用 MekEmpUpgrade 的静态 getter 方法获取 Upgrade 实例
	 * <br/>
	 * 使用方法缓存避免重复反射查找。任一步骤失败时返回 null 并记录警告日志。
	 *
	 * @param methodName  MekEmpUpgrade 的静态方法名（如 "getEMPOWERED_SPEED"）
	 * @param upgradeName 升级名称（用于日志）
	 * @return Upgrade 实例，反射失败时返回 null
	 */
	@Nullable
	private static Upgrade invokeGetUpgrade(String methodName, String upgradeName) {
		try {
			Class<?> clazz = getCachedMekEmpUpgradeClass();
			if (clazz == null) return null;
			Method method = getCachedMethod(methodName);
			if (method == null) {
				synchronized (MekEmpUpgradeSupport.class) {
					method = getCachedMethod(methodName);
					if (method == null) {
						method = clazz.getMethod(methodName);
						cacheMethod(methodName, method);
					}
				}
			}
			return (Upgrade) method.invoke(null);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("MekanismEmpowered 升级 {} 反射获取失败", upgradeName, e);
			return null;
		}
	}

	/**
	 * 根据 methodName 获取缓存的 Method
	 *
	 * @param methodName 静态方法名
	 * @return 缓存的 Method，未缓存时返回 null
	 */
	@Nullable
	private static Method getCachedMethod(String methodName) {
		return switch (methodName) {
			case "getEMPOWERED_SPEED" -> cachedGetEmpoweredSpeed;
			case "getEMPOWERED_ENERGY" -> cachedGetEmpoweredEnergy;
			case "getIO_CAPACITY" -> cachedGetIoCapacity;
			case "getAUTO_INSERTER" -> cachedGetAutoInserter;
			case "getFAST_ITEM_INSERT" -> cachedGetFastItemInsert;
			case "getFAST_ITEM_EJECT" -> cachedGetFastItemEject;
			default -> null;
		};
	}

	/**
	 * 缓存反射方法到对应的静态字段
	 * <br/>
	 * 根据 methodName 将 Method 对象存入对应的 volatile 静态字段，避免后续反射查找。
	 *
	 * @param methodName 静态方法名
	 * @param method     Method 对象
	 */
	private static void cacheMethod(String methodName, Method method) {
		switch (methodName) {
			case "getEMPOWERED_SPEED" -> cachedGetEmpoweredSpeed = method;
			case "getEMPOWERED_ENERGY" -> cachedGetEmpoweredEnergy = method;
			case "getIO_CAPACITY" -> cachedGetIoCapacity = method;
			case "getAUTO_INSERTER" -> cachedGetAutoInserter = method;
			case "getFAST_ITEM_INSERT" -> cachedGetFastItemInsert = method;
			case "getFAST_ITEM_EJECT" -> cachedGetFastItemEject = method;
			default -> { /* 未知方法名，不缓存 */ }
		}
	}

	/**
	 * 获取缓存的 MekEmpUpgrade 类（双重检查 + volatile）
	 *
	 * @return MekEmpUpgrade 类，未加载时返回 null
	 */
	@Nullable
	private static Class<?> getCachedMekEmpUpgradeClass() {
		Class<?> clazz = cachedMekEmpUpgradeClass;
		if (clazz == null) {
			synchronized (MekEmpUpgradeSupport.class) {
				clazz = cachedMekEmpUpgradeClass;
				if (clazz == null) {
					try {
						clazz = Class.forName(MEK_EMP_UPGRADE_CLASS);
						cachedMekEmpUpgradeClass = clazz;
					} catch (ClassNotFoundException e) {
						// MekEmp 类未加载，返回 null，调用方安全降级
						return null;
					}
				}
			}
		}
		return clazz;
	}
}
