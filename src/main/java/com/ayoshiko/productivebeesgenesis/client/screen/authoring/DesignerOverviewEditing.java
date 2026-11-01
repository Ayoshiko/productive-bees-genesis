package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 总览复用同一份字段模型；机器身份与生产节点 ID 始终由原入口管理。 */
final class DesignerOverviewEditing {
    private DesignerOverviewEditing() {
    }

    static void recipe(DesignerFormScreen screen, JsonObject document, String id, JsonObject recipe, Runnable edit) {
        var host = BeeDesignerMachineRecipes.host(recipe);
        if (host == null) {
            if (recipe.get("type").getAsString().startsWith("minecraft:crafting_")) {
                DesignerRecipeSummary.add(screen, document, id, recipe, edit);
            }
            new DesignerRecipeFields(screen, recipe, id).add();
            return;
        }
        var mc = Minecraft.getInstance();
        screen.scope("recipes/" + id);
        screen.note("", DesignerRecipeSummary.machineFlow(recipe));
        screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
                DesignerFormScreen.tr("view_jei"), () -> viewJei(screen, id, recipe))));
        screen.number("machine_processing_time", recipe, host == BeeDesignerMachineRecipes.Host.MMR ? "time" : "duration", "20");
        if (host == BeeDesignerMachineRecipes.Host.MI) {
            screen.number("machine_eu", recipe, "eu", "1");
        }
        for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
            screen.rows.add(new DesignerFormScreen.Row(entry.input() ? "machine_inputs" : "machine_outputs", (x, y, w) -> {
                screen.addRenderableWidget(new DesignerMachineEntryButton(x, y, w, entry,
                        () -> mc.setScreen(new DesignerMachineMaterialScreen(screen, entry))));
                screen.ghost(x, y, w, value -> DesignerMachineRecipeScreen.accepts(entry.kind(), value), value -> {
                    BeeDesignerMachineRecipes.replaceMaterial(entry,
                            BeeDesignerMachineRecipes.material(DesignerFormScreen.selection(value), mc.level.registryAccess()));
                    screen.rebuild();
                });
            }));
            if (BeeDesignerMachineRecipes.supported(host, entry.kind())) {
                screen.number("machine_amount", entry.value(), entry.amountKey(), "1", DesignerNumberRange.forMachine(entry));
                screen.number("chance", entry.node(), entry.chanceKey(), "1");
                if (host == BeeDesignerMachineRecipes.Host.MBD2) {
                    screen.toggle("machine_per_tick", entry.node(), "perTick", false);
                }
            }
        }
        screen.scope("");
    }

    static void viewJei(DesignerFormScreen screen, String id, JsonObject recipe) {
        var mc = Minecraft.getInstance();
        for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
            if (!entry.input() && entry.kind().equals("item")) {
                var stack = BeeDesignerMachineRecipes.decodeItem(entry, mc.level.registryAccess());
                screen.status = BeeDesignerClient.viewRecipes(id, stack);
                return;
            }
        }
        screen.status = BeeDesignerClient.viewRecipes(id, net.minecraft.world.item.ItemStack.EMPTY);
    }

    static void productionTime(DesignerFormScreen screen, JsonObject document, String nodeId, JsonObject node) {
        var recipes = document.getAsJsonObject("recipes");
        var timed = new java.util.ArrayList<JsonObject>();
        for (var id : node.getAsJsonArray("recipe_ids")) {
            if (recipes.has(id.getAsString())) {
                var recipe = recipes.getAsJsonObject(id.getAsString());
                if (recipe.has("processingTime") || recipe.has("processing_time")) timed.add(recipe);
            }
        }
        if (timed.isEmpty()) return;
        JsonObject first = timed.get(0);
        String key = first.has("processingTime") ? "processingTime" : "processing_time";
        screen.scope("production_nodes/" + nodeId);
        screen.anchor("time");
        screen.number("processing_time", first, key, "200", DesignerNumberRange.forField("processing_time", key), value -> {
            for (var recipe : timed) {
                if (recipe != first) {
                    recipe.add(recipe.has("processingTime") ? "processingTime" : "processing_time", first.get(key).deepCopy());
                }
            }
        });
    }

    static void productionFields(DesignerFormScreen screen, String id, JsonObject recipe) {
        String type = BeeDesignerDocument.text(recipe, "type", "");
        boolean resource = type.startsWith("productivebeesgenesis:");
        String outputField = type.equals("productivebees:advanced_beehive") ? "results" : "outputs";
        screen.scope("recipes/" + id);
        if (recipe.has(outputField)) {
            var outputs = recipe.getAsJsonArray(outputField);
            for (int index = 0; index < outputs.size(); index++) {
                JsonObject output = outputs.get(index).getAsJsonObject();
                screen.scope("recipes/" + id + "/" + outputField + "/" + index);
                var stack = resource ? ItemStack.EMPTY : DesignerStackButton.decode(output.get("item"));
                var name = stack.isEmpty() ? DesignerFormScreen.option(BeeDesignerDocument.text(output, "kind", "output")) : stack.getHoverName();
                screen.outputHeading(Component.translatable("designer.output_numbered", index + 1, name), outputs, output);
                DesignerOutputScreen.addFields(screen, output, resource, outputs);
            }
        }
        if (recipe.has("fluid")) {
            screen.scope("recipes/" + id + "/fluid");
            screen.number("fluid_amount", recipe.getAsJsonObject("fluid"), "amount", "1000");
        }
        screen.scope("");
    }
}
