package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

import java.util.List;

/**
	 * 染料蜜蜂花→染料产物解析器
	 * <br/>
	 * 复刻 PB 原版 BeeHelper.getRecipeOutputFromInput 的花→染料转换语义，
	 * 并支持植物魔法（Botania）神秘花/神秘蘑菇 → 对应颜色神秘花瓣的特殊映射。
	 * <p>
	 * 设计原则：无状态纯静态工具类，仅在染料蜜蜂产出采样路径中低频调用。
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
	 *
	 * @param level  世界实例（配方管理器）
	 * @param flower 喂食槽中的花朵物品
	 * @return 合成配方输出的染料，无匹配配方时返回 ItemStack.EMPTY
	 */
	public static ItemStack resolveDyeFromFlower(Level level, ItemStack flower) {
		if (level == null || flower == null || flower.isEmpty()) return ItemStack.EMPTY;
		try {
			List<RecipeHolder<CraftingRecipe>> recipes =
					level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
			for (RecipeHolder<CraftingRecipe> recipe : recipes) {
				List<Ingredient> ingredients = recipe.value().getIngredients();
				if (ingredients.size() != 1) continue;
				ItemStack[] stacks = ingredients.get(0).getItems();
				if (stacks.length > 0 && stacks[0].getItem() == flower.getItem()) {
					ItemStack result = recipe.value().getResultItem(level.registryAccess());
					if (!result.isEmpty()) return result.copy();
				}
			}
		} catch (RuntimeException e) {
			// 配方管理器异常时回退空栈，由调用方走旧行为兜底
		}
		return ItemStack.EMPTY;
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
