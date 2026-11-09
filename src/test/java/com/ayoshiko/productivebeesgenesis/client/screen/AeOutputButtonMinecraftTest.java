package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload;
import mekanism.client.gui.GuiMekanism;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("minecraft")
class AeOutputButtonMinecraftTest {
	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@Test
	void nativeLeftClickSendsOnceAndUsesTheCurrentTypeWithoutClientMutation() {
		try (var fixture = new Fixture(false)) {
			assertTrue(fixture.button.visible);
			assertTrue(fixture.button.active);
			assertEquals(0x009E45, fixture.button.getButtonTextColor(0, 0));
			assertTrue(fixture.button.mouseClicked(11, 21, 0));
			fixture.packets.verify(() -> PacketDistributor.sendToServer(
					new CycleAeOutputPayload(BlockPos.ZERO, CycleAeOutputPayload.OutputType.ITEM)), times(1));
			assertFalse(fixture.button.isValidClickButton(1));
			assertFalse(fixture.button.mouseClicked(11, 21, 1));

			fixture.type = TransmissionType.FLUID;
			assertTrue(fixture.button.mouseClicked(11, 21, 0));
			fixture.packets.verify(() -> PacketDistributor.sendToServer(
					new CycleAeOutputPayload(BlockPos.ZERO, CycleAeOutputPayload.OutputType.FLUID)), times(1));
			assertEquals(0x232323, fixture.button.getButtonTextColor(0, 0));
			when(fixture.host.productivebeesgenesis$isAeFluidOutputEnabled()).thenReturn(true);
			fixture.button.tick();
			assertEquals(0x009E45, fixture.button.getButtonTextColor(0, 0));
			verify(fixture.host, never()).toggleAeItemOutput();
			verify(fixture.host, never()).toggleAeFluidOutput();
			fixture.packets.verifyNoMoreInteractions();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void globalConfigurationAndUnsupportedTabsDisableTheActualButton(boolean apiary) {
		try (var fixture = new Fixture(apiary)) {
			if (apiary) {
				ModConfig.SERVER.apiaryAeOutputEnabled.set(false);
			} else {
				ModConfig.SERVER.mekCentrifugeAeOutputEnabled.set(false);
			}
			fixture.button.tick();
			assertTrue(fixture.button.visible);
			assertFalse(fixture.button.active);
			assertEquals(0x232323, fixture.button.getButtonTextColor(0, 0));
			assertFalse(fixture.button.mouseClicked(11, 21, 0));

			fixture.type = TransmissionType.FLUID;
			fixture.button.refreshState();
			assertTrue(fixture.button.active);
			if (apiary) {
				ModConfig.SERVER.apiaryAeFluidOutputEnabled.set(false);
			} else {
				ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled.set(false);
			}
			fixture.button.tick();
			assertTrue(fixture.button.visible);
			assertFalse(fixture.button.active);

			fixture.type = TransmissionType.CHEMICAL;
			fixture.button.refreshState();
			assertFalse(fixture.button.visible);
			assertFalse(fixture.button.active);
			fixture.button.onClick(11, 21, 0);
			fixture.packets.verifyNoInteractions();
		}
	}

	@Test
	void staleMenusReplacedTilesAndClosedWindowsCannotSend() {
		try (var fixture = new Fixture(false)) {
			fixture.player.containerMenu = null;
			fixture.button.onClick(11, 21, 0);
			assertFalse(fixture.button.active);
			fixture.player.containerMenu = fixture.menu;
			fixture.button.tick();
			assertTrue(fixture.button.active);

			fixture.minecraft.screen = null;
			fixture.button.onClick(11, 21, 0);
			assertFalse(fixture.button.active);
			fixture.minecraft.screen = fixture.screen;
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
			fixture.packets.verifyNoInteractions();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void transferToAnotherGuiForTheSameMenuKeepsTheWindowControlUsable() {
		try (var fixture = new Fixture(false)) {
			GuiMekanism<MekanismTileContainer<TileEntityMekanism>> transferred = mock(GuiMekanism.class);
			when(transferred.getMenu()).thenReturn(fixture.menu);
			fixture.button.transferToNewGui(transferred);
			fixture.minecraft.screen = transferred;
			fixture.button.tick();
			assertTrue(fixture.button.active);
			assertTrue(fixture.button.mouseClicked(11, 21, 0));
			fixture.packets.verify(() -> PacketDistributor.sendToServer(
					new CycleAeOutputPayload(BlockPos.ZERO, CycleAeOutputPayload.OutputType.ITEM)), times(1));
			fixture.packets.verifyNoMoreInteractions();
		}
	}

	private static final class Fixture implements AutoCloseable {
		final Minecraft minecraft = mock(Minecraft.class);
		final LocalPlayer player = mock(LocalPlayer.class);
		final ClientLevel level = mock(ClientLevel.class);
		final MekanismTileContainer<TileEntityMekanism> menu;
		final GuiMekanism<MekanismTileContainer<TileEntityMekanism>> screen;
		final TileEntityMekanism tile;
		final IAe2OutputHostBase host;
		final AeOutputButton button;
		final MockedStatic<PacketDistributor> packets;
		private final MockedStatic<Minecraft> client;
		private final boolean priorApiaryItems = ModConfig.SERVER.apiaryAeOutputEnabled.get();
		private final boolean priorApiaryFluids = ModConfig.SERVER.apiaryAeFluidOutputEnabled.get();
		private final boolean priorCentrifugeItems = ModConfig.SERVER.mekCentrifugeAeOutputEnabled.get();
		private final boolean priorCentrifugeFluids = ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled.get();
		TransmissionType type = TransmissionType.ITEM;

		@SuppressWarnings("unchecked")
		Fixture(boolean apiary) {
			ModConfig.SERVER.apiaryAeOutputEnabled.set(true);
			ModConfig.SERVER.apiaryAeFluidOutputEnabled.set(true);
			ModConfig.SERVER.mekCentrifugeAeOutputEnabled.set(true);
			ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled.set(true);
			tile = apiary ? mock(TileEntityMekApiary.class) : mock(TileEntityMekCentrifuge.class);
			host = (IAe2OutputHostBase) tile;
			menu = mock(MekanismTileContainer.class);
			screen = mock(GuiMekanism.class);
			when(menu.getTileEntity()).thenReturn(tile);
			when(screen.getMenu()).thenReturn(menu);
			when(tile.getBlockPos()).thenReturn(BlockPos.ZERO);
			when(tile.getLevel()).thenReturn(level);
			when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(tile);
			when(host.productivebeesgenesis$isAeItemOutputEnabled()).thenReturn(true);
			when(host.productivebeesgenesis$isAeFluidOutputEnabled()).thenReturn(false);
			when(minecraft.getSoundManager()).thenReturn(mock(SoundManager.class));
			player.containerMenu = menu;
			minecraft.player = player;
			minecraft.level = level;
			minecraft.screen = screen;
			client = mockStatic(Minecraft.class);
			client.when(Minecraft::getInstance).thenReturn(minecraft);
			packets = mockStatic(PacketDistributor.class);
			try {
				button = new AeOutputButton(screen, 10, 20, menu, () -> type);
			} catch (RuntimeException | Error failure) {
				restoreConfigs();
				packets.close();
				client.close();
				throw failure;
			}
		}

		private void restoreConfigs() {
			ModConfig.SERVER.apiaryAeOutputEnabled.set(priorApiaryItems);
			ModConfig.SERVER.apiaryAeFluidOutputEnabled.set(priorApiaryFluids);
			ModConfig.SERVER.mekCentrifugeAeOutputEnabled.set(priorCentrifugeItems);
			ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled.set(priorCentrifugeFluids);
		}

		@Override
		public void close() {
			try {
				button.onWindowClose();
				restoreConfigs();
			} finally {
				packets.close();
				client.close();
			}
		}
	}
}
