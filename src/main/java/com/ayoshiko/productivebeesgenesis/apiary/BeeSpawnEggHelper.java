package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

/**
 * Productive Bees 可配置资源蜜蜂刷怪蛋的统一判定工具。
 * <p>
 * PB 为所有数据驱动资源蜜蜂复用同一个刷怪蛋物品，具体蜜蜂类型存放在
 * {@code minecraft:entity_data.type}，而不是刷怪蛋物品本身的注册名。
 */
public final class BeeSpawnEggHelper {

	/** PB ConfigurableBee 的固定实体类型。 */
	public static final String CONFIGURABLE_ENTITY_ID = "productivebees:configurable_bee";

	private BeeSpawnEggHelper() {
	}

	/**
	 * 判断物品是否为带有合法具体类型的 PB 资源蜜蜂刷怪蛋。
	 * <p>
	 * 这里仅做物品组件级校验；具体类型是否存在于当前服务器资源包由服务端再次确认。
	 */
	public static boolean isResourceBeeSpawnEgg(ItemStack stack) {
		return getBeeType(stack) != null;
	}

	/**
	 * 读取刷怪蛋中的具体资源蜜蜂类型。
	 * <p>
	 * 严格限制为 PB 的可配置刷怪蛋，并拒绝被伪造为其他实体类型的
	 * {@code ENTITY_DATA}。返回值使用规范化后的 {@link ResourceLocation}。
	 */
	@Nullable
	public static ResourceLocation getBeeType(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SpawnEggItem)
				|| ModItems.CONFIGURABLE_SPAWN_EGG == null) {
			return null;
		}
		final Item configurableEgg;
		try {
			configurableEgg = ModItems.CONFIGURABLE_SPAWN_EGG.get();
		} catch (RuntimeException ignored) {
			// 注册表尚未绑定时，客户端点击判定只能安全失败。
			return null;
		}
		if (stack.getItem() != configurableEgg) return null;

		try {
			CustomData entityData = stack.get(DataComponents.ENTITY_DATA);
			if (entityData == null) return null;
			CompoundTag tag = entityData.copyTag();
			if (!tag.contains("type")) return null;

			// PB 正常生成的刷怪蛋会写入 id；缺失时允许 SpawnEggItem 使用其默认实体类型。
			if (tag.contains("id") && !CONFIGURABLE_ENTITY_ID.equals(tag.getString("id"))) {
				return null;
			}
			String type = tag.getString("type");
			return type.isEmpty() ? null : ResourceLocation.tryParse(type);
		} catch (RuntimeException ignored) {
			// 损坏的组件不得中断 GUI 或网络处理。
			return null;
		}
	}
}
