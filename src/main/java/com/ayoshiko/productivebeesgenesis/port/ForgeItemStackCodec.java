package com.ayoshiko.productivebeesgenesis.port;

import com.google.gson.*;
import com.mojang.serialization.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Designer documents use portable JSON; actual stacks use native 1.20 NBT. */
public final class ForgeItemStackCodec {
    public static final Codec<ItemStack> CODEC = Codec.PASSTHROUGH.flatXmap(value -> {
        try { return DataResult.success(read(value.convert(JsonOps.INSTANCE).getValue().getAsJsonObject())); }
        catch (RuntimeException e) { return DataResult.error(e::toString); }
    }, stack -> DataResult.success(new Dynamic<>(JsonOps.INSTANCE, write(stack))));

    public static ItemStack read(JsonObject json) {
        String id = json.has("id") ? json.get("id").getAsString() : json.get("item").getAsString();
        ResourceLocation key = new ResourceLocation(id);
        if (!BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("Unknown item: " + id);
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(key), json.has("count") ? json.get("count").getAsInt() : 1);
        CompoundTag nbt = new CompoundTag();
        if (json.has("tag")) nbt = tag(json.get("tag"));
        if (json.has("nbt")) nbt.merge(tag(json.get("nbt")));
        if (json.has("components")) nbt.merge(components(json.getAsJsonObject("components")));
        if (!nbt.isEmpty()) stack.setTag(nbt);
        return stack;
    }

    public static CompoundTag tag(JsonElement json) {
        try { return json.isJsonPrimitive() ? TagParser.parseTag(json.getAsString()) : CompoundTag.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(false, m -> {}); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new IllegalArgumentException(e); }
    }

    public static CompoundTag components(JsonObject components) {
        CompoundTag tag = new CompoundTag();
        for (var entry : components.entrySet()) {
            switch (entry.getKey()) {
                case "minecraft:custom_data" -> tag.merge(tag(entry.getValue()));
                case "minecraft:entity_data" -> tag.put("EntityTag", tag(entry.getValue()));
                case "minecraft:block_entity_data" -> tag.put("BlockEntityTag", tag(entry.getValue()));
                case "productivebees:bee_type" -> {
                    CompoundTag entity = tag.getCompound("EntityTag");
                    entity.putString("type", entry.getValue().getAsString()); tag.put("EntityTag", entity);
                }
                case "minecraft:custom_name" -> {
                    CompoundTag display = tag.getCompound("display"); display.putString("Name", entry.getValue().toString()); tag.put("display", display);
                }
                case "minecraft:damage" -> tag.putInt("Damage", entry.getValue().getAsInt());
                default -> throw new IllegalArgumentException("No 1.20.1 item data mapping: " + entry.getKey());
            }
        }
        return tag;
    }

    public static JsonObject write(ItemStack stack) {
        JsonObject json = new JsonObject(); json.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (stack.getCount() != 1) json.addProperty("count", stack.getCount());
        if (stack.hasTag()) {
            JsonObject components = new JsonObject();
            CompoundTag legacy=stack.getTag().copy();CompoundTag entity=legacy.getCompound("EntityTag");
            if(entity.contains("type",net.minecraft.nbt.Tag.TAG_STRING)){components.addProperty("productivebees:bee_type",entity.getString("type"));entity.remove("type");if(entity.isEmpty())legacy.remove("EntityTag");}
            if(!legacy.isEmpty())components.addProperty("minecraft:custom_data",legacy.toString());
            json.add("components", components);
        }
        return json;
    }
}
