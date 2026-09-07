package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper.FlowerPreference;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * blocks 类花朵匹配工具（纯静态，无状态）
 * <br/>
 * 从 {@link FeederSlotManager} 拆分而来，职责（SRP）：把「一个 ItemStack 是否满足某蜜蜂
 * 的 blocks 类花朵定义」这条纯规则独立出来，与 {@link AmberEntityFlowerHelper}
 * （entity_types 类花朵规则）并列；管理器只保留槽位状态、缓存与判定编排。
 */
final class BlockFlowerMatcher {

	private BlockFlowerMatcher() {
	}

	/**
	 * 检查物品栈是否匹配花朵偏好
	 * <br/>
	 * 参考 PB ConfigurableBee.isFlowerBlock / isFlowerItem 和 JDTE matchesConfiguredBlockFlower 的匹配逻辑：
	 * <ol>
	 *   <li>flowerTag：先检查物品标签（TagKey&lt;Item&gt;），若不匹配且物品为 BlockItem，再检查方块标签（TagKey&lt;Block&gt;）</li>
	 *   <li>flowerItem：ItemStack.is(Item)</li>
	 *   <li>flowerBlock：BlockItem 对应方块的注册表 ID 精确匹配</li>
	 *   <li>flowerFluid：BucketItem.content 匹配流体或流体标签</li>
	 * </ol>
	 * 任一匹配即返回 true。不检查 inverseFlower（与 PB isFlowerItem 行为一致）。
	 * <p>
	 * flowerTag 双重检查原理：PB 的 flowerTag 字段在 isFlowerBlock 中检查方块标签（TagKey&lt;Block&gt;），
	 * 在 isFlowerItem 中检查物品标签（TagKey&lt;Item&gt;）。部分模组（如 JDTE）仅创建方块标签而无对应物品标签，
	 * 例如 jdte:life_fluid_bee_flowers 仅包含 jdte:advanced_life_extractor 和 jdte:extended_life_extractor 两个方块。
	 * 当玩家将此类方块作为物品放入采蜜槽时，仅检查物品标签会漏匹配，必须同时检查方块标签。
	 *
	 * @param stack 待检查的物品栈
	 * @param pref  花朵偏好
	 * @return true 如果物品匹配任一花朵定义
	 */
	static boolean matches(ItemStack stack, FlowerPreference pref) {
		// flowerTag：先检查物品标签，再检查方块标签（BlockItem 场景）
		if (!pref.flowerTag().isEmpty()) {
			ResourceLocation tagId = ResourceLocation.parse(pref.flowerTag());
			// 1. 检查物品标签（与 PB isFlowerItem 一致）
			TagKey<Item> itemTag = TagKey.create(BuiltInRegistries.ITEM.key(), tagId);
			if (stack.is(itemTag)) return true;
			// 2. 当物品为 BlockItem 时，同时检查方块标签（与 PB isFlowerBlock / JDTE matchesConfiguredBlockFlower 一致）
			if (stack.getItem() instanceof BlockItem blockItem) {
				TagKey<Block> blockTag = TagKey.create(BuiltInRegistries.BLOCK.key(), tagId);
				if (blockItem.getBlock().defaultBlockState().is(blockTag)) return true;
			}
		}
		// flowerItem：检查具体物品
		if (!pref.flowerItem().isEmpty()) {
			Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(pref.flowerItem()));
			if (stack.is(item)) return true;
		}
		// flowerBlock：检查方块物品（如 sculk_bee 对应 minecraft:sculk_catalyst）
		// 仅当 stack 为 BlockItem 时通过方块注册表 ID 精确比对
		if (!pref.flowerBlock().isEmpty()) {
			if (stack.getItem() instanceof BlockItem blockItem) {
				ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
				if (blockId.toString().equals(pref.flowerBlock())) return true;
			}
		}
		// flowerFluid：检查流体桶
		if (!pref.flowerFluid().isEmpty() && stack.getItem() instanceof BucketItem bucket) {
			String fluidId = pref.flowerFluid();
			if (fluidId.startsWith("#")) {
				// 流体标签匹配 — 使用 Holder.is(TagKey) 替代 deprecated 的 Fluid.is(TagKey)
				TagKey<Fluid> fluidTag = TagKey.create(BuiltInRegistries.FLUID.key(),
						ResourceLocation.parse(fluidId.substring(1)));
				if (BuiltInRegistries.FLUID.wrapAsHolder(bucket.content).is(fluidTag)) return true;
			} else {
				// 具体流体匹配
				Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(fluidId));
				if (bucket.content.isSame(fluid)) return true;
			}
		}
		return false;
	}
}
