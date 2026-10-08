package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2TemplateDirectPushMinecraftTest {
	@Test
	void explicitAmountAndExactComponentsReachStorageWithoutCopyingTemplate() {
		ItemStack template = spy(new ItemStack(Items.HONEYCOMB));
		template.set(DataComponents.CUSTOM_NAME, Component.literal("exact variant"));
		MEStorage storage = mock(MEStorage.class);
		AEItemKey key = AEItemKey.of(template);
		when(storage.insert(eq(key), eq(4096L), eq(Actionable.MODULATE), any())).thenReturn(1024L);
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 123L, null);
		assertEquals(1024, session.push(template, 4096));
		verify(storage).insert(eq(key), eq(4096L), eq(Actionable.MODULATE), any());
		assertEquals(1, template.getCount());
		assertEquals(key, AEItemKey.of(template));
		verify(template, never()).copyWithCount(anyInt());
	}

	@Test
	void disabledTargetAndInvalidAmountDoNotAllocateOrInsert() {
		ItemStack template = spy(new ItemStack(Items.HONEYCOMB));
		var host = mock(IAe2OutputHostBase.class);
		assertEquals(0, Ae2OutputPusher.pushItemStack(host, template, 4096));
		MEStorage storage = mock(MEStorage.class);
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 124L, null);
		assertEquals(0, session.push(template, 0));
		assertEquals(0, session.push(template, -1));
		verifyNoInteractions(storage);
		verify(template, never()).copyWithCount(anyInt());
	}

	@Test
	void legacyStackEntryPointStillUsesItsOwnCountAndClampsAcceptance() {
		MEStorage storage = mock(MEStorage.class);
		when(storage.insert(any(), anyLong(), eq(Actionable.MODULATE), any())).thenReturn(Long.MAX_VALUE);
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 125L, null);
		ItemStack stack = new ItemStack(Items.IRON_INGOT, 37);
		assertEquals(37, session.applyAsInt(stack));
		verify(storage).insert(eq(AEItemKey.of(stack)), eq(37L), eq(Actionable.MODULATE), any());
		assertEquals(37, stack.getCount());
	}
}
