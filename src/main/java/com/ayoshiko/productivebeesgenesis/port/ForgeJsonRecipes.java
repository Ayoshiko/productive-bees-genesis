package com.ayoshiko.productivebeesgenesis.port;

import com.google.gson.*;

/** Converts portable editor JSON at the actual Forge recipe boundary. */
public final class ForgeJsonRecipes {
    public static JsonObject toPortable(JsonObject nativeRecipe) {
        JsonObject result = portable(nativeRecipe, "").getAsJsonObject();
        String type = result.has("type") ? result.get("type").getAsString() : "";
        if (type.equals("productivebees:advanced_beehive") || type.equals("productivebees:centrifuge")) {
            String key = type.endsWith("advanced_beehive") ? "results" : "outputs";
            if (result.has(key)) {
                JsonArray items=new JsonArray();
                for(var entry:result.getAsJsonArray(key)) {
                    JsonObject output=entry.getAsJsonObject();
                    if(output.has("fluid")) {
                        JsonObject fluid=output.get("fluid").isJsonObject()?output.getAsJsonObject("fluid").deepCopy():new JsonObject();
                        if(!output.get("fluid").isJsonObject())fluid.add("fluid",output.get("fluid").deepCopy());
                        fluid.addProperty("amount",output.has("amount")?output.get("amount").getAsInt():1);result.add("fluid",fluid);
                    } else items.add(output);
                }
                result.add(key,items);
            }
            if (result.has(key)) for (var entry : result.getAsJsonArray(key)) {
                var output = entry.getAsJsonObject();
                if (output.has("chance")) output.addProperty("chance", Math.max(0, Math.min(1, (output.get("chance").getAsDouble() + 1) / 100)));
            }
        }
        return result;
    }

    public static JsonElement portableIngredient(JsonElement ingredient){return portable(ingredient,"ingredient");}
    private static JsonElement portable(JsonElement value, String role) {
        if (value.isJsonArray()) { JsonArray result=new JsonArray();for(var entry:value.getAsJsonArray())result.add(portable(entry,role));return result; }
        if (!value.isJsonObject()) return value.deepCopy();
        JsonObject source=value.getAsJsonObject();
        if ((role.equals("result") || role.equals("stack")) && source.has("item") && source.get("item").isJsonPrimitive()) return ForgeItemStackCodec.write(ForgeItemStackCodec.read(source));
        if(source.has("item") && source.get("item").isJsonPrimitive() && source.has("nbt") && source.has("type") && (source.get("type").getAsString().equals("forge:nbt")||source.get("type").getAsString().equals("forge:partial_nbt"))){
            JsonObject converted=source.deepCopy();var nbt=ForgeItemStackCodec.tag(converted.remove("nbt"));JsonObject components=new JsonObject();var entity=nbt.getCompound("EntityTag");
            if(entity.contains("type",net.minecraft.nbt.Tag.TAG_STRING)){components.addProperty("productivebees:bee_type",entity.getString("type"));entity.remove("type");if(entity.isEmpty())nbt.remove("EntityTag");}
            if(!nbt.isEmpty())components.addProperty("minecraft:custom_data",nbt.toString());converted.add("components",components);converted.addProperty("strict",source.get("type").getAsString().equals("forge:nbt"));converted.addProperty("type","neoforge:components");converted.add("items",converted.remove("item"));return converted;
        }
        JsonObject result=new JsonObject();
        source.entrySet().forEach(entry->result.add(entry.getKey().equals("conditions")?"neoforge:conditions":entry.getKey(),portable(entry.getValue(),entry.getKey())));
        return result;
    }

    public static JsonObject toForge(JsonObject portable) {
        JsonObject result = transform(portable, "").getAsJsonObject();
        String type = result.has("type") ? result.get("type").getAsString() : "";
        if (type.equals("productivebees:advanced_beehive") || type.equals("productivebees:centrifuge")) {
            String key = type.endsWith("advanced_beehive") ? "results" : "outputs";
            if(result.has("fluid")) {
                JsonObject fluid=result.remove("fluid").getAsJsonObject();JsonObject output=new JsonObject();
                output.add("amount",fluid.remove("amount"));
                output.add("fluid",fluid.has("ingredient")?fluid.get("ingredient"):fluid);
                if(!result.has(key))result.add(key,new JsonArray());result.getAsJsonArray(key).add(output);
            }
            if (result.has(key)) for (var entry : result.getAsJsonArray(key)) {
                var output = entry.getAsJsonObject();
                if (output.has("chance")) {
                    double chance = output.get("chance").getAsDouble();
                    if (!Double.isFinite(chance) || chance<0 || chance>1) throw new IllegalArgumentException("Portable PB chance must be between 0 and 1");
                    output.addProperty("chance", (int)Math.round(chance * 100) - 1);
                }
            }
        }
        return result;
    }

    private static JsonElement transform(JsonElement value, String role) {
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray(); for (var entry : value.getAsJsonArray()) array.add(transform(entry, role)); return array;
        }
        if (!value.isJsonObject()) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (text.startsWith("neoforge:")) return new JsonPrimitive("forge:" + text.substring(9));
                if (text.startsWith("c:")) return new JsonPrimitive("forge:" + text.substring(2));
            }
            return value.deepCopy();
        }
        JsonObject source = value.getAsJsonObject(), result = new JsonObject();
        source.entrySet().forEach(entry -> {
            if (!entry.getKey().equals("components")) result.add(entry.getKey().equals("neoforge:conditions") ? "conditions" : entry.getKey(), transform(entry.getValue(), entry.getKey()));
        });
        if (source.has("components")) {
            var nbt = ForgeItemStackCodec.components(source.getAsJsonObject("components"));
            if (result.has("nbt")) nbt.merge(ForgeItemStackCodec.tag(result.get("nbt")));
            result.addProperty("nbt", nbt.toString());
            if (!role.equals("result") && !role.equals("stack")) {
                result.addProperty("type", source.has("strict") && !source.get("strict").getAsBoolean() ? "forge:partial_nbt" : "forge:nbt");
                result.remove("strict");
                if (result.has("items")) {
                    var items = result.remove("items");
                    result.add("item", items.isJsonArray() ? items.getAsJsonArray().get(0) : items);
                }
            }
        }
        if ((role.equals("result") || role.equals("stack")) && result.has("id")) result.add("item", result.remove("id"));
        return result;
    }
}
