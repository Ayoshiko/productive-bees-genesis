package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeSnapshotCacheMinecraftTest {
    @Test
    void repeatedPaidCyclesKeepOriginalTraitsAndReplacementInvalidatesThem() {
        var original = new BeeSlot();
        CompoundTag data = bee("example:first", "productivity.very_high");
        original.setBeeData(data);
        var state = new ApiaryProductionState(1, () -> {});
        var indices = new OrderedSlotIndex();
        indices.reset(1);
        indices.add(0);
        var profile = original.getGeneSampleProfile();
        for (int cycle = 0; cycle < 3; cycle++) {
            state.counts[0] = 7;
            state.capture(0, original);
            assertNotSame(data, state.bees[0].getBeeData());
            assertEquals(ResourceLocation.parse("example:first"), state.bees[0].getBeeTypeKey());
            assertEquals(3, state.bees[0].getProductivityLevel());
            assertSame(profile, state.bees[0].getGeneSampleProfile());
            state.begin(indices);
            state.converted();
            state.freeze(new BeeProduceProcessor.Generated(List.of(), FluidStack.EMPTY, 0));
            assertTrue(state.bees[0].isEmpty());
            assertNull(state.bees[0].getBeeTypeKey());
        }
        original.setBeeData(bee("example:second", "productivity.medium"));
        state.counts[0] = 5;
        state.capture(0, original);
        assertEquals(ResourceLocation.parse("example:second"), state.bees[0].getBeeTypeKey());
        assertEquals(1, state.bees[0].getProductivityLevel());
        assertNotSame(profile, state.bees[0].getGeneSampleProfile());
        original.clear();
        assertNull(original.getBeeTypeKey());
        assertEquals(0, original.getProductivityLevel());
    }

    @Test
    void flowerTypeIsReusedButContainerContentsStayLiveAndVersionChangesInvalidate() {
        var slot = new BeeSlot();
        slot.setBeeData(bee("example:flower", "productivity.normal"));
        var manager = mock(FeederSlotManager.class);
        var type = ResourceLocation.parse("example:flower");
        when(manager.hasValidFlower(type)).thenReturn(true);
        assertTrue(ApiaryFlowerValidation.check(slot, type, 1, manager));
        assertTrue(ApiaryFlowerValidation.check(slot, type, 1, manager));
        verify(manager, times(1)).hasContainerFlower(type);
        verify(manager, times(1)).hasValidFlower(type);
        when(manager.hasContainerFlower(type)).thenReturn(true);
        when(manager.hasValidFlower(type)).thenReturn(false, true);
        assertFalse(ApiaryFlowerValidation.check(slot, type, 2, manager));
        assertTrue(ApiaryFlowerValidation.check(slot, type, 2, manager));
        verify(manager, times(2)).hasContainerFlower(type);
        verify(manager, times(3)).hasValidFlower(type);
        slot.setBeeData(bee("example:replaced", "productivity.normal"));
        assertTrue(ApiaryFlowerValidation.check(slot, type, 2, manager));
        verify(manager, times(3)).hasContainerFlower(type);
    }

    private static CompoundTag bee(String type, String productivity) {
        var data = new CompoundTag();
        data.putString("id", "productivebees:configurable_bee");
        data.putString("type", type);
        var traits = new CompoundTag();
        traits.putString("bee_productivity", productivity);
        var attachments = new CompoundTag();
        attachments.put("productivebees:attributes_handler", traits);
        data.put("neoforge:attachments", attachments);
        return data;
    }
}
