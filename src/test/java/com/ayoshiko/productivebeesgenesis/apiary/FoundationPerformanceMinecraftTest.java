package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.resource.*;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.*;
import java.util.*;

@Tag("minecraft")
class FoundationPerformanceMinecraftTest {
    private static volatile long checksum;
    @Test void measureSharedProductionAndFullBuffer() throws Exception {
        String phase = System.getenv("PBG_FOUNDATION_PHASE");
        Assumptions.assumeTrue(phase != null && phase.matches("[a-z]+"));
        net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
        try {
            var report = new JsonObject();
            for (int cycles : new int[]{1, 256, 16384}) for (double chance : new double[]{1, 0.5}) {
                var outputs = List.of(new ResourceProductionRecipe.Output(ResourceKey.energy(), 1, chance),
                        new ResourceProductionRecipe.Output(ResourceKey.chemical(ResourceLocation.parse("mekanism:hydrogen")), 1, chance),
                        new ResourceProductionRecipe.Output(ResourceKey.chemical(ResourceLocation.parse("mekanism:oxygen")), 1, chance),
                        new ResourceProductionRecipe.Output(ResourceKey.chemical(ResourceLocation.parse("mekanism:chlorine")), 1, chance));
                var plan = new ResourceRollPlan(outputs, 16.5, 3, 31);
                measure(report, "rolls_" + cycles + "_" + chance, () -> checksum += plan.sampleWithRolls(cycles).rolls(), 30);
            }
            var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary")).defaultBlockState();
            var tile = new TileEntityMekApiary(Holder.direct(state.getBlock()), BlockPos.ZERO, state);
            for (int i = 0; i < 512; i++) {
                var stack = new ItemStack(Items.IRON_INGOT);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("group" + i));
                tile.getOutputBuffer().accept(stack);
            }
            measure(report, "buffer_512_rejected", () -> checksum += tile.getOutputBuffer().pushToSink(stack -> 0, null).pushed(), 1000);
            measure(report, "buffer_512_hold", () -> checksum += tile.getOutputBuffer().pushToSink(stack -> 0, stack -> true).heldCount(), 1000);
            var accounts = new ResourceAccounts(() -> {});
            for (int i = 0; i < 512; i++) accounts.create(UUID.randomUUID(), new ResourceAccount.Binding(ResourceLocation.parse("minecraft:overworld"), i, ResourceLocation.parse("example:host")));
            measure(report, "accounts_512_save", () -> checksum += accounts.save().sizeInBytes(), 100);
            report.addProperty("checksum", checksum);
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("PBG_FOUNDATION_OUTPUT"), "performance-" + phase + ".json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report), java.nio.charset.StandardCharsets.UTF_8);
        } finally { net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER); }
    }

    private static void measure(JsonObject report, String name, Runnable operation, int samples) {
        for (int i = 0; i < Math.min(100, samples); i++) operation.run();
        long[] times = new long[samples];
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId(), allocated = bean.getThreadAllocatedBytes(id);
        for (int i = 0; i < samples; i++) { long begin = System.nanoTime(); operation.run(); times[i] = System.nanoTime() - begin; }
        long bytes = bean.getThreadAllocatedBytes(id) - allocated;
        Arrays.sort(times);
        var result = new JsonObject();
        result.addProperty("median_ns", times[samples / 2]);
        result.addProperty("p95_ns", times[Math.min(samples - 1, (int) (samples * .95))]);
        result.addProperty("bytes_per_call", bytes / samples);
        report.add(name, result);
    }
}
