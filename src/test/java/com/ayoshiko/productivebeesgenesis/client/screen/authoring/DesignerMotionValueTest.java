package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerMotionValueTest {
    @Test
    void interruptedAnimationKeepsPositionAndVelocityAndConvergesAtDifferentFrameRates() {
        var fast = new DesignerMotionValue();
        var slow = new DesignerMotionValue();
        fast.advance(10, 0.04, 0.2, true);
        slow.advance(10, 0.04, 0.2, true);
        double before = fast.position();
        double velocity = fast.velocity();
        fast.advance(-4, 0, 0.2, true);
        assertEquals(before, fast.position());
        assertEquals(velocity, fast.velocity());
        for (int frame = 0; frame < 24; frame++) {
            fast.advance(-4, 1.0 / 120, 0.2, true);
        }
        for (int frame = 0; frame < 6; frame++) {
            slow.advance(-4, 1.0 / 30, 0.2, true);
        }
        assertEquals(fast.position(), slow.position(), 0.00001);
        assertEquals(fast.velocity(), slow.velocity(), 0.00001);
        fast.advance(-4, 0.1, 0.2, false);
        assertEquals(-4, fast.position());
        assertEquals(0, fast.velocity());
    }

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
