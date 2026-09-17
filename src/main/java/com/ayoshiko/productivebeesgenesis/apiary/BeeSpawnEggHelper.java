package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.common.item.SpawnEgg;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

/**
	 * Productive Bees 蜜蜂刷怪蛋的统一判定工具。
	 * <p>
	 * 数据驱动资源蜂复用 configurable_bee 刷怪蛋，具体类型存放在
	 * {@code minecraft:entity_data.type}；石料、木材等内建特殊蜂则使用各自实体类型的独立刷怪蛋。
 */
public final class BeeSpawnEggHelper {

	/** PB ConfigurableBee 的固定实体类型。 */
	public static final String CONFIGURABLE_ENTITY_ID = "productivebees:configurable_bee";

	private BeeSpawnEggHelper() {
	}

	/**
	 * 判断物品是否为可解析的 PB 蜜蜂刷怪蛋。
	 * <p>
	 * 这里仅做注册项与结构校验；服务端仍会创建临时实体并确认它确实是 ProductiveBee。
	 */
	public static boolean isResourceBeeSpawnEgg(ItemStack stack) {
		return resolve(stack) != null;
	}

	/**
	 * 读取刷怪蛋对应的产出/花源蜂种键。可配置蜂返回 ENTITY_DATA.type，
	 * 专用实体蜂返回实体类型注册名。
	 */
	@Nullable
	public static ResourceLocation getBeeType(ItemStack stack) {
		BeeSpawnEggData data = resolve(stack);
		return data == null ? null : data.beeType();
	}

	/** 解析 PB 蜜蜂蛋的实体类型与实际蜂种键。 */
	@Nullable
	public static BeeSpawnEggData resolve(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SpawnEgg spawnEgg)) {
			return null;
		}
		try {
			if (!isRegisteredPbSpawnEgg(stack.getItem())) return null;
			// 始终使用刷怪蛋的默认绑定，不允许 ENTITY_DATA.id 把专用蛋替换为其他实体。
			EntityType<?> entityType = spawnEgg.getType(ItemStack.EMPTY);
			ResourceLocation entityId = EntityType.getKey(entityType);
			if (!"productivebees".equals(entityId.getNamespace())) return null;
			CustomData entityData = stack.get(DataComponents.ENTITY_DATA);
			CompoundTag tag = entityData == null ? new CompoundTag() : entityData.copyTag();
			if (tag.contains("id") && !entityId.toString().equals(tag.getString("id"))) return null;

			if (!CONFIGURABLE_ENTITY_ID.equals(entityId.toString())) {
				return new BeeSpawnEggData(entityType, entityId, null);
			}

			if (entityData == null) return null;
			if (!tag.contains("type")) return null;

			ResourceLocation configurableType = ResourceLocation.tryParse(tag.getString("type"));
			return configurableType == null
					? null
					: new BeeSpawnEggData(entityType, configurableType, configurableType);
		} catch (RuntimeException ignored) {
			// 注册表尚未绑定或组件损坏时，客户端点击判定只能安全失败。
			return null;
		}
	}

	private static boolean isRegisteredPbSpawnEgg(Item item) {
		for (var holder : ModItems.SPAWN_EGGS) {
			if (holder.get() == item) return true;
		}
		return false;
	}

	/**
	 * @param entityType 实际要创建的 PB 蜜蜂实体类型
	 * @param beeType 机械蜂箱用于配方和花源判断的蜂种键
	 * @param configurableBeeType 可配置蜂的具体数据类型；专用实体蜂为 null
	 */
	public record BeeSpawnEggData(
			EntityType<?> entityType,
			ResourceLocation beeType,
			@Nullable ResourceLocation configurableBeeType) {
	}
}
