package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.BeeNbtHelper;

import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 蜜蜂实体工厂
 * <br/>
 * 从蜜蜂 NBT 数据创建对应的渲染用实体实例。
 * 支持 PB 的 ConfigurableBee（按 type 字段配置外观）和原版 Bee。
 * <p>
 * 设计原则：单一职责，仅负责实体创建与初始化，不涉及缓存或渲染。
 * 工厂模式便于后续扩展其他蜜蜂类型（如附属模组自定义蜜蜂）。
 */
public final class BeeEntityFactory {

	/** 工具类禁止实例化 */
	private BeeEntityFactory() {
	}

	/**
	 * 从蜜蜂 NBT 数据创建渲染用实体
	 * <br/>
	 * 流程：
	 * <ol>
	 *   <li>通过 {@link BeeNbtHelper} 解析 EntityType</li>
	 *   <li>调用 entityType.create(level) 创建实体</li>
	 *   <li>若为 ConfigurableBee，读取 type 字段设置蜜蜂类型与默认属性</li>
	 * </ol>
	 * 失败时回退到原版 Bee 实体，确保渲染不中断。
	 *
	 * @param beeData 蜜蜂 NBT 数据（含 id 和可选的 type 字段）
	 * @param level   世界实例
	 * @return 渲染用实体实例，极端情况（level 为 null）返回 null
	 */
	public static Entity createBeeEntity(CompoundTag beeData, Level level) {
		if (beeData == null || level == null) {
			return createFallbackBee(level);
		}
		try {
			EntityType<?> entityType = BeeNbtHelper.resolveEntityType(beeData);
			if (entityType != null) {
				Entity entity = entityType.create(level);
				if (entity instanceof ConfigurableBee configurableBee) {
					configureBeeType(configurableBee, beeData);
				}
				if (entity != null) return entity;
			}
		} catch (Exception e) {
			// 捕获所有异常，避免单个蜜蜂数据异常导致渲染崩溃
			ProductiveBeesGenesis.LOGGER.warn("创建 PB 蜜蜂实体失败，回退到原版蜜蜂", e);
		}
		return createFallbackBee(level);
	}

	/**
	 * 配置 ConfigurableBee 的类型与属性
	 * <br/>
	 * 从 beeData 读取 "type" 字段（蜜蜂类型 ResourceLocation 字符串），
	 * 调用 setBeeType 设置类型后调用 setDefaultAttributes 应用对应属性。
	 * 若 type 字段缺失或为空，跳过配置（实体仍可渲染，但使用默认外观）。
	 *
	 * @param bee     ConfigurableBee 实例
	 * @param beeData 蜜蜂 NBT 数据
	 */
	private static void configureBeeType(ConfigurableBee bee, CompoundTag beeData) {
		if (beeData.contains("type")) {
			String beeType = beeData.getString("type");
			if (!beeType.isEmpty()) {
				bee.setBeeType(beeType);
				bee.setDefaultAttributes();
			}
		}
	}

	/**
	 * 创建回退用的原版蜜蜂实体
	 * <br/>
	 * 当 PB 蜜蜂创建失败时使用，保证渲染流程不中断。
	 *
	 * @param level 世界实例
	 * @return 原版 Bee 实体，level 为 null 时返回 null
	 */
	private static Entity createFallbackBee(Level level) {
		if (level == null) return null;
		return EntityType.BEE.create(level);
	}
}
