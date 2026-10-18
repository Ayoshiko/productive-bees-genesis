package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.resource.PbResourceCatalog;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries;
import com.ayoshiko.productivebeesgenesis.util.ServerTickClock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeCreator;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ContainerFlowerMinecraftTest {
    private static final ResourceLocation BEE = ResourceLocation.parse("test:container_flower");
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static final String HYDROGEN = "{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"amount\":\"100\"}";

    static ItemStack item(String id) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))); }
    static ChemicalStack chemical(String id, long amount) {
        return new ChemicalStack(MekanismAPI.CHEMICAL_REGISTRY.getHolder(ResourceLocation.parse(id)).orElseThrow(), amount);
    }
    static JsonObject definition(String flower) {
        var json = new JsonObject(); json.addProperty("primaryColor", "#ffffff"); json.addProperty("secondaryColor", "#cccccc");
        json.add("flowerResource", JsonParser.parseString(flower)); return json;
    }

    @Test void realBeeCreatorPreservesContainerDefinitionAndConflictsNeverUseDefaultFlowers() {
        var json = definition(HYDROGEN);
        var data = BeeCreator.create(BEE, json);
        assertTrue(data.contains(ContainerFlower.NBT_KEY), "Real PB registration must include the Mixin");
        assertFalse(data.contains("flowerTag"));
        assertEquals("", ContainerFlower.fromData(data).problem());
        for (String key : List.of("flowerBlock", "flowerItem", "flowerTag", "flowerFluid", "postPollination")) {
            var bad = json.deepCopy(); bad.addProperty(key, "minecraft:water");
            assertEquals("invalid_flower", ContainerFlower.fromData(BeeCreator.create(BEE, bad)).problem());
        }
        for (String value : List.of("{}", "null", "{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"amount\":100}",
                "{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"amount\":\"0\"}",
                "{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"consume\":true}",
                "{\"kind\":\"energy\",\"energy_type\":\"EU\"}", "{\"kind\":\"unsupported\"}")) {
            assertEquals("invalid_flower", ContainerFlower.parse(value).problem(), value);
        }
    }

    @Test void actualFluidBucketsAndTankRequireContentsWithoutDraining() {
        var water = ContainerFlower.legacyFluid("minecraft:water");
        assertTrue(water.matches(new ItemStack(Items.WATER_BUCKET), REGISTRIES));
        assertFalse(water.matches(new ItemStack(Items.BUCKET), REGISTRIES));
        assertFalse(water.matches(new ItemStack(Items.LAVA_BUCKET), REGISTRIES));
        var tank = item("mekanism:basic_fluid_tank");
        var handler = tank.getCapability(Capabilities.FluidHandler.ITEM); assertNotNull(handler);
        assertFalse(water.matches(tank, REGISTRIES));
        assertEquals(250, handler.fill(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE));
        var original = tank.copy();
        assertTrue(water.matches(tank, REGISTRIES));
        assertTrue(ContainerFlower.parse("{\"kind\":\"fluid\",\"id\":\"minecraft:water\",\"amount\":\"250\"}").matches(tank, REGISTRIES));
        assertFalse(ContainerFlower.parse("{\"kind\":\"fluid\",\"id\":\"minecraft:water\",\"amount\":\"251\"}").matches(tank, REGISTRIES));
        assertTrue(ItemStack.matches(original, tank));
        assertEquals(250, handler.getFluidInTank(0).getAmount());
    }

    @Test void chemicalTankMatchesRealQuantityWithoutSpendingIt() {
        var flower = ContainerFlower.parse(HYDROGEN);
        var tank = item("mekanism:basic_chemical_tank");
        var handler = tank.getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()); assertNotNull(handler);
        assertFalse(flower.matches(tank, REGISTRIES));
        handler.setChemicalInTank(0, chemical("mekanism:oxygen", 1000)); assertFalse(flower.matches(tank, REGISTRIES));
        handler.setChemicalInTank(0, chemical("mekanism:hydrogen", 99)); assertFalse(flower.matches(tank, REGISTRIES));
        handler.setChemicalInTank(0, chemical("mekanism:hydrogen", 100));
        var before = tank.copy(); assertTrue(flower.matches(tank, REGISTRIES));
        assertTrue(ItemStack.matches(before, tank)); assertEquals(100, handler.getChemicalInTank(0).getAmount());
        // Mekanism 能量物品的能力初始化另由真实专服夹具验证；此 JVM 夹具不运行完整启动生命周期。
    }

    @Test void realMekanismGasBucketsMatchChemicalMinimumWithoutConsumingContents() {
        var hydrogen = item("mekanism:hydrogen_bucket");
        var oxygen = item("mekanism:oxygen_bucket");
        var flower = ContainerFlower.parse("{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"amount\":\"1000\"}");
        var before = hydrogen.copy();
        assertTrue(flower.matches(hydrogen, REGISTRIES));
        assertFalse(flower.matches(oxygen, REGISTRIES));
        assertFalse(flower.matches(new ItemStack(Items.BUCKET), REGISTRIES));
        assertFalse(ContainerFlower.parse("{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"amount\":\"1001\"}")
                .matches(hydrogen, REGISTRIES));
        assertTrue(ItemStack.matches(before, hydrogen));
    }

    @Test void actualFeederAndOuterBeeCacheObserveDisableInPlaceMutationAndReload() {
        var original = new HashMap<>(BeeReloadListener.INSTANCE.getData());
        try {
            var data = new HashMap<>(original); data.put(BEE, BeeCreator.create(BEE, definition(HYDROGEN)));
            BeeReloadListener.INSTANCE.setData(data); BeeInfoHelper.invalidateCache();
            var feeder = new FeederSlotManager(); feeder.buildFeederSlots(null); feeder.setRegistryLookup(() -> REGISTRIES);
            var tank = item("mekanism:basic_chemical_tank");
            tank.getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()).setChemicalInTank(0, chemical("mekanism:hydrogen", 50));
            feeder.getFeederSlot(0).setStack(tank.copy()); feeder.getFeederSlot(1).setStack(tank.copy());
            assertFalse(feeder.hasValidFlower(BEE), "Different feeder slots must not pool their contents");
            feeder.getFeederSlot(1).setStack(ItemStack.EMPTY);
            tank.getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()).setChemicalInTank(0, chemical("mekanism:hydrogen", 100));
            feeder.getFeederSlot(0).setStack(tank);
            ServerTickClock.reset(); ServerTickClock.tick();
            assertTrue(feeder.hasValidFlower(BEE));
            feeder.toggleSlotDisabled(0); assertFalse(feeder.hasValidFlower(BEE));
            feeder.toggleSlotDisabled(0); assertTrue(feeder.hasValidFlower(BEE));
            var cachedBee = mock(BeeSlot.class); when(cachedBee.consumeCachedFlowerValid(anyInt())).thenReturn(true);
            feeder.getFeederSlot(0).getStack().getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()).setChemicalInTank(0, ChemicalStack.EMPTY);
            ServerTickClock.tick();
            assertFalse(ApiaryFlowerValidation.check(cachedBee, BEE, feeder.getFlowerCacheVersion(), feeder));
            feeder.getFeederSlot(0).getStack().getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()).setChemicalInTank(0, chemical("mekanism:hydrogen", 100));
            ServerTickClock.tick(); assertTrue(feeder.hasValidFlower(BEE));
            data = new HashMap<>(data); data.put(BEE, BeeCreator.create(BEE, definition(HYDROGEN.replace("100", "101"))));
            BeeReloadListener.INSTANCE.setData(data); BeeInfoHelper.invalidateCache(); BeeConversionQueries.invalidate();
            assertFalse(feeder.hasValidFlower(BEE));
            assertEquals(100, feeder.getFeederSlot(0).getStack().getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()).getChemicalInTank(0).getAmount());
        } finally { BeeReloadListener.INSTANCE.setData(original); BeeInfoHelper.invalidateCache(); BeeConversionQueries.invalidate(); ServerTickClock.reset(); }
    }

    @Test void containerAdmissionIsLimitedToMechanicalHosts() {
        var spec = new PbResourceCatalog.Spec(List.of(), "", List.of(), List.of(), true);
        assertTrue(spec.validForMechanical()); assertFalse(spec.validForPb()); assertFalse(spec.validForJdte());
    }

    @Test void resourceTagsObserveMembershipAndEmptyReload() {
        var fluids = BuiltInRegistries.FLUID;
        var chemicals = MekanismAPI.CHEMICAL_REGISTRY;
        var originalFluids = fluids.getTags().collect(java.util.stream.Collectors.toMap(
                entry -> entry.getFirst(), entry -> entry.getSecond().stream().toList()));
        var originalChemicals = chemicals.getTags().collect(java.util.stream.Collectors.toMap(
                entry -> entry.getFirst(), entry -> entry.getSecond().stream().toList()));
        var id = ResourceLocation.parse("test:container_flowers");
        var fluidTag = net.minecraft.tags.TagKey.create(fluids.key(), id);
        var chemicalTag = net.minecraft.tags.TagKey.create(chemicals.key(), id);
        try {
            var fluidTags = new HashMap<>(originalFluids);
            var chemicalTags = new HashMap<>(originalChemicals);
            fluidTags.put(fluidTag, List.of(fluids.wrapAsHolder(Fluids.WATER)));
            chemicalTags.put(chemicalTag, List.of(chemical("mekanism:hydrogen", 100).getChemicalHolder()));
            fluids.bindTags(fluidTags); chemicals.bindTags(chemicalTags);
            var water = ContainerFlower.legacyFluid("#" + id);
            var hydrogen = ContainerFlower.parse(HYDROGEN.replace("mekanism:hydrogen", "#" + id));
            var tank = item("mekanism:basic_chemical_tank");
            tank.getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()).setChemicalInTank(0, chemical("mekanism:hydrogen", 100));
            assertEquals("", water.problem()); assertEquals("", hydrogen.problem());
            assertTrue(water.matches(new ItemStack(Items.WATER_BUCKET), REGISTRIES));
            assertFalse(water.matches(new ItemStack(Items.LAVA_BUCKET), REGISTRIES));
            assertTrue(hydrogen.matches(tank, REGISTRIES));
            fluidTags.put(fluidTag, List.of()); chemicalTags.put(chemicalTag, List.of());
            fluids.bindTags(fluidTags); chemicals.bindTags(chemicalTags);
            assertEquals("invalid_flower", water.problem()); assertEquals("invalid_flower", hydrogen.problem());
            assertFalse(water.matches(new ItemStack(Items.WATER_BUCKET), REGISTRIES));
            assertFalse(hydrogen.matches(tank, REGISTRIES));
        } finally { fluids.bindTags(originalFluids); chemicals.bindTags(originalChemicals); }
    }

    @Test void internalTanksAreNotPooledAndOversizedHandlersAreRejected() {
        var original = mock(ItemStack.class); var snapshot = mock(ItemStack.class);
        when(original.copyWithCount(1)).thenReturn(snapshot);
        var handler = mock(mekanism.api.chemical.IChemicalHandler.class);
        when(snapshot.getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item())).thenReturn(handler);
        when(handler.getChemicalTanks()).thenReturn(2);
        when(handler.getChemicalInTank(anyInt())).thenReturn(chemical("mekanism:hydrogen", 50));
        assertFalse(ContainerFlower.parse(HYDROGEN).matches(original, REGISTRIES));
        when(handler.getChemicalInTank(1)).thenReturn(chemical("mekanism:hydrogen", 100));
        assertTrue(ContainerFlower.parse(HYDROGEN).matches(original, REGISTRIES));
        when(handler.getChemicalTanks()).thenReturn(65);
        clearInvocations(handler);
        assertFalse(ContainerFlower.parse(HYDROGEN).matches(original, REGISTRIES));
        verify(handler, never()).getChemicalInTank(anyInt());
    }

    @Test void insufficientContainersAreNotPooledAndQueryFailureDoesNotMatch() {
        var flower = ContainerFlower.parse(HYDROGEN);
        var tank = item("mekanism:basic_chemical_tank");
        tank.getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item()).setChemicalInTank(0, chemical("mekanism:hydrogen", 50));
        tank.setCount(64); assertFalse(flower.matches(tank, REGISTRIES), "Stack size must not multiply container content");
        var broken = mock(ItemStack.class); var copy = mock(ItemStack.class);
        when(broken.copyWithCount(1)).thenReturn(copy);
        when(copy.getCapability(Capabilities.FluidHandler.ITEM)).thenThrow(new IllegalStateException("Broken container"));
        assertFalse(ContainerFlower.legacyFluid("minecraft:water").matches(broken, REGISTRIES));
        var energy = ContainerFlower.parse("{\"kind\":\"energy\",\"energy_type\":\"FE\",\"amount\":\"1000\"}");
        var storage = mock(net.neoforged.neoforge.energy.IEnergyStorage.class);
        when(copy.getCapability(Capabilities.EnergyStorage.ITEM)).thenReturn(storage);
        when(storage.getEnergyStored()).thenReturn(999, 1000);
        assertFalse(energy.matches(broken, REGISTRIES)); assertTrue(energy.matches(broken, REGISTRIES));
        verify(storage, never()).extractEnergy(anyInt(), anyBoolean());
        verify(storage, never()).receiveEnergy(anyInt(), anyBoolean());
        var strict = mock(mekanism.api.energy.IStrictEnergyHandler.class);
        when(copy.getCapability(mekanism.common.capabilities.Capabilities.STRICT_ENERGY.item())).thenReturn(strict);
        when(strict.getEnergyContainerCount()).thenReturn(1);
        var conversion = mekanism.api.energy.IEnergyConversionHelper.INSTANCE.feConversion();
        when(strict.getEnergy(0)).thenReturn(conversion.convertFrom(999), conversion.convertFrom(1000));
        assertFalse(energy.matches(broken, REGISTRIES)); assertTrue(energy.matches(broken, REGISTRIES));
        verify(strict, never()).extractEnergy(anyInt(), anyLong(), any());
        verify(strict, never()).insertEnergy(anyInt(), anyLong(), any());
    }
}
