package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeFactoryType;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Mekanism兼容性检测工具类
 * <br/>
 * 检测EvolvedMekanism及其附属mod的加载状态，使用Holder模式实现线程安全的懒加载。
 * 反射访问EM的可选类，避免编译期硬依赖导致ClassNotFoundException。
 */
public final class MekCompatHooks {

	/** EMFactoryTier的5个静态字段名（按等级升序） */
	private static final String OVERCLOCKED_FIELD = "OVERCLOCKED";
	private static final String QUANTUM_FIELD = "QUANTUM";
	private static final String DENSE_FIELD = "DENSE";
	private static final String MULTIVERSAL_FIELD = "MULTIVERSAL";
	private static final String CREATIVE_FIELD = "CREATIVE";

	/** EM工厂等级字段名列表（升序，用于反射批量读取） */
	private static final List<String> EM_TIER_FIELD_NAMES = List.of(
			OVERCLOCKED_FIELD, QUANTUM_FIELD, DENSE_FIELD, MULTIVERSAL_FIELD, CREATIVE_FIELD);

	/** ExtraFactoryTier的4个静态字段名（按等级升序：ABSOLUTE(11)→SUPREME(13)→COSMIC(15)→INFINITE(17)） */
	private static final String ME_ABSOLUTE_FIELD = "ABSOLUTE";
	private static final String ME_SUPREME_FIELD = "SUPREME";
	private static final String ME_COSMIC_FIELD = "COSMIC";
	private static final String ME_INFINITE_FIELD = "INFINITE";

	/** ME工厂等级字段名列表（升序，用于反射批量读取） */
	private static final List<String> ME_TIER_FIELD_NAMES = List.of(
			ME_ABSOLUTE_FIELD, ME_SUPREME_FIELD, ME_COSMIC_FIELD, ME_INFINITE_FIELD);

	/** EMExtraFactoryTier的4个静态字段名（按等级升序：ABSOLUTE_OVERCLOCKED(12)→SUPREME_QUANTUM(14)→COSMIC_DENSE(16)→INFINITE_MULTIVERSAL(18)） */
	private static final String EME_ABSOLUTE_OVERCLOCKED_FIELD = "ABSOLUTE_OVERCLOCKED";
	private static final String EME_SUPREME_QUANTUM_FIELD = "SUPREME_QUANTUM";
	private static final String EME_COSMIC_DENSE_FIELD = "COSMIC_DENSE";
	private static final String EME_INFINITE_MULTIVERSAL_FIELD = "INFINITE_MULTIVERSAL";

	/** EME工厂等级字段名列表（升序，用于反射批量读取） */
	private static final List<String> EME_TIER_FIELD_NAMES = List.of(
			EME_ABSOLUTE_OVERCLOCKED_FIELD, EME_SUPREME_QUANTUM_FIELD,
			EME_COSMIC_DENSE_FIELD, EME_INFINITE_MULTIVERSAL_FIELD);

	private final boolean evolvedMekanismLoaded;
	private final boolean mekanismExtrasLoaded;
	private final boolean evolvedMekanismExtrasLoaded;
	private final boolean mekanismEmpoweredLoaded;

	private MekCompatHooks() {
		ModList modList = ModList.get();
		// modList在单元测试环境可能为null，需做空值保护
		if (modList == null) {
			evolvedMekanismLoaded = false;
			mekanismExtrasLoaded = false;
			evolvedMekanismExtrasLoaded = false;
			mekanismEmpoweredLoaded = false;
		} else {
			evolvedMekanismLoaded = modList.isLoaded("evolvedmekanism");
			mekanismExtrasLoaded = modList.isLoaded("mekanism_extras");
			evolvedMekanismExtrasLoaded = modList.isLoaded("emextras");
			mekanismEmpoweredLoaded = modList.isLoaded("mekanism_empowered");
		}
	}

	/** Holder模式 — 线程安全的懒加载，JVM保证类初始化阶段原子性 */
	private static final class Holder {
		static final MekCompatHooks INSTANCE = new MekCompatHooks();
	}

	/** 获取单例实例 */
	public static MekCompatHooks getInstance() {
		return Holder.INSTANCE;
	}

	/** 检测EvolvedMekanism是否加载 */
	public static boolean isEvolvedMekanismLoaded() {
		return Holder.INSTANCE.evolvedMekanismLoaded;
	}

	/** 检测MekanismExtras是否加载 */
	public static boolean isMekanismExtrasLoaded() {
		return Holder.INSTANCE.mekanismExtrasLoaded;
	}

	/** 检测EvolvedMekanismExtras是否加载 */
	public static boolean isEvolvedMekanismExtrasLoaded() {
		return Holder.INSTANCE.evolvedMekanismExtrasLoaded;
	}

	/** 检测MekanismEmpowered是否加载 */
	public static boolean isMekanismEmpoweredLoaded() {
		return Holder.INSTANCE.mekanismEmpoweredLoaded;
	}

	/**
	 * 判断给定tier是否高于或等于EM的OVERCLOCKED等级
	 * <br/>
	 * EM未加载或反射失败时返回false，保证调用方安全。
	 *
	 * @param tier 待比较的Mekanism工厂等级
	 * @return true 如果EM已加载且tier.ordinal >= OVERCLOCKED.ordinal
	 */
	public static boolean isEMTierAboveOverclocked(FactoryTier tier) {
		if (!isEvolvedMekanismLoaded() || tier == null) {
			return false;
		}
		try {
			Class<?> emFactoryTierClass = MekReflectionCache.getCachedEMFactoryTierClass();
			if (emFactoryTierClass == null) return false;
			Field field = MekReflectionCache.getCachedField(emFactoryTierClass, OVERCLOCKED_FIELD);
			FactoryTier overclocked = (FactoryTier) field.get(null);
			return overclocked != null && tier.ordinal() >= overclocked.ordinal();
		} catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
			// 类未加载或字段访问失败，安全降级返回false
			return false;
		}
	}

/**
 * 获取EM的5个工厂等级（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
	 * <br/>
	 * EM未加载时返回空列表。EM加载时通过反射读取EMFactoryTier的5个静态字段，
	 * 返回升序FactoryTier列表（EM通过Mixin扩展FactoryTier枚举），反射失败返回空列表。
	 *
	 * @return EM工厂等级列表（升序），EM未加载或反射失败时返回空列表
	 */
	public static List<FactoryTier> getEMFactoryTiers() {
		if (!isEvolvedMekanismLoaded()) {
			return List.of();
		}
		try {
			Class<?> emFactoryTierClass = MekReflectionCache.getCachedEMFactoryTierClass();
			if (emFactoryTierClass == null) return List.of();
			List<FactoryTier> tiers = new ArrayList<>(EM_TIER_FIELD_NAMES.size());
			for (String fieldName : EM_TIER_FIELD_NAMES) {
				Field field = MekReflectionCache.getCachedField(emFactoryTierClass, fieldName);
				FactoryTier tier = (FactoryTier) field.get(null);
				if (tier != null) {
					tiers.add(tier);
				}
			}
			return tiers;
		} catch (ClassNotFoundException e) {
			// EM类路径变更或类未加载，记录错误日志并安全降级
			ProductiveBeesGenesis.LOGGER.error("EMFactoryTier类未找到，无法获取EM工厂等级", e);
			return List.of();
		} catch (NoSuchFieldException | IllegalAccessException e) {
			// 字段访问失败（字段被移除/重命名/访问限制），记录错误日志并安全降级
			ProductiveBeesGenesis.LOGGER.error("无法访问EMFactoryTier字段，反射获取EM工厂等级失败", e);
			return List.of();
		}
	}

	/**
	 * 获取ME的4个工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE）
	 * <br/>
	 * ME未加载时返回空列表。ME加载时通过反射读取ExtraFactoryTier的4个静态字段，
	 * 返回升序ExtraFactoryTier列表（ME使用独立枚举）。返回类型为List<Object>，调用方需自行类型转换。
	 *
	 * @return ME工厂等级列表（升序，元素类型为ExtraFactoryTier），ME未加载或反射失败时返回空列表
	 */
	public static List<Object> getMEFactoryTiers() {
		if (!isMekanismExtrasLoaded()) {
			return List.of();
		}
		try {
			Class<?> meFactoryTierClass = MekReflectionCache.getCachedMEFactoryTierClass();
			if (meFactoryTierClass == null) return List.of();
			List<Object> tiers = new ArrayList<>(ME_TIER_FIELD_NAMES.size());
			for (String fieldName : ME_TIER_FIELD_NAMES) {
				Field field = MekReflectionCache.getCachedField(meFactoryTierClass, fieldName);
				Object tier = field.get(null);
				if (tier != null) {
					tiers.add(tier);
				}
			}
			return tiers;
		} catch (ClassNotFoundException e) {
			// ME类路径变更或类未加载，记录错误日志并安全降级
			ProductiveBeesGenesis.LOGGER.error("ExtraFactoryTier类未找到，无法获取ME工厂等级", e);
			return List.of();
		} catch (NoSuchFieldException | IllegalAccessException e) {
			// 字段访问失败（字段被移除/重命名/访问限制），记录错误日志并安全降级
			ProductiveBeesGenesis.LOGGER.error("无法访问ExtraFactoryTier字段，反射获取ME工厂等级失败", e);
			return List.of();
		}
	}

	/**
	 * 判断给定对象是否为ME的ExtraFactoryTier枚举实例
	 * <br/>
	 * ME未加载时返回false。ME加载时通过反射加载ExtraFactoryTier类，
	 * 检查给定对象的运行时类是否为ExtraFactoryTier的实例。
	 * 反射失败（类路径变更）时返回false，保证调用方安全。
	 *
	 * @param tier 待检测的对象
	 * @return true 如果ME已加载且tier为ExtraFactoryTier枚举实例
	 */
	public static boolean isMETier(Object tier) {
		if (!isMekanismExtrasLoaded() || tier == null) {
			return false;
		}
		try {
			Class<?> meFactoryTierClass = MekReflectionCache.getCachedMEFactoryTierClass();
			if (meFactoryTierClass == null) return false;
			return meFactoryTierClass.isInstance(tier);
		} catch (ClassNotFoundException e) {
			// ME类路径变更或类未加载，安全降级返回false（调用方已有降级处理）
			return false;
		}
	}

	// ======================== EME (EvolvedMekanismExtras) 反射方法 ========================

	/**
	 * 获取EME的4个工厂等级（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL）
	 * <br/>
	 * EME未加载时返回空列表。EME加载时通过反射读取EMExtraFactoryTier的4个静态字段，
	 * 返回按等级升序排列的EMExtraFactoryTier列表（EME使用独立枚举，不扩展Mekanism的FactoryTier）。
	 * 由于EMExtraFactoryTier在编译时不存在，返回类型为List<Object>，调用方需自行类型转换。
	 * 反射失败时记录error日志并返回空列表，保证调用方安全。
	 *
	 * @return EME工厂等级列表（升序，元素类型为EMExtraFactoryTier），EME未加载或反射失败时返回空列表
	 */
	public static List<Object> getEMEFactoryTiers() {
		if (!isEvolvedMekanismExtrasLoaded()) {
			return List.of();
		}
		try {
			Class<?> emeFactoryTierClass = MekReflectionCache.getCachedEMEFactoryTierClass();
			if (emeFactoryTierClass == null) return List.of();
			List<Object> tiers = new ArrayList<>(EME_TIER_FIELD_NAMES.size());
			for (String fieldName : EME_TIER_FIELD_NAMES) {
				Field field = MekReflectionCache.getCachedField(emeFactoryTierClass, fieldName);
				Object tier = field.get(null);
				if (tier != null) {
					tiers.add(tier);
				}
			}
			return tiers;
		} catch (ClassNotFoundException e) {
			// EME类路径变更或类未加载，记录错误日志并安全降级
			ProductiveBeesGenesis.LOGGER.error("EMExtraFactoryTier类未找到，无法获取EME工厂等级", e);
			return List.of();
		} catch (NoSuchFieldException | IllegalAccessException e) {
			// 字段访问失败（字段被移除/重命名/访问限制），记录错误日志并安全降级
			ProductiveBeesGenesis.LOGGER.error("无法访问EMExtraFactoryTier字段，反射获取EME工厂等级失败", e);
			return List.of();
		}
	}

	/**
	 * 判断给定对象是否为EME的EMExtraFactoryTier枚举实例
	 * <br/>
	 * EME未加载时返回false。EME加载时通过反射加载EMExtraFactoryTier类，
	 * 检查给定对象的运行时类是否为EMExtraFactoryTier的实例。
	 * 反射失败（类路径变更）时返回false，保证调用方安全。
	 *
	 * @param tier 待检测的对象
	 * @return true 如果EME已加载且tier为EMExtraFactoryTier枚举实例
	 */
	public static boolean isEMETier(Object tier) {
		if (!isEvolvedMekanismExtrasLoaded() || tier == null) {
			return false;
		}
		try {
			Class<?> emeFactoryTierClass = MekReflectionCache.getCachedEMEFactoryTierClass();
			if (emeFactoryTierClass == null) return false;
			return emeFactoryTierClass.isInstance(tier);
		} catch (ClassNotFoundException e) {
			// EME类路径变更或类未加载，安全降级返回false（调用方已有降级处理）
			return false;
		}
	}

	// ======================== 配置卡兼容性检查 ========================

	/**
	 * 配置卡兼容性检查 — Attribute 属性匹配
	 * <br/>
	 * MekanismUtils.isSameTypeFactory() 无法识别 ME/EME 工厂方块（无 AttributeFactoryType 或有 EMExtraAttributeFactoryType），
	 * 导致配置卡粘贴失败。此方法补充检查 AttributeFactoryType 和 EMExtraAttributeFactoryType，
	 * 允许同类型（如SMELTING）的工厂跨等级粘贴配置。
	 * <p>
	 * 调用方需先调用 super.isConfigurationDataCompatible(blockType)，本方法只处理额外的 Attribute 检查，
	 * 两者通过短路或合并：
	 * <pre>{@code
	 * return super.isConfigurationDataCompatible(blockType)
	 *     || MekCompatHooks.isConfigurationDataCompatible(getBlockHolder(), blockType);
	 * }</pre>
	 *
	 * @param selfHolder 当前工厂方块的 Holder
	 * @param blockType  待比较的目标方块
	 * @return true 如果两个方块有相同类型的 AttributeFactoryType 或 EMExtraAttributeFactoryType
	 */
	public static boolean isConfigurationDataCompatible(net.minecraft.core.Holder<Block> selfHolder, Block blockType) {
		// 原版/EM工厂同类型检查（有AttributeFactoryType属性）
		AttributeFactoryType meType = Attribute.get(selfHolder, AttributeFactoryType.class);
		if (meType != null) {
			AttributeFactoryType otherType = Attribute.get(blockType, AttributeFactoryType.class);
			if (otherType != null && meType.getFactoryType() == otherType.getFactoryType()) {
				return true;
			}
		}
		// EME工厂同类型检查 — 仅在 EME 已加载时通过反射执行，避免编译期硬依赖 EMExtraAttributeFactoryType
		// 反射调用 Attribute.get(selfHolder, emeAttrClass) 和 getFactoryType()，异常时安全降级返回 false
		if (isEvolvedMekanismExtrasLoaded()) {
			try {
				Class<?> emeAttrClass = MekReflectionCache.getCachedEMEAttributeFactoryTypeClass();
				Method attributeGet = MekReflectionCache.getCachedAttributeGetMethod();
				if (emeAttrClass != null && attributeGet != null) {
					Object emeType = attributeGet.invoke(null, selfHolder, emeAttrClass);
					if (emeType != null) {
						Object otherType = attributeGet.invoke(null, blockType, emeAttrClass);
						if (otherType != null) {
							Method getFactoryType = MekReflectionCache.getCachedEMEGetFactoryTypeMethod(emeType.getClass());
							if (getFactoryType != null) {
								Object emeFactoryType = getFactoryType.invoke(emeType);
								Object otherFactoryType = getFactoryType.invoke(otherType);
								// 反射返回 Object，用 equals 比较枚举值（而非 ==）
								if (emeFactoryType != null && emeFactoryType.equals(otherFactoryType)) {
									return true;
								}
							}
						}
					}
				}
			} catch (Exception e) {
				// EME 类路径变更或反射失败，安全降级返回 false
				ProductiveBeesGenesis.LOGGER.error("EME Attribute 反射检查失败，配置卡兼容性检查降级", e);
			}
		}
		return false;
	}
}
