package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
	 * 染料蜜蜂花→染料产物解析器
	 * <br/>
	 * 复刻 PB 原版 BeeHelper.getRecipeOutputFromInput 的花→染料转换语义，
	 * 并支持植物魔法（Botania）神秘花/神秘蘑菇 → 对应颜色神秘花瓣的特殊映射。
	 * <p>
	 * 设计原则：无状态纯静态工具类；配方查找委托 {@link SingleIngredientCraftingIndex}，
	 * 避免在蜜蜂产出路径上重复全量遍历合成配方表。
	 */
public final class DyeProduceResolver {

	/** Botania 16 种基础颜色 */
	private static final String[] BOTANIA_COLORS = {
			"white", "orange", "magenta", "light_blue", "yellow",
			"lime", "pink", "gray", "light_gray", "cyan",
			"purple", "blue", "brown", "green", "red", "black"
	};

	private DyeProduceResolver() {
		// 工具类禁止实例化
	}

	/**
	 * 将 Botania 神秘花/神秘蘑菇映射为对应颜色的神秘花瓣。
	 * <br/>
	 * 支持 {@code botania:<color>_mystical_flower} / {@code botania:<color>_mystical_mushroom}
	 * 以及任何 {@code botania:<color>_mystical_*} 形态（含高神秘花等变体）。
	 * 通过注册表存在性守卫：Botania 未安装或对应花瓣不存在时返回空栈。
	 *
	 * @param flower 喂食槽中的花朵物品
	 * @return 对应颜色的 Botania 花瓣，无法映射时返回 ItemStack.EMPTY
	 */
	public static ItemStack resolveBotaniaPetal(ItemStack flower) {
		if (flower == null || flower.isEmpty()) return ItemStack.EMPTY;
		ResourceLocation id = BuiltInRegistries.ITEM.getKey(flower.getItem());
		if (!"botania".equals(id.getNamespace())) return ItemStack.EMPTY;
		String color = matchMysticalColor(id.getPath());
		if (color == null) return ItemStack.EMPTY;
		Item petal = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("botania", color + "_petal"));
		if (petal == Items.AIR) return ItemStack.EMPTY;
		return new ItemStack(petal);
	}

	/**
	 * 将花转换为染料 — 复刻 PB 原版 {@code BeeHelper.getRecipeOutputFromInput}。
	 * <br/>
	 * 在全部合成配方中查找"恰好一个原料且原料的第一个候选物品等于该花"的配方，
	 * 返回配方结果（如蒲公英 → 黄色染料）。找不到匹配配方时返回空栈。
	 * <p>
	 * 查找由 {@link SingleIngredientCraftingIndex} 提供 O(1) 索引，语义与原全量遍历一致
	 * （同样是首个匹配配方胜出、空产出配方跳过）。
	 *
	 * @param level  世界实例（配方管理器）
	 * @param flower 喂食槽中的花朵物品
	 * @return 合成配方输出的染料，无匹配配方时返回 ItemStack.EMPTY
	 */
	public static ItemStack resolveDyeFromFlower(Level level, ItemStack flower) {
		// 查找委托给索引：原实现每次调用都全量遍历合成配方表，染料蜜蜂每个产出周期都会触发，
		// 属于 tick 路径上的 O(全部合成配方) 开销；索引把成本前移到重载后首次查询，之后 O(1)。
		return SingleIngredientCraftingIndex.resolve(level, flower);
	}

	/**
	 * 匹配 Botania 神秘植物路径，返回颜色名。
	 * <br/>
	 * 仅匹配 {@code <color>_mystical_*} 前缀（神秘花/神秘蘑菇/高神秘花等变体），
	 * 避免误匹配 {@code mystical_flower} 本身或非 Botania 物品。
	 */
	@Nullable
	private static String matchMysticalColor(String path) {
		for (String color : BOTANIA_COLORS) {
			if (path.startsWith(color + "_mystical_")) {
				return color;
			}
		}
		return null;
	}
}
