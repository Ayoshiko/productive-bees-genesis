package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

/**
 * ItemStack BLOCK_ENTITY_DATA 组件读取工具
 * <br/>
 * 提取自 {@link com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge} 和
 * {@link com.ayoshiko.productivebeesgenesis.apiary.ItemBlockMekApiary} 中重复的
 * readCustomBlockEntityData 方法，消除跨包代码重复。
 *
 * @since V17 代码审查修复
 */
public final class ItemStackBlockEntityDataHelper {

	private ItemStackBlockEntityDataHelper() {
	}

	/**
	 * 从 ItemStack 读取 BLOCK_ENTITY_DATA 组件的 NBT
	 * <br/>
	 * 原理：getDrops() 将自定义方块实体数据写入 DataComponents.BLOCK_ENTITY_DATA，
	 * 通过 CustomData.copyTag() 获取 CompoundTag。
	 * 扳手拆卸的物品才有此数据，新物品返回 null。
	 *
	 * @param stack 物品堆叠
	 * @return 自定义 NBT，若无则返回 null
	 */
	@Nullable
	public static CompoundTag readCustomBlockEntityData(@NotNull ItemStack stack) {
		CustomData blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
		if (blockEntityData == null) return null;
		try {
			return blockEntityData.copyTag();
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("读取 BLOCK_ENTITY_DATA 异常", e);
			return null;
		}
	}
}
