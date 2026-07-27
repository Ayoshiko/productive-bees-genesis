package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.DevLog;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/**
 * 蜜蜂 NBT 数据解析工具类
 * <br/>
 * 从蜜蜂 NBT 数据解析 EntityType 和蜜蜂类型键，供蜂箱各组件统一调用。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：仅做蜜蜂 NBT 解析，不涉及槽位或产出逻辑</li>
 *   <li>无状态：纯静态方法，线程安全</li>
 * </ul>
 * <p>
 * NBT 格式兼容：
 * <ul>
 *   <li>PB Occupant 格式："id" 字段存储实体类型注册名</li>
 *   <li>PB 蜂笼格式："entity" 字段存储实体类型注册名字符串，"type" 字段存储 ConfigurableBee 具体类型</li>
 * </ul>
 */
public final class BeeNbtHelper {

	/** 工具类禁止实例化 */
	private BeeNbtHelper() {
	}

	/**
	 * 从 beeData NBT 解析蜜蜂 EntityType
	 * <br/>
	 * 兼容两种 NBT 格式：
	 * <ol>
	 *   <li>PB Occupant 格式：通过 "id" 字段查询（{@link EntityType#by}）</li>
	 *   <li>PB 蜂笼格式：通过 "entity" 字段字符串查询（{@link EntityType#byString}）</li>
	 * </ol>
	 * 蜂笼格式没有 "id" 字段，实体类型以字符串存在 "entity" 字段中。
	 *
	 * @param beeData 蜜蜂 NBT 数据
	 * @return EntityType 实例，解析失败或入参为 null 时返回 null
	 */
	public static EntityType<?> resolveEntityType(CompoundTag beeData) {
		if (beeData == null) return null;
		try {
			// 先按 PB Occupant 格式（"id" 字段）尝试
			EntityType<?> byId = EntityType.by(beeData).orElse(null);
			if (byId != null) return byId;
			// 兜底按蜂笼格式（"entity" 字符串字段）查询
			if (beeData.contains("entity")) {
				String entityKey = beeData.getString("entity");
				if (!entityKey.isEmpty()) {
					return EntityType.byString(entityKey).orElse(null);
				}
			}
			return null;
		} catch (Exception e) {
			// DevLog 节流日志便于排查（蜜蜂 NBT 解析路径，避免刷屏）
			DevLog.warn("bee_nbt", "解析蜜蜂 EntityType 失败: {}", e.toString());
			return null;
		}
	}

	/**
	 * 解析蜜蜂类型键（用于 BeeReloadListener 查询和配方查询）
	 * <br/>
	 * ConfigurableBee 的 EntityType 永远是 productivebees:configurable_bee，
	 * 但具体蜜蜂类型（如 productivebees:iron）存储在 "type" 字段中。
	 * 必须用具体类型键查询 BeeReloadListener 才能获取正确的花朵偏好和产出配方。
	 * <p>
	 * 解析优先级：
	 * <ol>
	 *   <li>"type" 字段 — ConfigurableBee 的具体类型（如 productivebees:iron）</li>
	 *   <li>"entity" 字段 — 蜂笼格式的实体类型注册名（如 minecraft:bee）</li>
	 *   <li>"id" 字段 — Occupant 格式的实体类型注册名</li>
	 * </ol>
	 *
	 * @param beeData 蜜蜂 NBT 数据
	 * @return 蜜蜂类型键 ResourceLocation，解析失败返回 null
	 */
	public static ResourceLocation resolveBeeTypeKey(CompoundTag beeData) {
		if (beeData == null) return null;
		// 优先：ConfigurableBee 的 "type" 字段（具体蜜蜂类型）
		if (beeData.contains("type")) {
			String type = beeData.getString("type");
			if (!type.isEmpty()) {
				ResourceLocation rl = ResourceLocation.tryParse(type);
				if (rl != null) return rl;
			}
		}
		// 兜底1：蜂笼格式的 "entity" 字段
		if (beeData.contains("entity")) {
			String entity = beeData.getString("entity");
			if (!entity.isEmpty()) {
				ResourceLocation rl = ResourceLocation.tryParse(entity);
				if (rl != null) return rl;
			}
		}
		// 兜底2：Occupant 格式的 "id" 字段
		if (beeData.contains("id")) {
			String id = beeData.getString("id");
			if (!id.isEmpty()) {
				ResourceLocation rl = ResourceLocation.tryParse(id);
				if (rl != null) return rl;
			}
		}
		return null;
	}
}
