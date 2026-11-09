package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryWrenchDismantleHandler;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import cy.jdkdigital.productivelib.registry.LibItems;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mekanism.api.security.IBlockSecurityUtils;
import mekanism.api.security.SecurityMode;
import mekanism.common.base.MekanismPermissions;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.item.ItemConfigurator;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.registries.MekanismItems;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class MachineDismantleMinecraftTest {
	private static final BlockPos POS = new BlockPos(3, 70, 5);
	private static final UUID OWNER = UUID.fromString("7a4a5aa3-6a09-4a67-a9df-0ced73098d02");
	private static final UUID VISITOR = UUID.fromString("68b11ae0-d1b9-4260-b1d8-98368e26440a");

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
	}

	@Test
	void privateVisitorAndSpectatorAreRejectedBeforeDropSerialization() {
		for (boolean spectator : new boolean[] {false, true}) {
			try (var fixture = new Fixture(true)) {
				fixture.setSecurity(spectator ? OWNER : VISITOR, SecurityMode.PRIVATE);
				when(fixture.player.isSpectator()).thenReturn(spectator);
				var before = fixture.sourceSnapshot();
				var inventory = fixture.inventorySnapshot();

				assertEquals(InteractionResult.FAIL, fixture.interact());

				fixture.drops.verify(() -> WorldUtils.getDrops(fixture.state, fixture.level,
						POS, fixture.tile, fixture.player, fixture.tool), never());
				verify(fixture.loot, never()).getRandomItems(any(LootParams.class));
				verify(fixture.level, never()).removeBlock(POS, false);
				fixture.assertSourceUnchanged(before);
				fixture.assertInventoryEquals(inventory);
				fixture.assertNoWorldItems();
			}
		}
	}

	@Test
	void fullAndPartiallyAvailableInventoryLeaveBothMachineFamiliesUntouched() {
		for (boolean apiary : new boolean[] {true, false}) {
			for (boolean oneEmptySlot : new boolean[] {false, true}) {
				try (var fixture = new Fixture(apiary)) {
					fixture.fillInventory();
					if (oneEmptySlot) {
						fixture.inventory.setItem(35, ItemStack.EMPTY);
						fixture.extraDrops = List.of(new ItemStack(Items.GOLD_INGOT, 2));
					}
					var before = fixture.sourceSnapshot();
					var inventory = fixture.inventorySnapshot();

					assertEquals(InteractionResult.FAIL, fixture.interact());

					verify(fixture.loot).getRandomItems(any(LootParams.class));
					verify(fixture.level, never()).removeBlock(POS, false);
					fixture.assertSourceUnchanged(before);
					fixture.assertInventoryEquals(inventory);
					fixture.assertNoWorldItems();
					assertFalse(MachineDismantle.isPreviewing(fixture.tile));
				}
			}
		}
	}

	@Test
	void successfulEventReturnsTheCompleteMachineAndOnlyThenClearsItsSource() {
		for (boolean apiary : new boolean[] {true, false}) {
			try (var fixture = new Fixture(apiary)) {
				fixture.setSecurity(VISITOR, SecurityMode.PUBLIC);
				var expected = fixture.sourceSnapshot();
				assertNotNull(expected.get(DataComponents.BLOCK_ENTITY_DATA));
				when(fixture.level.removeBlock(POS, false)).thenAnswer(invocation -> {
					assertTrue(ItemStack.matches(expected, fixture.inventory.getItem(0)));
					assertFalse(fixture.sourceSlot.getStack().isEmpty());
					fixture.currentTile = null;
					fixture.currentState = Blocks.AIR.defaultBlockState();
					return true;
				});

				assertEquals(InteractionResult.SUCCESS, fixture.interact());

				assertTrue(ItemStack.matches(expected, fixture.inventory.getItem(0)));
				assertTrue(fixture.sourceSlot.getStack().isEmpty());
				verify(fixture.level).removeBlock(POS, false);
				fixture.assertNoWorldItems();
				assertFalse(MachineDismantle.isPreviewing(fixture.tile));
			}
		}
	}

	@Test
	void rejectedRemovalRollsBackTheInventoryWithoutClearingSource() {
		try (var fixture = new Fixture(true)) {
			var before = fixture.sourceSnapshot();
			var inventory = fixture.inventorySnapshot();
			when(fixture.level.removeBlock(POS, false)).thenReturn(false);

			assertEquals(InteractionResult.FAIL, fixture.interact());

			verify(fixture.level).removeBlock(POS, false);
			fixture.assertSourceUnchanged(before);
			fixture.assertInventoryEquals(inventory);
			fixture.assertNoWorldItems();
			assertFalse(MachineDismantle.isPreviewing(fixture.tile));
		}
	}

	@Test
	void removalFailureBeforeSideEffectsRollsBackAndKeepsTheMachine() {
		try (var fixture = new Fixture(false)) {
			var before = fixture.sourceSnapshot();
			var inventory = fixture.inventorySnapshot();
			when(fixture.level.removeBlock(POS, false))
					.thenThrow(new IllegalStateException("fixed removal failure"));

			assertEquals(InteractionResult.FAIL, fixture.interact());

			fixture.assertSourceUnchanged(before);
			fixture.assertInventoryEquals(inventory);
			fixture.assertNoWorldItems();
			assertFalse(MachineDismantle.isPreviewing(fixture.tile));
		}
	}

	@Test
	void previewFailureReleasesItsScopeWithoutMutatingAssets() {
		try (var fixture = new Fixture(true)) {
			var before = fixture.sourceSnapshot();
			var inventory = fixture.inventorySnapshot();
			var failure = new IllegalStateException("fixed loot failure");
			when(fixture.loot.getRandomItems(any(LootParams.class))).thenThrow(failure);

			assertSame(failure, assertThrows(IllegalStateException.class, fixture::interact));

			assertFalse(MachineDismantle.isPreviewing(fixture.tile));
			verify(fixture.level, never()).removeBlock(POS, false);
			fixture.assertSourceUnchanged(before);
			fixture.assertInventoryEquals(inventory);
			fixture.assertNoWorldItems();
		}
	}

	private static RegistryAccess registries() {
		return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	/** 真实注册机器、槽位和序列化；世界移除与掉落表为确定性的测试替身。 */
	private static final class Fixture implements AutoCloseable {
		final ServerLevel level = mock(ServerLevel.class);
		final ServerPlayer player = mock(ServerPlayer.class);
		final Inventory inventory = new Inventory(player);
		final LootTable loot = mock(LootTable.class);
		final BlockState state;
		final TileEntityMekanism tile;
		final BasicInventorySlot sourceSlot;
		final ItemStack tool = new ItemStack(MekanismItems.CONFIGURATOR.get());
		final MockedStatic<PermissionAPI> permissions;
		final MockedStatic<WorldUtils> drops;
		BlockEntity currentTile;
		BlockState currentState;
		List<ItemStack> extraDrops = List.of();

		Fixture(boolean apiary) {
			if (apiary) {
				var block = ModBlocks.MEK_APIARY.get();
				state = block.defaultBlockState();
				var source = (TileEntityMekApiary) block.newBlockEntity(POS, state);
				tile = source;
				sourceSlot = source.getPbUpgradeInputSlot();
			} else {
				var block = ModBlocks.MEK_CENTRIFUGE.get();
				state = block.defaultBlockState();
				var source = (TileEntityMekCentrifuge) block.newBlockEntity(POS, state);
				tile = source;
				sourceSlot = source.getPbUpgradeInputSlot();
			}
			currentTile = tile;
			currentState = state;
			var chunkSource = mock(ServerChunkCache.class);
			var server = mock(MinecraftServer.class);
			var reloadable = mock(ReloadableServerRegistries.Holder.class);
			when(level.getChunkSource()).thenReturn(chunkSource);
			when(chunkSource.hasChunk(POS.getX() >> 4, POS.getZ() >> 4)).thenReturn(true);
			when(level.getBlockEntity(POS)).thenAnswer(invocation -> currentTile);
			when(level.getBlockState(POS)).thenAnswer(invocation -> currentState);
			when(level.mayInteract(player, POS)).thenReturn(true);
			when(level.registryAccess()).thenReturn(registries());
			when(level.getServer()).thenReturn(server);
			when(server.reloadableRegistries()).thenReturn(reloadable);
			when(reloadable.getLootTable(state.getBlock().getLootTable())).thenReturn(loot);
			when(loot.getRandomItems(any(LootParams.class))).thenAnswer(invocation -> {
				var result = new ObjectArrayList<ItemStack>();
				result.add(new ItemStack(state.getBlock()));
				for (ItemStack extra : extraDrops) {
					result.add(extra.copy());
				}
				return result;
			});
			when(player.level()).thenReturn(level);
			when(player.isAlive()).thenReturn(true);
			when(player.mayBuild()).thenReturn(true);
			when(player.isShiftKeyDown()).thenReturn(true);
			when(player.canInteractWithBlock(POS, 0)).thenReturn(true);
			when(player.getInventory()).thenReturn(inventory);
			tool.set(MekanismDataComponents.CONFIGURATOR_MODE.get(), ItemConfigurator.ConfiguratorMode.WRENCH);
			inventory.setItem(Inventory.SLOT_OFFHAND, tool);
			when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(tool);
			assertTrue(WrenchCapabilityHelper.canUseAsWrench(tool));
			tile.setLevel(level);
			tile.setCustomName(Component.literal("保留完整组件的机器"));
			var content = new ItemStack(LibItems.UPGRADE_TIME.get(), 3);
			var identity = new CompoundTag();
			identity.putString("fixture", "dismantle-exact-components");
			content.set(DataComponents.CUSTOM_DATA, CustomData.of(identity));
			content.set(DataComponents.CUSTOM_NAME, Component.literal("原升级"));
			sourceSlot.setStack(content);
			when(level.getCapability(IBlockSecurityUtils.INSTANCE.securityCapability(),
					POS, state, tile, null)).thenReturn(tile);
			when(level.getCapability(IBlockSecurityUtils.INSTANCE.ownerCapability(),
					POS, state, tile, null)).thenReturn(tile);
			setSecurity(OWNER, SecurityMode.PRIVATE);
			permissions = mockStatic(PermissionAPI.class);
			permissions.when(() -> PermissionAPI.getPermission(player, MekanismPermissions.BYPASS_SECURITY))
					.thenReturn(false);
			drops = mockStatic(WorldUtils.class, invocation -> {
				if (invocation.getMethod().getName().equals("getDrops")) {
					return List.of();
				}
				return invocation.callRealMethod();
			});
			drops.when(() -> WorldUtils.getDrops(state, level, POS, tile, player, tool))
					.thenAnswer(invocation -> {
						assertTrue(MachineDismantle.isPreviewing(tile));
						return Block.getDrops(state, level, POS, tile, player, tool);
					});
		}

		void setSecurity(UUID playerId, SecurityMode mode) {
			var data = new CompoundTag();
			data.putUUID("owner", OWNER);
			data.putInt("security_mode", mode.ordinal());
			tile.getSecurity().deserialize(data, registries());
			when(player.getUUID()).thenReturn(playerId);
			assertEquals(mode, tile.getSecurityMode());
			assertEquals(OWNER, tile.getOwnerUUID());
		}

		InteractionResult interact() {
			var hit = new BlockHitResult(POS.getCenter(), Direction.UP, POS, false);
			var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.OFF_HAND, POS, hit);
			ApiaryWrenchDismantleHandler.onRightClickBlock(event);
			assertTrue(event.isCanceled());
			return event.getCancellationResult();
		}

		ItemStack sourceSnapshot() {
			var result = new ItemStack(state.getBlock());
			tile.saveToItem(result, registries());
			result.set(DataComponents.CUSTOM_NAME, tile.getCustomName());
			return result;
		}

		void assertSourceUnchanged(ItemStack before) {
			assertSame(tile, currentTile);
			assertSame(state, currentState);
			assertFalse(sourceSlot.getStack().isEmpty());
			assertTrue(ItemStack.matches(before, sourceSnapshot()));
		}

		void fillInventory() {
			for (int slot = 0; slot < inventory.items.size(); slot++) {
				var stack = new ItemStack(Items.STONE);
				stack.setCount(inventory.getMaxStackSize(stack));
				inventory.setItem(slot, stack);
			}
		}

		List<ItemStack> inventorySnapshot() {
			var result = new ArrayList<ItemStack>();
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				result.add(inventory.getItem(slot).copy());
			}
			return result;
		}

		void assertInventoryEquals(List<ItemStack> before) {
			for (int slot = 0; slot < before.size(); slot++) {
				assertTrue(ItemStack.matches(before.get(slot), inventory.getItem(slot)), "slot " + slot);
			}
		}

		void assertNoWorldItems() {
			verify(level, never()).addFreshEntity(any(Entity.class));
			verify(player, never()).drop(any(ItemStack.class), anyBoolean());
		}

		@Override
		public void close() {
			drops.close();
			permissions.close();
		}
	}
}
