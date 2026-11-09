package com.ayoshiko.productivebeesgenesis.network;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.menu.MekCentrifugeContainer;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.ayoshiko.productivebeesgenesis.util.PbCombItemRefs;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import mekanism.api.security.IBlockSecurityUtils;
import mekanism.api.security.SecurityMode;
import mekanism.common.config.MekanismConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@Tag("minecraft")
class MachineMenuAccessMinecraftTest {
    @BeforeAll
    static void loadConfigs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unloadConfigs() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Test
    void publicAndOwnerAccessFollowRealMekanismSecurityAfterMenuCreation() {
        try (var fixture = new Fixture()) {
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            assertSame(fixture.tile, IBlockSecurityUtils.INSTANCE.securityCapability(
                    fixture.level, BlockPos.ZERO, fixture.tile.getBlockState(), fixture.tile));
            boolean initial = fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled();
            fixture.toggleOutput();
            assertEquals(!initial, fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled());

            fixture.securityMode(SecurityMode.PRIVATE);
            assertTrue(fixture.menu.stillValid(fixture.player));
            assertFalse(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            fixture.toggleOutput();
            assertEquals(!initial, fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled());

            when(fixture.player.getUUID()).thenReturn(Fixture.OWNER);
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            fixture.toggleOutput();
            assertEquals(initial, fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled());

            when(fixture.player.getUUID()).thenReturn(Fixture.VISITOR);
            fixture.securityMode(SecurityMode.PUBLIC);
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
        }
    }

    @Test
    void spectatorWithExistingMenuCannotMutateSettingsReturnInputsOrExchangeVirtualSlot() {
        try (var fixture = new Fixture()) {
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            var input = fixture.tile.productivebeesgenesis$getInputSlot(0);
            var stored = new ItemStack(PbCombItemRefs.honeycomb(), 7);
            stored.set(PbDataComponents.beeType(), PBConstants.MYRIADCREATIONS_TYPE);
            assertTrue(fixture.tile.containsRecipe(stored));
            assertTrue(input.isItemValid(stored));
            assertTrue(input.getLimit(stored) >= stored.getCount());
            input.setStack(stored);
            fixture.inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 11));
            fixture.menu.setCarried(stored.copyWithCount(3));
            var holder = fixture.tile.productivebeesgenesis$getAe2StateHolder();
            var filter = holder.getOrCreateInputFilter();
            var key = AEItemKey.of(stored);
            filter.setDirectEntryFingerprintAt(0, Ae2ItemFingerprint.encodeOrLegacy(key, fixture.registries));
            filter.resolveDirectKey(0, key);
            assertTrue(filter.isDirectEntry(0));
            var inputBefore = input.getStack().copy();
            var inventoryBefore = fixture.inventory.getItem(0).copy();
            var cursorBefore = fixture.menu.getCarried().copy();
            boolean outputBefore = holder.isAeItemOutputEnabled();

            when(fixture.player.isSpectator()).thenReturn(true);
            assertTrue(fixture.menu.stillValid(fixture.player));
            assertFalse(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            try (var grid = mockStatic(Ae2GridNodeManager.class)) {
                fixture.toggleOutput();
                CentrifugeInputReturnPayloadHandler.handle(
                        new ReturnCentrifugeInputPayload(BlockPos.ZERO), fixture.context);
                Ae2PayloadHandlers.handleAeInputOutputSlot(
                        new AeInputOutputSlotPayload(BlockPos.ZERO, 0, false, false), fixture.context);
                Ae2PayloadHandlers.handleAeInputOutputSlot(
                        new AeInputOutputSlotPayload(BlockPos.ZERO, 0, true, false), fixture.context);
                grid.verifyNoInteractions();
            }
            assertEquals(outputBefore, holder.isAeItemOutputEnabled());
            assertTrue(ItemStack.matches(inputBefore, input.getStack()));
            assertTrue(ItemStack.matches(inventoryBefore, fixture.inventory.getItem(0)));
            assertTrue(ItemStack.matches(cursorBefore, fixture.menu.getCarried()));
        }
    }

    @Test
    void samePositionReplacementDoesNotAuthorizeTheOriginalMenu() {
        try (var fixture = new Fixture()) {
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            var block = ModBlocks.MEK_CENTRIFUGE.get();
            var replacement = assertInstanceOf(TileEntityMekCentrifuge.class,
                    block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState()));
            replacement.setLevel(fixture.level);
            boolean originalOutput = fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled();
            boolean replacementOutput = replacement.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled();
            when(fixture.level.getBlockEntity(BlockPos.ZERO)).thenReturn(replacement);

            assertTrue(fixture.menu.stillValid(fixture.player));
            assertFalse(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            fixture.toggleOutput();

            assertEquals(originalOutput, fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled());
            assertEquals(replacementOutput, replacement.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled());
        }
    }

    @Test
    void unloadedInvalidAndDistantMenusCannotChangeTheMachine() {
        try (var fixture = new Fixture()) {
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            boolean initial = fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled();
            when(fixture.level.hasChunkAt(BlockPos.ZERO)).thenReturn(false);
            assertFalse(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            fixture.toggleOutput();
            assertEquals(initial, fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled());

            when(fixture.level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            when(fixture.level.isInWorldBounds(BlockPos.ZERO)).thenReturn(false);
            assertFalse(fixture.menu.stillValid(fixture.player));
            assertFalse(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            fixture.toggleOutput();
            assertEquals(initial, fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled());

            when(fixture.level.isInWorldBounds(BlockPos.ZERO)).thenReturn(true);
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            when(fixture.player.distanceToSqr(BlockPos.ZERO.getCenter()))
                    .thenReturn(NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ + 1);
            assertFalse(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            fixture.toggleOutput();
            assertEquals(initial, fixture.tile.productivebeesgenesis$getAe2StateHolder().isAeItemOutputEnabled());
        }
    }

    static final class Fixture implements AutoCloseable {
        static final UUID OWNER = UUID.fromString("01000000-0000-0000-0000-000000000001");
        static final UUID VISITOR = UUID.fromString("01000000-0000-0000-0000-000000000002");
        final RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final Inventory inventory = new Inventory(player);
        final IPayloadContext context = mock(IPayloadContext.class);
        final TileEntityMekCentrifuge tile;
        final MekCentrifugeContainer<TileEntityMekCentrifuge> menu;
        private final boolean previousProtection = MekanismConfig.general.allowProtection.get();
        private final boolean previousBypass = MekanismConfig.general.opsBypassRestrictions.get();

        Fixture() {
            MekanismConfig.general.allowProtection.set(true);
            MekanismConfig.general.opsBypassRestrictions.set(false);
            var server = mock(MinecraftServer.class);
            when(server.isSameThread()).thenReturn(true);
            when(server.getRecipeManager()).thenReturn(mock(RecipeManager.class));
            when(level.getServer()).thenReturn(server);
            when(level.registryAccess()).thenReturn(registries);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
            when(level.hasChunk(0, 0)).thenReturn(true);
            when(level.isInWorldBounds(BlockPos.ZERO)).thenReturn(true);
            when(player.level()).thenReturn(level);
            when(player.serverLevel()).thenReturn(level);
            when(player.registryAccess()).thenReturn(registries);
            when(player.getUUID()).thenReturn(VISITOR);
            when(player.getName()).thenReturn(Component.literal("RP01"));
            when(player.getAbilities()).thenReturn(new Abilities());
            when(player.getInventory()).thenReturn(inventory);
            when(context.player()).thenReturn(player);
            var block = ModBlocks.MEK_CENTRIFUGE.get();
            var state = block.defaultBlockState();
            tile = assertInstanceOf(TileEntityMekCentrifuge.class, block.newBlockEntity(BlockPos.ZERO, state));
            tile.setLevel(level);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(tile);
            when(level.getBlockState(BlockPos.ZERO)).thenReturn(state);
            when(level.getCapability(IBlockSecurityUtils.INSTANCE.securityCapability(),
                    BlockPos.ZERO, state, tile, null)).thenReturn(tile);
            securityMode(SecurityMode.PUBLIC);
            menu = new MekCentrifugeContainer<>(ModMenuTypes.MEK_CENTRIFUGE, 1, inventory, tile);
            player.containerMenu = menu;
            assertTrue(menu.stillValid(player));
        }

        void securityMode(SecurityMode mode) {
            // 使用正式组件 decoder 还原已存权限，不创建安全频率或触发关菜单广播。
            var data = new CompoundTag();
            data.putUUID("owner", OWNER);
            data.putInt("security_mode", mode.ordinal());
            tile.getSecurity().deserialize(data, registries);
            assertEquals(OWNER, tile.getOwnerUUID());
            assertEquals(mode, tile.getSecurityMode());
        }

        void toggleOutput() {
            Ae2PayloadHandlers.handleCycleAeOutput(
                    new CycleAeOutputPayload(BlockPos.ZERO, CycleAeOutputPayload.OutputType.ITEM), context);
        }

        @Override
        public void close() {
            tile.close(player);
            MekanismConfig.general.opsBypassRestrictions.set(previousBypass);
            MekanismConfig.general.allowProtection.set(previousProtection);
        }
    }
}
