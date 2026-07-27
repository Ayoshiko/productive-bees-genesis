package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.List;

import cy.jdkdigital.productivebees.util.BeeHelper;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;

/**
 * 蜜脾→蜜脾块转换器
 * <br/>
 * 从 {@link BeeProduceProcessor} 抽取的蜜脾块升级转换逻辑，负责将 {@link HoneycombItem}
 * 类型产出替换为对应的蜜脾块。
 * <p>
 * 复刻 PB 原版 {@code BeeHelper.getCombBlockFromHoneyComb} 的转换语义：
 * 将所有 {@link HoneycombItem} 类型的产出替换为对应的蜜脾块（1:1 替换，保持数量）。
 * <p>
 * 转换结果不写入静态缓存（不同蜂箱升级状态不同），每次调用动态转换。
 * 转换失败（PB 内部异常）时保留原始物品，避免产物丢失。
 */
public class CombBlockConverter {

	/**
	 * 将蜜脾转换为蜜脾块（Bug 5）
	 * <br/>
	 * 安装 Omega/Block 升级后，复刻 PB 原版 {@code BeeHelper.getCombBlockFromHoneyComb} 的转换逻辑：
	 * 将所有 {@link HoneycombItem} 类型的产出替换为对应的蜜脾块（1:1 替换，保持数量）。
	 * <p>
	 * PB 原版在 {@code BeeHelper.getBeeProduce} 中对每个产出物品判断
	 * {@code itemStack.getItem() instanceof HoneycombItem}，命中则调用
	 * {@code getCombBlockFromHoneyComb} 替换，随后 {@code setCount(count)} 保持数量。
	 * 此处采用相同语义：1:1 替换不改变数量。
	 * <p>
	 * 转换结果不写入静态缓存 {@code produceCache}（不同蜂箱升级状态不同），每次调用动态转换。
	 * 转换失败（PB 内部异常）时保留原始物品，避免产物丢失。
	 *
	 * @param items 原始产出物品列表
	 * @return 转换后的物品列表（蜜脾已替换为蜜脾块）
	 */
	public List<ItemStack> convertCombsToBlocks(List<ItemStack> items) {
		List<ItemStack> result = new ArrayList<>(items.size());
		for (ItemStack stack : items) {
			if (stack.isEmpty()) continue;
			if (stack.getItem() instanceof HoneycombItem) {
				try {
					ItemStack block = BeeHelper.getCombBlockFromHoneyComb(stack);
					if (!block.isEmpty()) {
						// 1:1 替换，保持原始数量
						block.setCount(stack.getCount());
						result.add(block);
						continue;
					}
				} catch (Exception e) {
					// 转换失败保留原始物品，避免产物丢失
					ProductiveBeesGenesis.LOGGER.warn("蜜脾→蜜脾块转换失败，保留原始物品", e);
				}
			}
			result.add(stack);
		}
		return result;
	}
}
