package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 饲养板按标签随机抽样工具（纯静态，无状态）
 * <br/>
 * 从 {@link FeederSlotManager} 拆分而来，职责（SRP）：为 lumber_bee / quarry_bee / dye_bee 等
 * 多花蜜脾蜜蜂从饲养板推断产物时，按标签在生效格子中做等概率抽样（蓄水池抽样，单次遍历）。
 * <p>
 * 与花朵有效性判定分离：前者回答"蜜蜂能不能工作"，本类回答"这次产出选中哪一格的物品"。
 * 两者都只看生效格子（{@link FeederInventorySlot#isActive()}），禁用格既不供花也不供产物。
 */
final class FeederTagSampler {

	private FeederTagSampler() {
	}

	/**
	 * 随机取一个匹配方块标签的 BlockItem
	 * <br/>
	 * 复刻 PB 原版 FeederBlockEntity.getRandomBlockFromInventory 逻辑：
	 * 遍历生效格子，筛选 BlockItem 且对应方块在指定标签中的物品，等概率返回一个。
	 * <p>
	 * 性能：仅在 multi-flower 蜜蜂产出时调用（低频），使用 ThreadLocalRandom 避免竞争。
	 * 喂食槽数量固定（≤60），遍历 O(N) 开销可忽略。
	 *
	 * @param slots    喂食槽列表
	 * @param blockTag 方块标签（如 ModTags.LUMBER、ModTags.QUARRY）
	 * @return 匹配的 ItemStack；无匹配返回 {@link ItemStack#EMPTY}
	 */
	static ItemStack randomBlock(List<FeederInventorySlot> slots, TagKey<Block> blockTag) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		Block selected = null;
		int matches = 0;
		for (int i = 0; i < slots.size(); i++) {
			FeederInventorySlot slot = slots.get(i);
			if (!slot.isActive()) continue;
			ItemStack stack = slot.getStack();
			if (!(stack.getItem() instanceof BlockItem blockItem)) continue;
			Block block = blockItem.getBlock();
			// 用 BlockState.is 替代废弃的 Block.builtInRegistryHolder().is()
			if (!block.defaultBlockState().is(blockTag)) continue;
			if (random.nextInt(++matches) == 0) selected = block;
		}
		return selected == null ? ItemStack.EMPTY : new ItemStack(selected);
	}

	/**
	 * 随机取一个匹配物品标签的物品
	 * <br/>
	 * 用于 dye_bee 等蜜蜂从喂食槽推断产物，dye 不一定是 BlockItem（如玫瑰红染料）。
	 *
	 * @param slots   喂食槽列表
	 * @param itemTag 物品标签（如 ModTags.Common.DYES）
	 * @return 匹配 ItemStack 的副本；无匹配返回 {@link ItemStack#EMPTY}
	 */
	static ItemStack randomItem(List<FeederInventorySlot> slots, TagKey<Item> itemTag) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		ItemStack selected = ItemStack.EMPTY;
		int matches = 0;
		for (int i = 0; i < slots.size(); i++) {
			FeederInventorySlot slot = slots.get(i);
			if (!slot.isActive()) continue;
			ItemStack stack = slot.getStack();
			if (!stack.is(itemTag)) continue;
			if (random.nextInt(++matches) == 0) selected = stack;
		}
		return selected.isEmpty() ? ItemStack.EMPTY : selected.copy();
	}
}
