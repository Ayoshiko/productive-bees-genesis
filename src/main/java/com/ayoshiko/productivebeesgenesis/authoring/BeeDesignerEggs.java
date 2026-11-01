package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.apiary.BeeSpawnEggHelper;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivebees.common.item.SpawnEgg;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import com.ayoshiko.productivebeesgenesis.port.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.ayoshiko.productivebeesgenesis.port.CustomData;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.TreeSet;

/** 配方身份来自刷怪蛋的真实绑定与组件，不能从物品名猜测可配置蜂种。 */
public final class BeeDesignerEggs {

	private BeeDesignerEggs() {
	}

	public static List<String> bees() {
		TreeSet<String> ids = new TreeSet<>();
		BeeReloadListener.INSTANCE.getData().keySet().forEach(id -> ids.add(id.toString()));
		ids.add("minecraft:bee");
		for (var holder : cy.jdkdigital.productivebees.init.ModItems.SPAWN_EGGS) {
			ResourceLocation id = bee(holder.get().getDefaultInstance());
			if (id != null) {
				ids.add(id.toString());
			}
		}
		return List.copyOf(ids);
	}

	public static ItemStack egg(String id) {
		ResourceLocation bee = new ResourceLocation(id);
		if (id.equals("minecraft:bee")) {
			return new ItemStack(Items.BEE_SPAWN_EGG);
		}
		for (var holder : cy.jdkdigital.productivebees.init.ModItems.SPAWN_EGGS) {
			ItemStack stack = holder.get().getDefaultInstance();
			if (bee.equals(bee(stack))) {
				return stack;
			}
		}
		ItemStack stack = cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_SPAWN_EGG.get().getDefaultInstance();
		CompoundTag entity = new CompoundTag();
		entity.putString("id", BeeSpawnEggHelper.CONFIGURABLE_ENTITY_ID);
		entity.putString("type", id);
		com.ayoshiko.productivebeesgenesis.port.ForgeComponents.set(stack, DataComponents.ENTITY_DATA, CustomData.of(entity));
		return stack;
	}

	public static JsonObject output(String id) {
		ItemStack stack = egg(id);
		JsonObject result = new JsonObject();
		result.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		CustomData entity = com.ayoshiko.productivebeesgenesis.port.ForgeComponents.get(stack, DataComponents.ENTITY_DATA);
		if (entity != null) {
			JsonObject components = new JsonObject();
			components.add("minecraft:entity_data", CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE, entity.copyTag()).getOrThrow(false, message -> {}));
			result.add("components", components);
		}
		return result;
	}

	public static JsonObject retarget(JsonObject original, String id) {
		JsonObject result = normalize(original);
		JsonObject identity = output(id);
		result.add("id", identity.get("id"));
		result.remove("item");
		JsonObject components = BeeDesignerDocument.object(result, "components");
		JsonObject entity = components.has("minecraft:entity_data") && components.get("minecraft:entity_data").isJsonObject()
				? components.getAsJsonObject("minecraft:entity_data") : new JsonObject();
		entity.remove("types");
		if (identity.has("components")) {
			identity.getAsJsonObject("components").getAsJsonObject("minecraft:entity_data").entrySet()
					.forEach(entry -> entity.add(entry.getKey(), entry.getValue().deepCopy()));
		} else {
			entity.addProperty("id", id);
			entity.remove("type");
		}
		components.add("minecraft:entity_data", entity);
		return result;
	}

	public static ItemStack validate(JsonObject source, HolderLookup.Provider registries) {
		JsonObject normalized = normalize(source);
		if (normalized.has("count")) {
			try {
				if (!normalized.get("count").isJsonPrimitive() || !normalized.getAsJsonPrimitive("count").isNumber()) {
					throw new ArithmeticException("Expected numeric count");
				}
				int count = normalized.get("count").getAsBigDecimal().intValueExact();
				if (count < 1 || count > 64) {
					throw new ArithmeticException("Count outside 1..64");
				}
			} catch (ArithmeticException failure) {
				throw new IllegalArgumentException("designer.error.egg_count", failure);
			}
		}
		ItemStack stack = com.ayoshiko.productivebeesgenesis.port.ForgeItemStackCodec.CODEC.parse(JsonOps.INSTANCE, normalized).getOrThrow(false, message -> {});
		if (bee(stack) == null || !bee(stack).equals(bee(normalized)) || stack.getCount() > stack.getMaxStackSize()) {
			throw new IllegalArgumentException("designer.error.invalid_bee_egg");
		}
		return stack;
	}

	@Nullable
	public static ResourceLocation bee(ItemStack stack) {
		if (stack.is(Items.BEE_SPAWN_EGG)) {
			CustomData entity = com.ayoshiko.productivebeesgenesis.port.ForgeComponents.get(stack, DataComponents.ENTITY_DATA);
			if (entity != null && entity.copyTag().contains("id") && !entity.copyTag().getString("id").equals("minecraft:bee")) {
				return null;
			}
			return new ResourceLocation("minecraft:bee");
		}
		CustomData data = com.ayoshiko.productivebeesgenesis.port.ForgeComponents.get(stack, DataComponents.ENTITY_DATA);
		if (data != null && data.copyTag().contains("types")) {
			return null;
		}
		return BeeSpawnEggHelper.getBeeType(stack);
	}

	/** 旧脚本导出的 SNBT 组件只转换结构；不执行任何 KubeJS 文本。 */
	public static JsonObject normalize(JsonObject source) {
		JsonObject result = source.deepCopy();
		JsonObject components = result.has("components") && result.get("components").isJsonObject() ? result.getAsJsonObject("components") : null;
		if (components != null && components.has("minecraft:entity_data") && components.get("minecraft:entity_data").isJsonPrimitive()) {
			try {
				CompoundTag entity = TagParser.parseTag(components.get("minecraft:entity_data").getAsString());
				components.add("minecraft:entity_data", CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE, entity).getOrThrow(false, message -> {}));
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
				throw new IllegalArgumentException("designer.error.invalid_bee_egg", failure);
			}
		}
		return result;
	}

	@Nullable
	public static ResourceLocation bee(JsonObject source) {
		JsonObject stack = normalize(source);
		ResourceLocation itemId = ResourceLocation.tryParse(BeeDesignerDocument.text(stack, "id", BeeDesignerDocument.text(stack, "item", "")));
		if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
			return null;
		}
		var item = BuiltInRegistries.ITEM.get(itemId);
		if (!(item instanceof SpawnEgg) && item != Items.BEE_SPAWN_EGG) {
			return null;
		}
		ResourceLocation expectedEntity = item == Items.BEE_SPAWN_EGG ? new ResourceLocation("minecraft:bee")
				: EntityType.getKey(((SpawnEgg) item).getType(null));
		JsonObject components = stack.has("components") && stack.get("components").isJsonObject() ? stack.getAsJsonObject("components") : null;
		JsonObject entity = components != null && components.has("minecraft:entity_data")
				&& components.get("minecraft:entity_data").isJsonObject() ? components.getAsJsonObject("minecraft:entity_data") : null;
		if (entity != null && (entity.has("types") || entity.has("id") && !expectedEntity.toString().equals(BeeDesignerDocument.text(entity, "id", "")))) {
			return null;
		}
		if (expectedEntity.toString().equals(BeeSpawnEggHelper.CONFIGURABLE_ENTITY_ID)) {
			return entity == null ? null : ResourceLocation.tryParse(BeeDesignerDocument.text(entity, "type", ""));
		}
		return expectedEntity;
	}
}
