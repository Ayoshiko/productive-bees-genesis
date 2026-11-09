package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.network.OpenAeInputConfigPayload;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.lib.transmitter.TransmissionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("minecraft")
class AeInputButtonMinecraftTest {
	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@Test
	void nativeClickCreatesOneInputWindowWithConfiguredPagesAndRequestsOneSnapshot() {
		try (var fixture = new Fixture();
				var windows = mockConstruction(GuiAeInputConfig.class, (window, context) -> {
					assertSame(fixture.screen, context.arguments().get(0));
					assertSame(fixture.tile, context.arguments().get(3));
					assertNull(context.arguments().get(4));
				})) {
			assertTrue(fixture.button.visible);
			assertTrue(fixture.button.active);
			assertFalse(fixture.button.mouseClicked(11, 21, 1));
			assertTrue(windows.constructed().isEmpty());

			assertTrue(fixture.button.mouseClicked(11, 21, 0));
			assertEquals(1, windows.constructed().size());
			GuiAeInputConfig window = windows.constructed().getFirst();
			verify(window).setMinPages(ModConfig.SERVER.mekCentrifugeAeInputMinPages.get());
			verify(fixture.screen).addWindow(window);
			fixture.packets.verify(() -> PacketDistributor.sendToServer(
					new OpenAeInputConfigPayload(BlockPos.ZERO)), times(1));

			fixture.button.mouseClicked(11, 21, 0);
			assertEquals(1, windows.constructed().size());
			fixture.packets.verifyNoMoreInteractions();
		}
	}

	@Test
	void disabledTabsStaleMenusAndClosedButtonsDoNotOpenOrRequest() {
		try (var fixture = new Fixture(); var windows = mockConstruction(GuiAeInputConfig.class)) {
			ModConfig.SERVER.mekCentrifugeAeInputEnabled.set(false);
			fixture.button.onClick(11, 21, 0);
			assertTrue(fixture.button.visible);
			assertFalse(fixture.button.active);

			ModConfig.SERVER.mekCentrifugeAeInputEnabled.set(true);
			fixture.type = TransmissionType.FLUID;
			fixture.button.refreshState();
			assertFalse(fixture.button.visible);
			fixture.button.onClick(11, 21, 0);
			fixture.type = TransmissionType.CHEMICAL;
			fixture.button.tick();
			assertFalse(fixture.button.visible);

			fixture.type = TransmissionType.ITEM;
			fixture.player.containerMenu = null;
			fixture.button.onClick(11, 21, 0);
			assertFalse(fixture.button.active);
			fixture.player.containerMenu = fixture.menu;
			fixture.button.tick();
			assertTrue(fixture.button.active);

			when(fixture.level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(TileEntityMekCentrifuge.class));
			fixture.button.onClick(11, 21, 0);
			assertFalse(fixture.button.active);
			when(fixture.level.getBlockEntity(BlockPos.ZERO)).thenReturn(fixture.tile);
			fixture.button.tick();
			assertTrue(fixture.button.active);
			fixture.button.onWindowClose();
			fixture.button.tick();
			fixture.button.onClick(11, 21, 0);
			assertFalse(fixture.button.visible);
			assertFalse(fixture.button.active);
			assertTrue(windows.constructed().isEmpty());
			fixture.packets.verifyNoInteractions();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void transferredControlOpensTheInputWindowInTheNewGuiForTheSameMenu() {
		try (var fixture = new Fixture(); var windows = mockConstruction(GuiAeInputConfig.class)) {
			GuiMekanism<MekanismTileContainer<TileEntityMekCentrifuge>> transferred = mock(GuiMekanism.class);
			when(transferred.getMenu()).thenReturn(fixture.menu);
			when(transferred.getWindows()).thenReturn(List.of());
			fixture.button.transferToNewGui(transferred);
			fixture.minecraft.screen = transferred;
			fixture.button.tick();
			assertTrue(fixture.button.active);
			assertTrue(fixture.button.mouseClicked(11, 21, 0));
			assertEquals(1, windows.constructed().size());
			verify(transferred).addWindow(windows.constructed().getFirst());
			fixture.packets.verify(() -> PacketDistributor.sendToServer(
					new OpenAeInputConfigPayload(BlockPos.ZERO)), times(1));
			fixture.packets.verifyNoMoreInteractions();
		}
	}

	private static final class Fixture implements AutoCloseable {
		final Minecraft minecraft = mock(Minecraft.class);
		final LocalPlayer player = mock(LocalPlayer.class);
		final ClientLevel level = mock(ClientLevel.class);
		final TileEntityMekCentrifuge tile = mock(TileEntityMekCentrifuge.class);
		final MekanismTileContainer<TileEntityMekCentrifuge> menu;
		final GuiMekanism<MekanismTileContainer<TileEntityMekCentrifuge>> screen;
		final List<GuiWindow> windows = new ArrayList<>();
		final AeInputButton button;
		final MockedStatic<PacketDistributor> packets;
		private final MockedStatic<Minecraft> client;
		private final boolean priorEnabled = ModConfig.SERVER.mekCentrifugeAeInputEnabled.get();
		TransmissionType type = TransmissionType.ITEM;

		@SuppressWarnings("unchecked")
		Fixture() {
			ModConfig.SERVER.mekCentrifugeAeInputEnabled.set(true);
			menu = mock(MekanismTileContainer.class);
			screen = mock(GuiMekanism.class);
			when(menu.getTileEntity()).thenReturn(tile);
			when(screen.getMenu()).thenReturn(menu);
			when(screen.getWindows()).thenReturn(windows);
			doAnswer(invocation -> {
				windows.add(invocation.getArgument(0));
				return null;
			}).when(screen).addWindow(any(GuiWindow.class));
			when(tile.getBlockPos()).thenReturn(BlockPos.ZERO);
			when(tile.getLevel()).thenReturn(level);
			when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(tile);
			when(minecraft.getSoundManager()).thenReturn(mock(SoundManager.class));
			player.containerMenu = menu;
			minecraft.player = player;
			minecraft.level = level;
			minecraft.screen = screen;
			client = mockStatic(Minecraft.class);
			client.when(Minecraft::getInstance).thenReturn(minecraft);
			packets = mockStatic(PacketDistributor.class);
			try {
				button = new AeInputButton(screen, 10, 20, menu, () -> type);
			} catch (RuntimeException | Error failure) {
				ModConfig.SERVER.mekCentrifugeAeInputEnabled.set(priorEnabled);
				packets.close();
				client.close();
				throw failure;
			}
		}

		@Override
		public void close() {
			try {
				button.onWindowClose();
				ModConfig.SERVER.mekCentrifugeAeInputEnabled.set(priorEnabled);
			} finally {
				packets.close();
				client.close();
			}
		}
	}
}
