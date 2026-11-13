package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerMotionValueTest {

    @Test
    void longerAutosaveDefaultsMigrateOlderLegalPreferencesWithoutDiscardingOtherSettings() {
        var old = DesignerPreferences.defaults();
        old.addProperty("theme", "custom_dark");
        old.addProperty("motion_style", "fluid");
        old.addProperty("auto_save_ms", 5000);
        var migrated = DesignerPreferences.normalize(old);
        assertEquals(15000, migrated.get("auto_save_ms").getAsInt());
        assertEquals("custom_dark", migrated.get("theme").getAsString());
        assertEquals("responsive", migrated.get("motion_style").getAsString());
        assertEquals(60000, DesignerPreferences.defaults().get("auto_save_ms").getAsInt());
        old.addProperty("auto_save_ms", 600000);
        assertEquals(600000, DesignerPreferences.normalize(old).get("auto_save_ms").getAsInt());
        old.addProperty("auto_save_ms", 600001);
        assertThrows(IllegalArgumentException.class, () -> DesignerPreferences.normalize(old));
    }
}
