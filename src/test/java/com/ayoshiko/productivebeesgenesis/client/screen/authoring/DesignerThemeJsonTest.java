package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerThemeJsonTest {
	@Test
	void roundTripsAllPresetsAndRejectsInvalidThemeStructure() {
		for (String preset : new String[]{"dark", "light", "ae_light"}) {
			DesignerPalette palette = DesignerPalette.preset(preset);
			assertEquals(palette, DesignerPalette.parse(DesignerJsonCodec.parse(palette.json().toString())));
		}
		for (String text : new String[]{"{\"version\":\"1\"}", "{\"version\":1.5}", "{\"light\":\"true\"}",
				"{\"name\":null}", "{\"colors\":[]}", "{\"colors\":{\"accent\":\"#xyzxyz\"}}", "{\"script\":\"x\"}"}) {
			var problem = assertThrows(IllegalArgumentException.class, () -> DesignerPalette.parse(DesignerJsonCodec.parse(text)));
			assertTrue(problem.getMessage().startsWith("designer.error."), text);
		}
	}

	@Test
	void strictJsonRejectsDuplicatesTrailingTextAndExcessiveDepth() {
		for (String text : new String[]{"{\"name\":\"first\",\"name\":\"last\"}", "{} {}", "{name:'x'}", "{\"a\":1,}"}) {
			assertThrows(IllegalArgumentException.class, () -> DesignerJsonCodec.parse(text));
		}
		String deep = "{\"a\":".repeat(34) + "0" + "}".repeat(34);
		assertThrows(IllegalArgumentException.class, () -> DesignerJsonCodec.parse(deep));
	}

	@Test
	void preferenceValidationIsAtomicAndRequiresCorrectCustomThemeKind() {
		JsonObject before = DesignerPreferences.snapshot();
		JsonObject bad = before.deepCopy();
		bad.addProperty("ui_scale", 10);
		assertThrows(IllegalArgumentException.class, () -> DesignerPreferences.apply(bad));
		assertEquals(before, DesignerPreferences.snapshot());
		for (String text : new String[]{"{\"theme\":null}", "{\"density\":true}", "{\"custom_dark\":[]}",
				"{\"custom_light\":{\"light\":false}}", "{\"auto_save\":\"true\"}"}) {
			assertThrows(IllegalArgumentException.class, () -> DesignerPreferences.normalize(DesignerJsonCodec.parse(text)));
		}
		JsonObject normalized = DesignerPreferences.normalize(new JsonObject());
		assertEquals(DesignerPreferences.defaults(), normalized);
		normalized.addProperty("theme", "light");
		assertEquals(before, DesignerPreferences.snapshot());
	}

	@Test
	void geneDisplayPreferenceKeepsLegacyDefaultsAndRejectsUnknownModes() {
		JsonObject legacy = DesignerPreferences.defaults();
		legacy.remove("gene_layout");
		legacy.addProperty("ui_scale", 1.15);
		JsonObject normalized = DesignerPreferences.normalize(legacy);
		assertEquals("dropdown", normalized.get("gene_layout").getAsString());
		assertEquals(1.15, normalized.get("ui_scale").getAsDouble());
		legacy.addProperty("gene_layout", "buttons");
		assertEquals("buttons", DesignerPreferences.normalize(legacy).get("gene_layout").getAsString());
		legacy.addProperty("gene_layout", "unknown");
		assertThrows(IllegalArgumentException.class, () -> DesignerPreferences.normalize(legacy));
	}
}
