package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import io.netty.buffer.Unpooled;
import mekanism.api.RelativeSide;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceSideConfigMinecraftTest {
    @BeforeAll static void configs() {
        net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }
    @AfterAll static void unload() {
        net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
    }
    private static TileEntityMekCentrifuge tile() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
        return new TileEntityMekCentrifuge(Holder.direct(block), BlockPos.ZERO, block.defaultBlockState());
    }
    @Test void relativeSidesRotateAndTypesRemainIndependent() {
        var tile = tile();
        var config = new ResourceSideConfig();
        assertEquals(0, config.mask(ResourceNativeType.ENERGY));
        assertTrue(config.eject(ResourceNativeType.AIR));
        assertFalse(config.eject(ResourceNativeType.CHEMICAL));
        for (var facing : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            tile.setBlockState(mekanism.common.block.attribute.Attribute.setFacing(tile.getBlockState(), facing));
            for (var side : RelativeSide.values()) {
                config.set(ResourceNativeType.CHEMICAL, 1 << side.ordinal(), true);
                for (var actual : net.minecraft.core.Direction.values()) {
                    assertEquals(actual == side.getDirection(facing), config.output(tile, ResourceNativeType.CHEMICAL, actual));
                }
                assertEquals(0, config.mask(ResourceNativeType.ENERGY));
                assertFalse(config.eject(ResourceNativeType.ENERGY));
            }
        }
        assertEquals(config.save(), ResourceSideConfig.load(config.save()).save());
    }
    @Test void malformedConfigurationIsRetainedWithoutErasingTheAccountReference() {
        var tile = tile();
        var state = tile.getData(ResourceHiveHost.TYPE);
        var raw = StringTag.valueOf("future native settings");
        state.readNativeConfig(tile, raw);
        assertFalse(state.nativeConfigValid());
        assertFalse(state.nativeOutput(tile, ResourceNativeType.CHEMICAL, null));
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved = state.serializeNBT(provider);
        var restored = new ResourceHiveHost();
        restored.deserializeNBT(provider, saved);
        assertEquals(raw, restored.nativeConfigData());
        assertEquals(saved, restored.serializeNBT(provider));
        for (var field : new String[]{"version", "outputs", "eject", "revision"}) {
            var tag = new ResourceSideConfig().save();
            tag.put(field, raw);
            assertThrows(IllegalArgumentException.class, () -> ResourceSideConfig.load(tag));
        }
        var extreme = new ResourceSideConfig().save();
        extreme.putLong("revision", Long.MAX_VALUE);
        var config = ResourceSideConfig.load(extreme);
        assertThrows(ArithmeticException.class, () -> config.set(ResourceNativeType.CHEMICAL, 1, true));
        assertEquals(extreme, config.save());
    }
    @Test void sustainedDataAndMachineItemCarryOnlySideSettings() {
        var tile = tile();
        var state = tile.getData(ResourceHiveHost.TYPE);
        state.setNativeOutput(tile, ResourceNativeType.CHEMICAL, 5, true);
        state.setNativeOutput(tile, ResourceNativeType.ENERGY, 8, false);
        var sustained = new CompoundTag();
        ResourceSidePersistence.write(tile, sustained);
        assertEquals(java.util.Set.of(ResourceSidePersistence.KEY), sustained.getAllKeys());
        var other = tile();
        ResourceSidePersistence.read(other, sustained);
        assertEquals(state.nativeConfigData(), other.getData(ResourceHiveHost.TYPE).nativeConfigData());
        ResourceSidePersistence.read(other, new CompoundTag());
        assertEquals(state.nativeConfigData(), other.getData(ResourceHiveHost.TYPE).nativeConfigData());
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var stack = new ItemStack(tile.getBlockState().getBlock());
        tile.saveToItem(stack, provider);
        assertNotNull(stack.get(DataComponents.BLOCK_ENTITY_DATA));
        var tag = stack.get(DataComponents.BLOCK_ENTITY_DATA).copyTag();
        assertEquals(sustained.get(ResourceSidePersistence.KEY), tag.get(ResourceSidePersistence.KEY));
        var replacement = tile();
        replacement.loadCustomOnly(tag, provider);
        assertEquals(state.nativeConfigData(), replacement.getData(ResourceHiveHost.TYPE).nativeConfigData());
        var card = tile.getConfigurationData(provider, null);
        assertEquals(sustained.get(ResourceSidePersistence.KEY), card.get(ResourceSidePersistence.KEY));
        assertFalse(card.contains("source"));
        assertFalse(card.contains("emc_recipient"));
        assertFalse(card.contains("neoforge:attachments"));
    }
    @Test void cachedChemicalHandlerStopsExtractingWhenItsSideCloses() {
        var account = ResourceAccountsMinecraftTest.account(new ResourceAccounts(() -> {}));
        ResourceAccountsMinecraftTest.produce(account, 100);
        var port = mock(ResourceOutputAccess.class);
        when(port.account()).thenReturn(account);
        when(port.reference()).thenReturn(account.reference());
        when(port.capacity()).thenReturn(1000L);
        when(port.outputAllowed(ResourceNativeType.CHEMICAL)).thenReturn(true);
        var output = new ResourceChemicalOutput(port);
        assertEquals(100, output.getChemicalInTank(0).getAmount());
        when(port.outputAllowed(ResourceNativeType.CHEMICAL)).thenReturn(false);
        assertTrue(output.extractChemical(100, mekanism.api.Action.EXECUTE).isEmpty());
        assertEquals(100L, account.available().get(ResourceAccountsMinecraftTest.HYDROGEN));
    }
    @Test void nativeEnergyModesUseSeparateContainersAndPersistWithMachineConfiguration() {
        var tile = tile();
        var state = tile.getData(ResourceHiveHost.TYPE);
        var config = tile.getConfig().getConfig(mekanism.common.lib.transmitter.TransmissionType.ENERGY);
        assertTrue(config.supports(mekanism.common.tile.component.config.DataType.OUTPUT));
        assertTrue(config.supports(mekanism.common.tile.component.config.DataType.INPUT_OUTPUT));
        var input = (mekanism.common.tile.component.config.slot.EnergySlotInfo)
                config.getSlotInfo(mekanism.common.tile.component.config.DataType.INPUT);
        var output = (mekanism.common.tile.component.config.slot.EnergySlotInfo)
                config.getSlotInfo(mekanism.common.tile.component.config.DataType.OUTPUT);
        assertSame(tile.energyContainer(), input.getContainers().getFirst());
        assertInstanceOf(ResourceMekEnergyContainer.class, output.getContainers().getFirst());
        var accountView = output.getContainers().getFirst();
        assertEquals(100, accountView.insert(100, mekanism.api.Action.EXECUTE, mekanism.api.AutomationType.EXTERNAL));
        assertEquals(0, accountView.getNeeded());
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        assertTrue(accountView.serializeNBT(provider).isEmpty());
        state.setNativeOutput(tile, ResourceNativeType.ENERGY, 1 << RelativeSide.RIGHT.ordinal(), true);
        assertEquals(mekanism.common.tile.component.config.DataType.OUTPUT, config.getDataType(RelativeSide.RIGHT));
        assertTrue(state.nativeEject(tile, ResourceNativeType.ENERGY));
        var saved = tile.saveWithoutMetadata(provider);
        var restored = tile();
        restored.loadCustomOnly(saved, provider);
        assertEquals(config.getDataType(RelativeSide.RIGHT), restored.getConfig()
                .getConfig(mekanism.common.lib.transmitter.TransmissionType.ENERGY).getDataType(RelativeSide.RIGHT));
    }
    @Test void oldEnergyFacesMigrateOnceWithoutOverwritingLaterNativeConfiguration() {
        var tile = tile();
        var state = tile.getData(ResourceHiveHost.TYPE);
        var legacy = new ResourceSideConfig();
        legacy.set(ResourceNativeType.ENERGY, 1 << RelativeSide.RIGHT.ordinal(), true);
        var data = legacy.save();
        data.putInt("version", 1);
        state.readNativeConfig(tile, data);
        var config = tile.getConfig().getConfig(mekanism.common.lib.transmitter.TransmissionType.ENERGY);
        assertEquals(mekanism.common.tile.component.config.DataType.OUTPUT, config.getDataType(RelativeSide.RIGHT));
        assertTrue(config.isEjecting());
        config.setDataType(mekanism.common.tile.component.config.DataType.NONE, RelativeSide.RIGHT);
        state.migrateNativeEnergy(tile);
        assertEquals(mekanism.common.tile.component.config.DataType.NONE, config.getDataType(RelativeSide.RIGHT));
        assertEquals(2, ((CompoundTag) state.nativeConfigData()).getInt("version"));
    }
    @Test void configurationPacketRoundTripIsBoundedAndRejectsInvalidMasks() {
        var request = new ResourceMenuSync.Request(1, BlockPos.ZERO, UUID.randomUUID(), 1, 0, 0, 0);
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), provider);
        try {
            var reply = new ResourceSideConfigSync.Reply(request, 1, new ResourceSideConfig().save(), true);
            ResourceSideConfigSync.Reply.CODEC.encode(buffer, reply);
            int full = buffer.readableBytes();
            assertEquals(reply, ResourceSideConfigSync.Reply.CODEC.decode(buffer));
            buffer.clear();
            ResourceSideConfigSync.Reply.CODEC.encode(buffer, new ResourceSideConfigSync.Reply(request, 1, null, true));
            assertTrue(buffer.readableBytes() < full);
            assertTrue(full < 300);
            assertThrows(IllegalArgumentException.class, () -> new ResourceSideConfigSync.Change(request, 0, 64, true));
            assertThrows(IllegalArgumentException.class, () -> new ResourceSideConfigSync.Change(request, 7, 0, true));
        } finally {
            buffer.release();
        }
    }
    @Test void strictEnergyExtractionCannotExceedTheRequestOrSpendOnSimulation() {
        var store = new ResourceAccounts(() -> {});
        var account = ResourceAccountsMinecraftTest.account(store);
        var amounts = java.util.Map.of(ResourceTypes.FE, Long.MAX_VALUE);
        var ticket = account.reserve(account.reference(), 1, new CompoundTag(), amounts);
        account.start(ticket);
        account.freeze(ticket, amounts);
        account.commit(ticket);
        var port = mock(ResourceOutputAccess.class);
        when(port.account()).thenReturn(account);
        when(port.reference()).thenReturn(account.reference());
        when(port.capacity()).thenReturn(Long.MAX_VALUE);
        when(port.outputAllowed(ResourceNativeType.ENERGY)).thenReturn(true);
        var output = new ResourceStrictEnergyOutput(port);
        var conversion = mekanism.api.energy.IEnergyConversionHelper.INSTANCE.feConversion();
        for (long amount : new long[]{1, 2, 3, 100, 9007199254740993L, Long.MAX_VALUE - 1025}) {
            var before = store.save();
            long simulated = output.extractEnergy(0, amount, mekanism.api.Action.SIMULATE);
            assertTrue(simulated >= 0 && simulated <= amount);
            assertEquals(before, store.save());
            long old = account.available().get(ResourceTypes.FE);
            long actual = output.extractEnergy(0, amount, mekanism.api.Action.EXECUTE);
            assertEquals(simulated, actual);
            long spent = old - account.available().getOrDefault(ResourceTypes.FE, 0L);
            assertEquals(conversion.convertFrom(spent), actual);
        }
        output.invalidate();
        assertEquals(0, output.extractEnergy(0, 100, mekanism.api.Action.EXECUTE));
    }
}
