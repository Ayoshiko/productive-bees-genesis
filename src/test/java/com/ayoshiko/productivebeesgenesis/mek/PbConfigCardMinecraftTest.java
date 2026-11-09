package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import mekanism.common.network.PacketUtils;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class PbConfigCardMinecraftTest {
	private static final String UPGRADES = "productivebeesgenesis_pb_upgrades";
	private static final String AE_ITEMS = "productivebeesgenesis_ae_item_output";
	private static ServerLevel level;
	private static MockedStatic<PacketUtils> notifications;

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
		level = mock(ServerLevel.class);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		notifications = mockStatic(PacketUtils.class);
	}

	@AfterAll
	static void unloadConfigs() {
		notifications.close();
		ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
	}

	private static ServerPlayer player(boolean full) {
		ServerPlayer player = mock(ServerPlayer.class);
		Inventory inventory = new Inventory(player);
		when(player.getInventory()).thenReturn(inventory);
		when(player.level()).thenReturn(level);
		if (full) {
			for (int slot = 0; slot < inventory.items.size(); slot++) {
				inventory.setItem(slot, new ItemStack(Items.STONE, 64));
			}
		}
		return player;
	}

	private static List<TileEntityMekanism> hosts() {
		List<TileEntityMekanism> hosts = new ArrayList<>();
		for (var id : BuiltInRegistries.BLOCK.keySet().stream().sorted().toList()) {
			if (!id.getNamespace().equals("productivebeesgenesis")
					|| !(id.getPath().contains("mek_apiary") || id.getPath().contains("mek_centrifuge"))) {
				continue;
			}
			var block = BuiltInRegistries.BLOCK.get(id);
			var tile = assertInstanceOf(TileEntityMekanism.class,
					((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState()));
			tile.setLevel(level);
			hosts.add(tile);
		}
		assertFalse(hosts.isEmpty());
		return hosts;
	}

	private static CompoundTag card(TileEntityMekanism tile, int productivity, int time) {
		CompoundTag card = tile.getConfigurationData(level.registryAccess(), null);
		CompoundTag upgrades = new CompoundTag();
		upgrades.putInt(PbUpgradeType.PRODUCTIVITY.getId(), productivity);
		upgrades.putInt(PbUpgradeType.TIME.getId(), time);
		card.put(UPGRADES, upgrades);
		return card;
	}

	private static long count(Inventory inventory, Item item) {
		long count = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	@Test
	void everyRegisteredHostRejectsFullAndPartialReturnsBeforeAnyConfigurationChanges() {
		Item productivity = PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.PRODUCTIVITY).getItem();
		for (TileEntityMekanism tile : hosts()) {
			IPbUpgradeProvider upgrades = assertInstanceOf(IPbUpgradeProvider.class, tile);
			assertEquals(2, upgrades.installPbUpgradeBulk(PbUpgradeType.PRODUCTIVITY, 2));
			assertEquals(1, upgrades.installPbUpgradeBulk(PbUpgradeType.TIME, 1));
			for (boolean partial : List.of(false, true)) {
				ServerPlayer player = player(true);
				if (partial) {
					player.getInventory().setItem(0, new ItemStack(productivity, 63));
				}
				CompoundTag before = tile.getConfigurationData(level.registryAccess(), null);
				CompoundTag target = card(tile, 0, 0);
				target.putBoolean(AE_ITEMS, !before.getBoolean(AE_ITEMS));
				tile.setConfigurationData(level.registryAccess(), player, target);
				assertEquals(before, tile.getConfigurationData(level.registryAccess(), null), tile.getBlockState().toString());
				assertEquals(2, upgrades.getPbUpgradeInstalledCount(PbUpgradeType.PRODUCTIVITY));
				assertEquals(1, upgrades.getPbUpgradeInstalledCount(PbUpgradeType.TIME));
				assertEquals(partial ? 63 : 0, count(player.getInventory(), productivity));
			}
		}
		verify(level, never()).addFreshEntity(any());
	}

	@Test
	void allHostsReuseInstalledItemsAndCanExchangeWithoutTemporaryFreeSpace() {
		Item productivity = PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.PRODUCTIVITY).getItem();
		Item time = PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.TIME).getItem();
		for (TileEntityMekanism tile : hosts()) {
			IPbUpgradeProvider upgrades = assertInstanceOf(IPbUpgradeProvider.class, tile);
			assertEquals(1, upgrades.installPbUpgradeBulk(PbUpgradeType.PRODUCTIVITY, 1));
			ServerPlayer player = player(true);
			tile.setConfigurationData(level.registryAccess(), player, card(tile, 1, 0));
			assertEquals(1, upgrades.getPbUpgradeInstalledCount(PbUpgradeType.PRODUCTIVITY));
			assertEquals(0, count(player.getInventory(), productivity));
			player.getInventory().setItem(0, new ItemStack(time, 1));
			tile.setConfigurationData(level.registryAccess(), player, card(tile, 0, 1));
			assertEquals(0, upgrades.getPbUpgradeInstalledCount(PbUpgradeType.PRODUCTIVITY));
			assertEquals(1, upgrades.getPbUpgradeInstalledCount(PbUpgradeType.TIME));
			assertEquals(1, count(player.getInventory(), productivity));
			assertEquals(0, count(player.getInventory(), time));
		}
		verify(level, never()).addFreshEntity(any());
	}

	@Test
	void survivalConsumesOnlyAvailableItemsAndCreativeDoesNotReturnCopies() {
		var block = ModBlocks.MEK_CENTRIFUGE.get();
		var tile = (TileEntityMekCentrifuge) block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
		assertNotNull(tile);
		tile.setLevel(level);
		Item productivity = PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.PRODUCTIVITY).getItem();
		ServerPlayer player = player(false);
		player.getInventory().setItem(0, new ItemStack(productivity, 2));
		tile.setConfigurationData(level.registryAccess(), player, card(tile, 3, 0));
		assertEquals(2, tile.getPbUpgradeInstalledCount(PbUpgradeType.PRODUCTIVITY));
		assertEquals(0, count(player.getInventory(), productivity));
		when(player.isCreative()).thenReturn(true);
		tile.setConfigurationData(level.registryAccess(), player, card(tile, 0, 1));
		assertEquals(0, tile.getPbUpgradeInstalledCount(PbUpgradeType.PRODUCTIVITY));
		assertEquals(1, tile.getPbUpgradeInstalledCount(PbUpgradeType.TIME));
		assertEquals(0, count(player.getInventory(), productivity));
	}
}
