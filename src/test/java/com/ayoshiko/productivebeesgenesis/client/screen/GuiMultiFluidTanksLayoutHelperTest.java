package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuiMultiFluidTanksLayoutHelperTest {

    @Test
    void nineteenSlotsFitNarrowAndWideViewportsWithoutOverlapping() {
        for (int viewport : List.of(320, 480)) {
            int available = GuiMultiFluidTanksLayoutHelper.calculateAvailableWidth(viewport);
            for (int count : List.of(1, 18, 19)) {
                int cols = GuiMultiFluidTanksLayoutHelper.calculateCols(count, available);
                int width = GuiMultiFluidTanksLayoutHelper.calculateWindowWidth(count, available);
                int height = GuiMultiFluidTanksLayoutHelper.calculateWindowHeight(count, cols);
                assertTrue(width <= available);
                var positions = new HashSet<String>();
                for (int index = 0; index < count; index++) {
                    int x = GuiMultiFluidTanksLayoutHelper.calculateGaugeX(index, cols);
                    int y = GuiMultiFluidTanksLayoutHelper.calculateGaugeY(index, cols);
                    assertTrue(x >= GuiMultiFluidTanksLayoutHelper.LEFT_PADDING);
                    assertTrue(x + GuiMultiFluidTanksLayoutHelper.GAUGE_W
                            <= width - GuiMultiFluidTanksLayoutHelper.RIGHT_PADDING);
                    assertTrue(y + GuiMultiFluidTanksLayoutHelper.GAUGE_H <= height);
                    assertTrue(positions.add(x + ":" + y));
                }
                assertEquals(count, positions.size());
                assertEquals(viewport == 320 && count > cols ? 2 : 1,
                        GuiMultiFluidTanksLayoutHelper.calculateVisibleRows(count, cols));
            }
        }
    }

    @Test
    void mainTankOnlyKeepsAClosableEmptyWindow() {
        int available = GuiMultiFluidTanksLayoutHelper.calculateAvailableWidth(320);
        int cols = GuiMultiFluidTanksLayoutHelper.calculateCols(0, available);
        assertEquals(0, GuiMultiFluidTanksLayoutHelper.calculateVisibleRows(0, cols));
        assertTrue(GuiMultiFluidTanksLayoutHelper.calculateWindowWidth(0, available) >= 80);
        assertTrue(GuiMultiFluidTanksLayoutHelper.calculateWindowHeight(0, cols)
                > GuiMultiFluidTanksLayoutHelper.GRID_Y);
    }
}
