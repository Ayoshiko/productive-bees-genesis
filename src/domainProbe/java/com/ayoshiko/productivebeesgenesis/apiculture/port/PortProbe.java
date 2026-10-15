package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.domainprobe.PlayerInventorySyncProbe;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.*;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandler;

/** 复用正式 ticker、原生菜单和能力；模拟调用逐次核对权威根未变化。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class PortProbe {
    public static final BlockPos POS = new BlockPos(8, 100, 8), SUPPLY = POS.north(), INPUT = POS.west(), OUTPUT = POS.east();
    public static volatile boolean clientReady, clientVerified, normalShutdown;
    public static volatile int clientStage;
    public static volatile String failure;
    private static final JsonObject report = new JsonObject();
    private static final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private static NetworkCoreBlockEntity core;
    private static NetworkPortBlockEntity supply, input, output;
    private static ServerPlayer player;
    private static NetworkSavedData data;
    private static UUID member;
    private static CompoundTag marker;
    private static NetworkCheckpoint baseline;
    private static IItemHandler stale;
    private static int phase, started, waitUntil, oldCycle;
    private static long sequence;
    private static boolean initialized;
    static boolean enabled() { return System.getProperty("pbg.port.mode") != null; }
    static boolean client() { return "client".equals(System.getProperty("pbg.port.mode")); }
    static boolean reading() { return "read".equals(System.getProperty("pbg.port.mode")); }
    private static Path markerFile(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("port-probe.dat"); }
    static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
        if (!enabled() || phase == 99) return;
        var server = event.getServer(); var level = server.overworld();
        try {
            if (!initialized) {
                if (client() && server.getPlayerList().getPlayers().isEmpty()) return;
                oldCycle = ModConfig.SERVER.apiaryProcessingTime.get(); ModConfig.SERVER.apiaryProcessingTime.set(40);
                ModConfig.SERVER.beeNetwork.enabled.set(true);
                level.setChunkForced(0, 0, true); level.setDayTime(6000); level.setWeatherParameters(100000, 0, false, false);
                marker = reading() ? NbtIo.readCompressed(markerFile(server), NbtAccounter.unlimitedHeap()) : new CompoundTag();
                UUID owner = reading() ? marker.getUUID("owner") : client() ? server.getPlayerList().getPlayers().getFirst().getUUID() : UUID.randomUUID();
                player = client() ? server.getPlayerList().getPlayers().getFirst()
                        : new ServerPlayer(server, level, new GameProfile(owner, "PortOwner"), ClientInformation.createDefault());
                if (!client()) new PlayerInventorySyncProbe(player);
                player.teleportTo(POS.getX() + 0.5, POS.getY() + 2, POS.getZ() + 0.5);
                if (reading()) {
                    check(marker.getLong("writer") != ProcessHandle.current().pid(), "Reader reused writer JVM");
                    core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
                    supply = (NetworkPortBlockEntity) level.getBlockEntity(SUPPLY); input = (NetworkPortBlockEntity) level.getBlockEntity(INPUT); output = (NetworkPortBlockEntity) level.getBlockEntity(OUTPUT);
                    check(core != null && supply != null && input != null && output != null, "Missing persisted ports");
                    phase = 20;
                } else {
                    level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(owner);
                    level.setBlockAndUpdate(POS.south(), ModBlocks.MEK_APIARY.get().defaultBlockState());
                    var hive = (TileEntityMekApiary) level.getBlockEntity(POS.south()); hive.setOwnerUUID(owner); hive.setFeederConversionEnabled(false);
                    level.setBlockAndUpdate(POS.south(2), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
                    ((TileEntityMekCentrifuge) level.getBlockEntity(POS.south(2))).setOwnerUUID(owner);
                    supply = place(level, SUPPLY, NetworkContent.SUPPLY_PORT.get()); input = place(level, INPUT, NetworkContent.INPUT_PORT.get()); output = place(level, OUTPUT, NetworkContent.OUTPUT_PORT.get());
                    marker.putUUID("owner", owner); marker.putLong("writer", ProcessHandle.current().pid()); phase = 1;
                }
                started = server.getTickCount(); initialized = true; return;
            }
            check(server.getTickCount() - started < 4000, "Port timeout phase=" + phase + " result=" + supply.result());
            if ((server.getTickCount() - started) % 200 == 0) com.mojang.logging.LogUtils.getLogger().info(
                    "PORT_PROBE_PROGRESS phase={} result={} core={} running={} runtime={} energy={}", phase, supply.result(),
                    core.ownership().status(), core.productionRunning(), core.runtime().status(), data == null ? -1 : data.checkpoint().energy().stored());
            check(level.getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(12)).isEmpty(), "Port emitted owned items");
            check(level.getEntitiesOfClass(Bee.class, new AABB(POS).inflate(12)).isEmpty(), "Port spawned a world bee");
            if (!actions.isEmpty()) {
                if (server.getTickCount() % 6 == 0 && available()) actions.removeFirst().run();
                return;
            }
            if (phase == 1) {
                if (core.topology() == null || !core.topology().valid() || core.ownership().busy()) return;
                check(core.topology().members().size() == 2, "Ports contributed machine capacity");
                if (core.network() == null) { check(core.ownership().command(true), "Join refused"); return; }
                check(core.ownership().failure().isEmpty(), "Join failed: " + core.ownership().failure());
                if (core.ownership().status() != CoreOwnershipController.Status.MANAGED) return;
                data = core.ownership().readyAuthority(); if (data == null) return;
                for (var record : data.checkpoint().ownedMachines().activeValues()) if (record.claim().machine().endsWith(":mek_apiary")) member = record.claim().member();
                check(member != null, "Missing owned apiary");
                check(!core.productionRunning(), "Fixture must install while paused");
                actions.add(() -> { open(supply); command(2, 0, -1, 1); });
                actions.add(() -> command(0, 0, -1, 1));
                actions.add(() -> deposit(0, cage(), 0));
                actions.add(() -> deposit(1, egg(), 1));
                actions.add(() -> deposit(2, new ItemStack(Items.IRON_BLOCK), 2));
                actions.add(() -> deposit(3, new ItemStack(Items.GOLD_BLOCK), 3));
                phase = 2; return;
            }
            if (phase == 2) {
                var bees = data.checkpoint().ownedMachines().get(member).bees();
                if (bees == null || bees.bees().size() != 2 || bees.feeding().slots().get(0).item() == null || bees.feeding().slots().get(1).item() == null) return;
                check(bees.bees().stream().allMatch(b -> b.progress() == 0), "Paused supply advanced production");
                var held = supply.state().supplies();
                check(held.get(0).count() == 1 && VerifiedCageProjection.afterRelease(cage()).getItem() == PortCapabilities.item(held.get(0), level).getItem(), "Sturdy cage was lost");
                check(held.stream().mapToInt(PortState.Stack::count).sum() == 1, "Supply duplicated or lost an input");
                check(PortCapabilities.item(held.get(0), level).get(DataComponents.CUSTOM_DATA) == null, "Empty cage retained a second bee");
                report.addProperty("pausedBeeAndFlowerSupplyConserved", true); report.addProperty("emptyCageReturnedOnce", true);
                if (client()) {
                    open(input);
                    player.getInventory().items.set(1, comb("iron")); player.getInventory().items.set(2, comb("gold"));
                    player.getInventory().items.set(3, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("productivebees:honey_bucket"))));
                    player.getInventory().setChanged(); player.containerMenu.broadcastChanges(); clientReady = true; phase = 50; return;
                }
                actions.add(() -> { open(input); command(0, 0, -1, 1); });
                actions.add(() -> { open(output); player.getInventory().items.set(4, new ItemStack(Items.RAW_IRON)); command(3, 0, 4, 1); });
                actions.add(() -> { player.getInventory().items.set(4, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("productivebees:honey_bucket")))); command(3, 9, 4, 1); });
                actions.add(() -> command(0, 0, -1, 1));
                phase = 3; return;
            }
            if (phase == 3) {
                if (!available() || RuntimeProductPolicies.peek(level) == null) return;
                capabilityChecks(level);
                var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, POS, Direction.UP);
                check(energy != null && energy.receiveEnergy(500000, false) == 500000, "Core FE unavailable");
                check(core.setProductionRunning(true), "Production start failed"); phase = 4; return;
            }
            if (phase == 4) {
                var stock = data.checkpoint().ledger().balances();
                var iron = ProductKeyCodec.item(new ItemStack(Items.RAW_IRON), level.registryAccess());
                var honey = ProductKeyCodec.fluid(honey(), level.registryAccess());
                if (stock.getOrDefault(iron, com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.ZERO).longSaturated() < 1
                        || stock.getOrDefault(honey, com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.ZERO).longSaturated() < 250) return;
                var handler = items(level, OUTPUT); var before = data.checkpoint(); var taken = handler.extractItem(0, 64, false);
                check(!taken.isEmpty() && taken.is(Items.RAW_IRON), "Produced iron not exposed");
                check(before.ledger().available(iron).subtract(com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.of(taken.getCount())).equals(data.checkpoint().ledger().available(iron)), "Output did not debit exact production");
                var fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, OUTPUT, Direction.UP); before = data.checkpoint();
                var drained = fluids.drain(1000, FluidAction.EXECUTE); check(!drained.isEmpty() && drained.getAmount() <= 1000, "Produced honey not exposed");
                check(before.ledger().available(honey).subtract(com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.of(drained.getAmount())).equals(data.checkpoint().ledger().available(honey)), "Fluid output changed another amount");
                report.addProperty("formalBeeCentrifugeAndPipeOutput", true);
                check(core.setProductionRunning(false), "Pause failed");
                actions.add(() -> { open(supply); command(0, 0, -1, 1); });
                phase = 5; return;
            }
            if (phase == 5) {
                baseline = data.checkpoint(); stale = items(level, INPUT); check(stale.isItemValid(0, new ItemStack(Items.RAW_IRON)), "Input was unavailable");
                core.toggleFace(Direction.UP); phase = 6; waitUntil = server.getTickCount() + 35; return;
            }
            if (phase == 6) {
                if (!available() || server.getTickCount() < waitUntil) return;
                check(stale.insertItem(0, new ItemStack(Items.RAW_IRON), false).getCount() == 1, "Old capability revived after topology rebuild");
                check(data.checkpoint() == baseline, "Stale capability changed assets");
                var removed = supply; var state = level.getBlockState(SUPPLY); level.removeBlockEntity(SUPPLY);
                supply = new NetworkPortBlockEntity(SUPPLY, state); supply.initializeOwner(player.getUUID()); level.setBlockEntity(supply);
                core.requestRebuild();
                check(!removed.allowed(player) && !supply.open(player), "Unready replacement accessed retained materials");
                phase = 7; return;
            }
            if (phase == 7) {
                if (!available()) return;
                open(supply); check(supply.state().supplies().get(0).count() == 1, "Replacement lost or duplicated retained cage");
                check(!supply.state().enabled(), "Replacing a port enabled automation");
                var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "PortGuest")); stranger.setPos(player.position()); new PlayerInventorySyncProbe(stranger);
                check(!supply.open(stranger), "Another owner opened supply");
                report.addProperty("staleReferencesAndOwnerBoundary", true); report.addProperty("replacementRecoversSameAccount", true);
                waitUntil = server.getTickCount() + 30; phase = 8; return;
            }
            if (phase == 8) {
                if (!available() || server.getTickCount() < waitUntil) return;
                marker.put("ports", NetworkCheckpointCodec.encode(data.checkpoint()).get("ports").copy());
                marker.putUUID("network", core.network().networkId()); NbtIo.writeCompressed(marker, markerFile(server));
                finish(server); return;
            }
            if (phase == 20) {
                if (!available()) return;
                data = core.ownership().readyAuthority();
                check(NetworkCheckpointCodec.encode(data.checkpoint()).get("ports").equals(marker.get("ports")), "Port configuration or finite items changed across JVM");
                open(supply); check(!supply.state().enabled(), "Disabled supply resumed after restart");
                player.getInventory().items.set(0, ItemStack.EMPTY);
                actions.add(() -> {
                    var request = command(5, 0, 0, 64);
                    check(player.getInventory().items.get(0).getCount() == 1 && supply.state().supplies().get(0).empty(), "Manual recovery did not return one cage");
                    var before = data.checkpoint(); ((NetworkPortMenu) player.containerMenu).request(player, request);
                    check(data.checkpoint() == before && player.getInventory().items.get(0).getCount() == 1, "Replay duplicated recovered cage");
                });
                phase = 21; return;
            }
            if (phase == 21) {
                if (!available() || RuntimeProductPolicies.peek(level) == null) return;
                capabilityChecks(level); report.addProperty("crossJvmPortAndManualRecovery", true); finish(server); return;
            }
            if (phase == 50 && clientStage == 1) {
                check(player.getInventory().items.get(0).getCount() == 1 && supply.state().supplies().get(0).empty(), "Client cage recovery failed");
                report.addProperty("clientNativeMenuRecovery", true); clientStage = 11;
            }
            if (phase == 50 && clientStage == 2) {
                var state = output.state();
                check(ProductKeyCodec.item(comb("iron"), level.registryAccess()).equals(state.filters().get(0))
                        && ProductKeyCodec.item(comb("gold"), level.registryAccess()).equals(state.filters().get(1)), "Client conflated bee component filters");
                check(state.filters().get(9) != null && state.enabled(), "Client fluid filter or toggle failed");
                check(player.getInventory().items.get(1).getCount() == 1 && player.getInventory().items.get(2).getCount() == 1, "Ghost filter consumed a sample");
                report.addProperty("clientExactSamplesAndToggle", true);
                var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, POS, Direction.UP);
                check(energy != null && energy.receiveEnergy(500000, false) == 500000 && core.setProductionRunning(true),
                        "Client configured network could not start production");
                phase = 51; return;
            }
            if (phase == 51) {
                if (!available()) return;
                var products = items(level, OUTPUT);
                if (products.getStackInSlot(0).isEmpty() || products.getStackInSlot(1).isEmpty()) return;
                var before = data.checkpoint();
                var iron = ProductKeyCodec.item(comb("iron"), level.registryAccess());
                var gold = ProductKeyCodec.item(comb("gold"), level.registryAccess());
                var ironTaken = products.extractItem(0, 64, false); var goldTaken = products.extractItem(1, 64, false);
                check(!ironTaken.isEmpty() && !goldTaken.isEmpty()
                        && before.ledger().available(iron).subtract(com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.of(ironTaken.getCount())).equals(data.checkpoint().ledger().available(iron))
                        && before.ledger().available(gold).subtract(com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.of(goldTaken.getCount())).equals(data.checkpoint().ledger().available(gold)),
                        "Client configured component filters did not extract actual production");
                var fluid = level.getCapability(Capabilities.FluidHandler.BLOCK, OUTPUT, Direction.UP);
                check(fluid != null && !fluid.drain(1000, FluidAction.EXECUTE).isEmpty(), "Client configured honey output missing");
                check(core.setProductionRunning(false), "Client production pause failed");
                report.addProperty("clientConfiguredProductionAndOutput", true); clientVerified = true; clientStage = 12; phase = 52;
            }
        } catch (Exception error) { fail(server, error); }
    }
    private static boolean available() { return core != null && core.topology() != null && core.topology().valid() && core.ownership().readyAuthority() != null; }
    private static NetworkPortBlockEntity place(ServerLevel level, BlockPos pos, NetworkPortBlock block) {
        level.setBlockAndUpdate(pos, block.defaultBlockState()); var port = (NetworkPortBlockEntity) level.getBlockEntity(pos); port.initializeOwner(player.getUUID()); return port;
    }
    private static void open(NetworkPortBlockEntity port) { check(port.open(player), "Port menu did not open"); sequence = 0; }
    private static PortMenuRequest command(int action, int slot, int source, int amount) {
        var menu = (NetworkPortMenu) player.containerMenu;
        var request = new PortMenuRequest(menu.containerId, menu.session(), ++sequence, menu.configuration(), action, slot, source, amount);
        menu.request(player, request);
        check(menu.result() == (action >= 4 ? 5 : 0) || !menu.enabled() && menu.result() == 2, "Port command failed: " + action + "/" + menu.result());
        return request;
    }
    private static void deposit(int slot, ItemStack stack, int source) {
        player.getInventory().items.set(source, stack); command(4, slot, source, stack.getCount());
        check(player.getInventory().items.get(source).isEmpty(), "Menu did not debit supply source");
    }
    public static ItemStack cage() {
        var tag = new CompoundTag(); tag.putString("entity", "productivebees:configurable_bee"); tag.putString("type", "productivebees:iron"); tag.putString("marker", "port-bee");
        var stack = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); return stack;
    }
    public static ItemStack egg() {
        for (var holder : cy.jdkdigital.productivebees.init.ModItems.SPAWN_EGGS) {
            var stack = new ItemStack(holder.get()); var tag = new CompoundTag(); tag.putString("id", "productivebees:configurable_bee"); tag.putString("type", "productivebees:gold");
            stack.set(DataComponents.ENTITY_DATA, CustomData.of(tag));
            var resolved = com.ayoshiko.productivebeesgenesis.apiary.BeeSpawnEggHelper.resolve(stack);
            if (resolved != null && resolved.configurableBeeType() != null) return stack;
        }
        throw new IllegalStateException("Configurable spawn egg missing");
    }
    public static ItemStack comb(String bee) {
        var stack = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_HONEYCOMB.get());
        stack.set(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:" + bee)); return stack;
    }
    private static FluidStack honey() { return new FluidStack(BuiltInRegistries.FLUID.get(ResourceLocation.parse("productivebees:honey")), 1000); }
    private static IItemHandler items(ServerLevel level, BlockPos pos) { return Objects.requireNonNull(level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP)); }
    private static void capabilityChecks(ServerLevel level) {
        var in = items(level, INPUT); var out = items(level, OUTPUT); var before = data.checkpoint();
        var offered = new ItemStack(Items.RAW_IRON, 80);
        var simulatedInput = in.insertItem(0, offered, true);
        check(simulatedInput.getCount() == 16 && offered.getCount() == 80 && data.checkpoint() == before,
                "Input simulation mismatch: remaining=" + simulatedInput.getCount() + " source=" + offered.getCount()
                + " rootChanged=" + (data.checkpoint() != before) + " state=" + input.state()
                + " valid=" + in.isItemValid(0, offered));
        check(in.insertItem(0, offered, false).getCount() == 16 && offered.getCount() == 80, "Input cap or source changed");
        var iron = ProductKeyCodec.item(new ItemStack(Items.RAW_IRON), level.registryAccess());
        check(data.checkpoint().ledger().available(iron).equals(before.ledger().available(iron).add(com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.of(64))), "Input credit not exact");
        before = data.checkpoint(); check(in.insertItem(0, new ItemStack(Items.BEDROCK), false).getCount() == 1 && data.checkpoint() == before, "Illegal product accepted");
        var simulated = out.extractItem(0, 80, true); check(simulated.getCount() == 64 && data.checkpoint() == before, "Output simulation mutated assets");
        check(out.extractItem(0, 80, false).getCount() == 64 && in.extractItem(0, 1, false).isEmpty(), "Item roles or limits failed");
        check(out.insertItem(0, offered, false) == offered, "Output accepted input");
        var fluidsIn = level.getCapability(Capabilities.FluidHandler.BLOCK, INPUT, Direction.UP);
        var fluidsOut = level.getCapability(Capabilities.FluidHandler.BLOCK, OUTPUT, Direction.UP); before = data.checkpoint();
        check(fluidsIn.fill(honey().copyWithAmount(2000), FluidAction.SIMULATE) == 1000 && data.checkpoint() == before, "Fluid simulation changed root");
        check(fluidsIn.fill(honey().copyWithAmount(2000), FluidAction.EXECUTE) == 1000, "Fluid input cap failed");
        before = data.checkpoint(); check(fluidsOut.drain(2000, FluidAction.SIMULATE).getAmount() == 1000 && data.checkpoint() == before, "Fluid output simulation changed root");
        check(fluidsOut.drain(2000, FluidAction.EXECUTE).getAmount() == 1000, "Fluid output cap failed");
        check(level.getCapability(Capabilities.FluidHandler.BLOCK, SUPPLY, Direction.UP) == null, "Supply exposed fluid assets");
        if (supply.state().enabled()) {
            var supplies = items(level, SUPPLY); before = data.checkpoint();
            var offeredSupply = new ItemStack(Items.COBBLESTONE, 80);
            check(supplies.insertItem(8, offeredSupply, true).getCount() == 16 && data.checkpoint() == before,
                    "Finite supply simulation changed assets");
            check(supplies.insertItem(8, offeredSupply, false).getCount() == 16 && offeredSupply.getCount() == 80,
                    "Finite supply accepted more than capacity");
            before = data.checkpoint();
            check(supplies.insertItem(8, new ItemStack(Items.COBBLESTONE), false).getCount() == 1
                    && supplies.insertItem(8, new ItemStack(Items.RAW_IRON), false).getCount() == 1
                    && data.checkpoint() == before, "Full or mismatched supply lost rejected items");
            var projection = supplies.getStackInSlot(8); projection.setCount(1);
            check(supplies.extractItem(8, 80, true).getCount() == 64 && data.checkpoint() == before,
                    "Supply projection or simulated extraction changed assets");
            check(supplies.extractItem(8, 80, false).getCount() == 64 && supply.state().supplies().get(8).empty(),
                    "Supply recovery did not return exactly the retained stack");
            report.addProperty("finiteSupplyCapacityRefusalAndRecovery", true);
        }
        report.addProperty("nativeCapabilitiesActualAmountsSimulationAndRoles", true);
    }
    private static void finish(MinecraftServer server) {
        report.addProperty("passed", true); report.addProperty("noWorldDropsOrBeeSpawns", true); phase = 99; write();
        if (!client()) server.halt(false);
    }
    private static void fail(MinecraftServer server, Exception error) {
        failure = error.toString(); report.addProperty("passed", false); report.addProperty("failure", failure);
        com.mojang.logging.LogUtils.getLogger().error("PORT_PROBE_FAILED phase={}", phase, error); phase = 99; write(); if (!client()) server.halt(false);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        if (!enabled() || !initialized) return;
        try {
            var path = event.getServer().getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + core.network().networkId() + ".dat");
            var saved = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data");
            var restored = NetworkCheckpointCodec.forRegistries(event.getServer().registryAccess()).decode(saved);
            check(restored.ports().size() == 3, "Normal shutdown lost port records");
            report.addProperty("normalShutdown", true); normalShutdown = true;
            if (client()) report.addProperty("passed", clientVerified && failure == null);
        } catch (Exception error) { report.addProperty("passed", false); report.addProperty("shutdownFailure", error.toString()); }
        finally { ModConfig.SERVER.apiaryProcessingTime.set(oldCycle); write(); }
    }
    private static void write() {
        report.addProperty("mode", System.getProperty("pbg.port.mode")); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
        try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/ports.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
