package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerIdentity;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerLifecycle;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMyriad;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerNativeBees;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/** 未完成身份输入独立保管；有效输入只改变工程，世界迁移仍由发布事务执行。 */
final class DesignerIdentityField {
    private String input;
    private String problem = "";

    void clear() {
        input = null;
        problem = "";
    }

    String pending(JsonObject document) {
        return input == null || input.equals(document.get("id").getAsString()) ? "" : "id_input\n" + input;
    }

    void snapshot(DesignerDraftSnapshot snapshot, JsonObject document) {
        snapshot.completeRecovery("id-input");
        if (!pending(document).isEmpty()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", "id_input");
            entry.addProperty("text", input);
            snapshot.recover(entry, "id-input");
        }
    }

    void restore(String text) {
        input = text;
        problem = "designer.identity_input_pending";
    }

    void add(BeeDesignerScreen editor) {
        JsonObject document = editor.editingDocument();
        editor.scope("");
        editor.anchor("id");
        if (BeeDesignerNativeBees.recipesOnly(document) || BeeDesignerMyriad.recipesOnly(document)
                || BeeDesignerLifecycle.deleted(document)) {
            editor.note("id", Component.literal(document.get("id").getAsString()));
            return;
        }
        String current = document.get("id").getAsString();
        Set<String> reserved = reserved(document);
        String text = input == null ? current : input;
        String help = help(document);
        editor.rows.add(new DesignerFormScreen.Row("id", (x, y, w) -> {
            DesignerTheme.Input field = new DesignerTheme.Input(DesignerFonts.body(), x + 4, y + 5, w - 8, 20,
                    DesignerFormScreen.tr("id"));
            field.bind(document, "id");
            field.setMaxLength(256);
            field.setValue(text);
            field.setResponder(value -> {
                accept(editor, value, reserved);
                showProblem(field, help);
            });
            showProblem(field, help);
            editor.addRenderableWidget(field);
        }));
    }

    boolean key(BeeDesignerScreen editor, int key) {
        if ((key != GLFW.GLFW_KEY_ENTER && key != GLFW.GLFW_KEY_KP_ENTER)
                || !(editor.getFocused() instanceof DesignerTheme.Input field)
                || !field.getMessage().equals(DesignerFormScreen.tr("id"))) {
            return false;
        }
        JsonObject document = editor.editingDocument();
        accept(editor, field.getValue(), reserved(document));
        showProblem(field, help(document));
        return true;
    }

    private String help(JsonObject document) {
        return BeeDesignerDocument.text(document, "baseline", "new").equals("new")
                ? "identity_draft_help" : "identity_migrate_help";
    }

    private Set<String> reserved(JsonObject document) {
        Set<String> reserved = new HashSet<>();
        String current = document.get("id").getAsString();
        String original = document.has(BeeDesignerIdentity.MIGRATION)
                ? document.getAsJsonObject(BeeDesignerIdentity.MIGRATION).get("from").getAsString() : current;
        Set<String> ids = new HashSet<>(BeeDesignerEggs.bees());
        ids.addAll(DesignerDraftIndex.allIds());
        for (String id : ids) {
            if (!id.equals(current) && !id.equals(original)) {
                reserved.add(BeeDesignerDocument.translationKey(id));
            }
        }
        return reserved;
    }

    private void showProblem(DesignerTheme.Input field, String help) {
        field.invalid(!problem.isEmpty());
        field.setTooltip(Tooltip.create(problem.isEmpty() ? DesignerFormScreen.tr(help)
                : DesignerFormScreen.statusMessage(problem)));
    }

    private void accept(BeeDesignerScreen editor, String value, Set<String> reserved) {
        JsonObject document = editor.editingDocument();
        input = value;
        if (value.equals(document.get("id").getAsString())) {
            clear();
            editor.identityInputAccepted();
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || value.indexOf(':') <= 0 || !id.getPath().matches("[a-z0-9_.-]{1,96}")) {
            problem = "designer.error.id";
            editor.status = problem;
            return;
        }
        if (reserved.contains(BeeDesignerDocument.translationKey(value)) || BeeDesignerIdentity.isAlias(id)) {
            problem = "designer.error.name_collision";
            editor.status = problem;
            return;
        }
        editor.renameIdentityInline(value);
        clear();
        editor.identityInputAccepted();
    }
}
