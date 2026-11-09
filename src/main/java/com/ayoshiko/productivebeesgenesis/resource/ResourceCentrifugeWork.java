package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/** 一段已付费离心进度的封闭快照；输入、配方和种子不随重载重新解释。 */
final class ResourceCentrifugeWork {
	final ResourceLocation recipeId;
	final ResourceCentrifugeRecipe recipe;
	final ItemStack input;
	final int paidTicks;
	final long seed;
	final long revision;
	final CompoundTag ordinaryRecipe;
	private CompoundTag encodedTemplate;

	ResourceCentrifugeWork(ResourceLocation id, ResourceCentrifugeRecipe recipe, ItemStack input,
			int paidTicks, long seed, long revision) {
		this(id, recipe, input, paidTicks, seed, revision, null);
	}

	ResourceCentrifugeWork(ResourceLocation id, ResourceCentrifugeRecipe recipe, ItemStack input,
			int paidTicks, long seed, long revision, CompoundTag ordinaryRecipe) {
		this.recipeId = id;
		this.recipe = recipe;
		this.input = input.copyWithCount(1);
		this.paidTicks = paidTicks;
		this.seed = seed;
		this.revision = revision;
		this.ordinaryRecipe = ordinaryRecipe == null ? null : ordinaryRecipe.copy();
		if (paidTicks < 0 || revision < 0 || recipe.units(this.input) == 0
				|| recipe.includePbOutputs() != (ordinaryRecipe != null)) {
			throw new IllegalArgumentException("Invalid resource centrifuge work");
		}
	}

	CompoundTag save(HolderLookup.Provider provider) {
		var tag = saveBase(provider);
		if (ordinaryRecipe != null) tag.put("ordinary_recipe", ordinaryRecipe.copy());
		return tag;
	}

	/** 请求证据只需普通配方 ID；完整产物表仍由已付工作和实际本地结果保管。 */
	CompoundTag evidence(HolderLookup.Provider provider) {
		var tag = saveBase(provider);
		if (ordinaryRecipe != null) tag.putString("ordinary_recipe_id", ordinaryRecipe.getString("id"));
		return tag;
	}

	private CompoundTag saveBase(HolderLookup.Provider provider) {
		if (encodedTemplate == null) {
			var template = new CompoundTag();
			template.putInt("version", 2);
			template.putString("recipe_id", recipeId.toString());
			template.put("recipe", ResourceCentrifugeRecipe.CODEC.codec().encodeStart(NbtOps.INSTANCE, recipe).getOrThrow());
			template.put("input", input.save(provider));
			template.putLong("revision", revision);
			encodedTemplate = template;
		}
		var tag = encodedTemplate.copy();
		tag.putInt("paid_ticks", paidTicks);
		tag.putLong("seed", seed);
		return tag;
	}

	ResourceCentrifugeWork advance(int ticks, long nextSeed) {
		return new ResourceCentrifugeWork(this, ticks, nextSeed);
	}

	private ResourceCentrifugeWork(ResourceCentrifugeWork previous, int ticks, long nextSeed) {
		if (ticks < 0) throw new IllegalArgumentException("Negative paid progress");
		recipeId = previous.recipeId;
		recipe = previous.recipe;
		input = previous.input;
		paidTicks = ticks;
		seed = nextSeed;
		revision = previous.revision;
		ordinaryRecipe = previous.ordinaryRecipe;
		encodedTemplate = previous.encodedTemplate;
	}

	static ResourceCentrifugeWork load(CompoundTag tag, HolderLookup.Provider provider) {
		ResourceAccountData.keys(tag, Set.of("version", "recipe_id", "recipe", "input", "paid_ticks", "seed", "revision", "ordinary_recipe"));
		if (!tag.contains("version", Tag.TAG_INT) || (tag.getInt("version") != 1 && tag.getInt("version") != 2)
				|| tag.contains("ordinary_recipe") && !tag.contains("ordinary_recipe", Tag.TAG_COMPOUND)
				|| !tag.contains("recipe_id", Tag.TAG_STRING) || !tag.contains("recipe", Tag.TAG_COMPOUND)
				|| !tag.contains("input", Tag.TAG_COMPOUND) || !tag.contains("paid_ticks", Tag.TAG_INT)
				|| !tag.contains("seed", Tag.TAG_LONG) || !tag.contains("revision", Tag.TAG_LONG)) {
			throw new IllegalArgumentException("Invalid resource centrifuge work data");
		}
		var input = ItemStack.parse(provider, tag.getCompound("input")).orElseThrow();
		if (input.getCount() != 1) throw new IllegalArgumentException("Invalid input identity count");
		return new ResourceCentrifugeWork(ResourceLocation.parse(tag.getString("recipe_id")),
				ResourceCentrifugeRecipe.CODEC.codec().parse(NbtOps.INSTANCE, tag.get("recipe")).getOrThrow(),
				input, tag.getInt("paid_ticks"), tag.getLong("seed"), tag.getLong("revision"),
				tag.contains("ordinary_recipe") ? tag.getCompound("ordinary_recipe") : null);
	}
}
