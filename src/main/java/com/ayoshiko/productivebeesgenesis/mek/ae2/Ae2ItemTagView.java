package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagCandidate;
import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagPattern;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Collection;

/**
 * 物品的标签视图 — 标签过滤的唯一「候选面」定义（SRP + DRY）。
 * <p>
 * <b>为什么要独立成类</b>：同一条规则有两个消费者 —— 服务端的
 * {@link Ae2TagFilterCache}（判定某个 AE2 键是否通过表达式）与客户端的标签选取器
 * （列出某个物品可用的标签）。若两处各写一份枚举逻辑，任何一侧漏掉方块标签
 * 都会表现为「界面里选得到、实际拉不到」这种极难排查的不一致。
 * <p>
 * <b>候选面构成</b>：物品 id + 物品标签 + （方块物品的）方块标签。并入方块标签的原因是
 * {@code c:storage_blocks/honeycombs} 这类 id 只挂在方块标签树上，物品标签里未必有同名项；
 * ExtendedAE 的标签总线同样对 {@code BlockItem} 并入方块标签。
 * <p>
 * 纯静态无状态，不引用 appeng 类，客户端与服务端均可安全调用。
 */
public final class Ae2ItemTagView {

	private Ae2ItemTagView() {
	}

	/**
	 * 构造表达式求值用的候选视图。
	 * <p>
	 * 返回的 lambda 每次调用都会重新读取标签快照，因此调用方必须自行缓存判定结果
	 * （{@link Ae2TagFilterCache} 承担这一职责），不可放进每 tick 热路径反复求值。
	 */
	public static TagCandidate candidateOf(Item item) {
		if (item == null) return pattern -> false;
		ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
		String itemIdText = itemId == null ? null : itemId.toString();
		// wrapAsHolder 而非已废弃的 builtInRegistryHolder()（废弃警告会让构建失败）
		Holder<Item> itemHolder = BuiltInRegistries.ITEM.wrapAsHolder(item);
		Holder<Block> blockHolder = item instanceof BlockItem blockItem
				? BuiltInRegistries.BLOCK.wrapAsHolder(blockItem.getBlock())
				: null;
		return pattern -> {
			if (pattern == null) return false;
			if (itemIdText != null && pattern.matches(itemIdText)) return true;
			if (matchesAnyTag(itemHolder, pattern)) return true;
			return blockHolder != null && matchesAnyTag(blockHolder, pattern);
		};
	}

	/**
	 * 收集该物品可用的全部标签 id（不含物品 id 自身）。
	 * <p>
	 * 供客户端「放物品→列标签」选取器使用；传入 {@link java.util.TreeSet} 即可同时获得去重与字典序。
	 * 标签读取异常按「无标签」处理，绝不向上抛出，避免界面渲染路径崩溃。
	 */
	public static void collectTagIds(Item item, Collection<String> out) {
		if (item == null || out == null) return;
		try {
			collectTagIds(BuiltInRegistries.ITEM.wrapAsHolder(item), out);
			if (item instanceof BlockItem blockItem) {
				collectTagIds(BuiltInRegistries.BLOCK.wrapAsHolder(blockItem.getBlock()), out);
			}
		} catch (LinkageError | RuntimeException ignored) {
			// 注册表 Holder 未绑定（资源重载窗口期）时视为无标签，界面下一帧自然恢复
		}
	}

	private static <T> void collectTagIds(Holder<T> holder, Collection<String> out) {
		holder.tags().forEach(tag -> {
			if (tag != null) out.add(tag.location().toString());
		});
	}

	private static <T> boolean matchesAnyTag(Holder<T> holder, TagPattern pattern) {
		return holder.tags().anyMatch(tag -> tag != null && pattern.matches(tag.location().toString()));
	}
}
