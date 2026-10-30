package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

/** 编辑器本地偏好与作者安装说明；不修改服务器配置或玩家 GUI 比例。 */
final class DesignerSettingsScreen extends DesignerFormScreen {
    private final Screen parent;
    private String category = "appearance";
    private JsonObject custom = DesignerPreferences.palette().json();
    private String importPath = "";
    private boolean saving;
    private boolean layoutChanged;
	private boolean customExpanded = DesignerPreferences.text("theme").startsWith("custom_");

    DesignerSettingsScreen(Screen parent) {
        super(tr("settings"));
        this.parent = parent;
        editorParent = parent;
    }

    @Override
    protected void init() {
        begin();
        if (status.isBlank() && !DesignerPreferences.problem().isBlank()) {
            status = DesignerPreferences.problem();
        }
        tabs(left, contentTop, contentWidth, category, new String[]{"appearance", "interaction", "shortcuts", "installation", "files"},
                value -> true, value -> {
                    category = value;
                    page = 0;
                    rebuild();
                    animatePage();
                });
        contentTop += 28;
        switch (category) {
            case "appearance" -> appearance();
            case "interaction" -> interaction();
            case "shortcuts" -> shortcuts();
            case "installation" -> installation();
            case "files" -> DesignerFileLocationsScreen.addLocations(this);
            default -> throw new IllegalStateException("Unknown settings category");
        }
        finishRows();
        int cell = (contentWidth - 12) / 3;
        primaryButton(left, footerY(), cell, tr("settings_save"), () -> DesignerPreferences.save(message -> status = message));
        button(left + cell + 6, footerY(), cell, tr("settings_reset"), () -> {
            DesignerPreferences.apply(DesignerPreferences.defaults());
            custom = DesignerPreferences.palette().json();
            status = "designer.status.preferences_reset";
            rebuild();
        });
        button(left + (cell + 6) * 2, footerY(), cell, Component.translatable("gui.done"), this::onClose);
    }

    private void appearance() {
        section("settings_appearance");
        preferenceChoice("settings_theme", "theme", "dark", "light", "ae_light", "ae_dark", "custom_dark", "custom_light");
		preferenceChoice("settings_skin", "skin", "smooth", "ae");
		preferenceChoice("settings_surface_material", "surface_material", "solid", "glass");
		preferenceChoice("settings_font_mode", "font_mode", "auto", "sdf", "vanilla");
		note("", tr("settings_font_help"));
        preferenceChoice("settings_density", "density", "compact", "comfortable", "spacious");
		preferenceChoice("settings_navigation_layout", "navigation_layout", "adaptive", "menus", "expanded");
		preferenceChoice("settings_field_layout", "field_layout", "adaptive", "single", "columns");
		preferenceToggle("settings_collapse_sections", "collapse_sections");
		preferenceToggle("settings_catalog_help", "catalog_help");
		note("", tr("settings_responsive_help"));
        settingSlider("ui_scale", 0.85, 1.15);
        settingSlider("backdrop", 0.1, 0.9);
		rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerTheme.GroupHeader(x, y, w,
				Component.literal(customExpanded ? "⌄  " : "›  ").append(tr("settings_custom_theme")), () -> {
					customExpanded = !customExpanded;
					rebuild();
				}))));
		if (customExpanded || !DesignerPreferences.enabled("collapse_sections")) {
			customTheme();
		}
	}

	private void customTheme() {
        note("", tr("settings_theme_help"));
        rows.add(new Row("", (x, y, w) -> {
            int cell = (w - 6) / 2;
            button(x, y, cell, tr("settings_custom_dark"), () -> editTheme("custom_dark"));
            button(x + cell + 6, y, cell, tr("settings_custom_light"), () -> editTheme("custom_light"));
        }, 20, true));
        JsonObject colors = custom.getAsJsonObject("colors");
		try {
			DesignerPalette preview = DesignerPalette.parse(custom);
			double ratio = Math.min(DesignerPalette.contrastRatio(preview.text(), preview.panel()),
					DesignerPalette.contrastRatio(preview.text(), preview.button()));
			note(ratio < 4.5 ? "error" : "", Component.translatable("designer.settings_contrast",
					String.format(java.util.Locale.ROOT, "%.1f", ratio)));
		} catch (IllegalArgumentException invalid) {
			note("error", statusMessage(invalid.getMessage()));
		}
        for (String key : List.of("panel", "card", "input", "button", "hover", "text", "muted", "accent", "border", "error", "success")) {
			rows.add(new Row("theme.color." + key, (x, y, w) -> {
				addRenderableWidget(new DesignerTheme.Swatch(x, y, 26, () -> colors.get(key).getAsString(), () ->
						minecraft.setScreen(new DesignerColorScreen(this, colors, key))));
				var input = new DesignerTheme.Input(font, x + 38, y + 5, Math.max(20, w - 42), 20, tr("theme.color." + key));
				input.setMaxLength(7);
				input.setValue(colors.get(key).getAsString());
				input.setResponder(value -> {
					colors.addProperty(key, value);
					input.invalid(!value.matches("#[0-9a-fA-F]{6}"));
				});
				addRenderableWidget(input);
			}));
        }
        rows.add(new Row("", (x, y, w) -> primaryButton(x, y, w, tr("settings_apply_theme"), () -> {
            try {
                DesignerPreferences.theme(custom);
                status = "designer.status.theme_applied";
                rebuild();
            } catch (RuntimeException exception) {
                status = exception.getMessage();
            }
        }), 20, true));
        section("settings_theme_files");
        text("settings_theme_path", importPath, value -> importPath = value);
        rows.add(new Row("", (x, y, w) -> {
            int cell = (w - 12) / 3;
            button(x, y, cell, tr("settings_import_theme"), () -> importTheme(importPath));
            button(x + cell + 6, y, cell, tr("settings_export_theme"), () -> DesignerProjectFiles.exportTheme(DesignerPreferences.palette().json(),
                    path -> status = Component.translatable("designer.status.exported", path.toString()).getString(), message -> status = message));
            button(x + (cell + 6) * 2, y, cell, tr("settings_theme_json"), () ->
                    minecraft.setScreen(new DesignerJsonScreen(this, custom, tr("settings_theme_json").getString())));
        }, 20, true));
    }

    private void editTheme(String key) {
        custom = DesignerPreferences.snapshot().getAsJsonObject(key);
        status = "";
        rebuild();
    }

    private void interaction() {
        section("settings_animation");
		rows.add(new Row("motion_preset", (x, y, w) -> {
			int cell = (w - 8) / 3;
			String[] presets = {"gentle", "snappy", "still"};
			for (int index = 0; index < presets.length; index++) {
				String preset = presets[index];
				button(x + index * (cell + 4), y, cell, tr("motion_preset." + preset), () -> motionPreset(preset));
			}
		}, 24, true));
        preferenceToggle("settings_animations", "animations");
        preferenceToggle("settings_reduce_motion", "reduce_motion");
        settingSlider("animation_ms", 80, 400);
		settingSlider("motion_amount", 0, 24);
		preferenceChoice("settings_motion_style", "motion_style", "responsive", "smooth");
		preferenceChoice("settings_motion_feedback", "motion_feedback", "subtle", "ripple", "none");
		preferenceChoice("settings_motion_transition", "motion_transition", "slide", "reveal", "none");
		preferenceToggle("settings_motion_menus", "motion_menus");
		preferenceToggle("settings_motion_selection", "motion_selection");
        section("settings_behavior");
        settingSlider("preview_sensitivity", 0.15, 1.5);
        settingSlider("tooltip_ms", 0, 1500);
        preferenceToggle("settings_context_hints", "context_hints");
        preferenceToggle("settings_alternating_rows", "alternating_rows");
        preferenceToggle("settings_auto_save", "auto_save");
        settingSlider("auto_save_ms", 15000, 600000);
        note("", tr("settings_interaction_help"));
    }

	private void motionPreset(String preset) {
		JsonObject values = DesignerPreferences.snapshot();
		values.addProperty("reduce_motion", preset.equals("still"));
		values.addProperty("animations", !preset.equals("still"));
		values.addProperty("animation_ms", preset.equals("snappy") ? 130 : 220);
		values.addProperty("motion_amount", preset.equals("snappy") ? 6 : 3);
		values.addProperty("motion_style", preset.equals("snappy") ? "responsive" : "smooth");
		values.addProperty("motion_feedback", preset.equals("still") ? "none" : "subtle");
		values.addProperty("motion_transition", preset.equals("still") ? "none" : "slide");
		DesignerPreferences.apply(values);
		status = "designer.motion_preset_applied";
		rebuild();
	}

    private void shortcuts() {
        section("settings_shortcuts");
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("tutorial_reopen"), () ->
				DesignerTutorialScreen.start(this))));
        for (String key : List.of("search", "save", "undo", "redo", "theme", "layout", "settings", "pages", "selection", "drag", "number")) {
            note("", tr("shortcut." + key));
        }
    }

    private void installation() {
        section("settings_server");
        for (String key : List.of("creative", "permission", "apply", "datapack", "names", "kubejs", "restart")) {
            note("", tr("installation." + key));
        }
		note("", tr("editor_texture_credit"));
    }

    private void preferenceChoice(String label, String key, String... choices) {
        rows.add(new Row(label, (x, y, w) -> selection(x, y, w, DesignerPreferences.text(key), choices, value -> {
            DesignerPreferences.set(key, value);
            if (key.equals("theme")) {
                custom = DesignerPreferences.palette().json();
            }
            rebuild();
        })));
    }

    private void preferenceToggle(String label, String key) {
        rows.add(new Row(label, (x, y, w) -> addRenderableWidget(new DesignerTheme.Switch(x, y, Math.min(w, 90),
                DesignerPreferences.enabled(key), () -> {
                    DesignerPreferences.set(key, !DesignerPreferences.enabled(key));
                })), 20, false, 90));
    }

    private void settingSlider(String key, double min, double max) {
        rows.add(new Row("settings_" + key, (x, y, w) -> slider(x, y, w, (DesignerPreferences.number(key) - min) / (max - min),
                value -> Component.translatable("designer.setting_value." + key, display(key, min + value * (max - min))),
                value -> {
                    DesignerPreferences.set(key, min + value * (max - min));
                    layoutChanged |= key.equals("ui_scale");
                })));
    }

    private static String display(String key, double value) {
        return key.endsWith("_ms") || key.equals("motion_amount") ? Long.toString(Math.round(value)) : Long.toString(Math.round(value * 100));
    }

    private void importTheme(String value) {
        if (value.isBlank()) {
            status = "designer.error.settings_file";
            return;
        }
        try {
            DesignerProjectFiles.importTheme(Path.of(value), loaded -> {
                try {
                    DesignerPreferences.theme(loaded);
                    custom = DesignerPreferences.palette().json();
                    status = "designer.status.theme_applied";
                    rebuild();
                } catch (RuntimeException exception) {
                    status = exception.getMessage();
                }
            }, message -> status = message);
        } catch (RuntimeException exception) {
            status = "designer.error.settings_file";
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (files.size() == 1) {
            importPath = files.getFirst().toString();
            importTheme(importPath);
        } else {
            status = "designer.error.settings_file";
        }
    }

    @Override
    protected boolean releaseContent(double x, double y, int button) {
        boolean handled = super.releaseContent(x, y, button);
        if (layoutChanged) {
            layoutChanged = false;
            rebuild();
        }
        return handled;
    }

    @Override
    public void onClose() {
        if (saving || isLeaving()) {
            return;
        }
        if (!DesignerPreferences.dirty()) {
            minecraft.setScreen(parent);
            return;
        }
        saving = true;
        DesignerPreferences.save(message -> {
            saving = false;
            if (minecraft.screen != this) {
                return;
            }
            if (message.equals("designer.status.preferences_saved")) {
			minecraft.setScreen(parent);
            } else {
                status = message;
            }
        });
    }
}
