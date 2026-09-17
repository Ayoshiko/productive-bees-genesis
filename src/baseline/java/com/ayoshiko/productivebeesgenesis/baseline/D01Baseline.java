package com.ayoshiko.productivebeesgenesis.baseline;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 显式 Gradle 属性启用的独立测试运行；默认源集和发行包中不存在此类。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class D01Baseline {
    private static D01Baseline active;
    private final MinecraftServer server;
    private final int warmup = Integer.getInteger("pbg.d01.warmupTicks", 1_200);
    private final int samples = Integer.getInteger("pbg.d01.sampleTicks", 2_400);
    private final long[] tickDurations;
    private final Path results;
    private D01Scene scene;
    private int elapsed;
    private int stage;
    private long tickStart;
    private JsonObject before;

    private D01Baseline(MinecraftServer server) {
        if (warmup < 20 || samples < 20) throw new IllegalArgumentException("Baseline windows are too short");
        this.server = server;
        results = server.getWorldPath(LevelResource.ROOT).normalize().resolveSibling("results");
        tickDurations = new long[samples];
        // 所有 FE 只由夹具在 Pre 事件补充，避免 AE 回填掩盖生产耗能。
        ModConfig.SERVER.apiaryAeEnergyInputEnabled.set(false);
        ModConfig.SERVER.mekCentrifugeAeEnergyInputEnabled.set(false);
        scene = new D01Scene(server.overworld(), false);
        LogUtils.getLogger().info("D01_BEGIN {} warmup={} samples={}", scene.name, warmup, samples);
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (Boolean.getBoolean("pbg.d01.enabled")) active = new D01Baseline(event.getServer());
    }

    @SubscribeEvent
    public static void beforeTick(ServerTickEvent.Pre event) {
        if (active == null) return;
        active.scene.refillEnergy();
        active.tickStart = System.nanoTime();
    }

    @SubscribeEvent
    public static void afterTick(ServerTickEvent.Post event) {
        if (active != null) active.tick();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { active = null; }

    private void tick() {
        elapsed++;
        if (elapsed == 20) scene.connect();
        int sampleIndex = elapsed - warmup - 21;
        if (sampleIndex >= 0 && sampleIndex < samples) {
            tickDurations[sampleIndex] = System.nanoTime() - tickStart;
            scene.measureEnergy();
        }
        if (elapsed == warmup + 20) {
            scene.validateReady();
            scene.resetCounters();
            before = scene.snapshot();
            command("spark profiler start --interval 4");
            LogUtils.getLogger().info("D01_SAMPLE_BEGIN {}", scene.name);
        }
        if (elapsed == warmup + samples + 20) {
            command("spark profiler stop --save-to-file");
            writeResult();
            LogUtils.getLogger().info("D01_SAMPLE_END {}", scene.name);
        }
        // 留出本地 Spark profile 写入时间，切场景不混进采样区间。
        if (elapsed == warmup + samples + 220) {
            scene.close();
            if (stage++ == 0) {
                scene = new D01Scene(server.overworld(), true);
                elapsed = 0;
                Arrays.fill(tickDurations, 0);
                LogUtils.getLogger().info("D01_BEGIN {}", scene.name);
            } else {
                active = null;
                LogUtils.getLogger().info("D01_COMPLETE {}", results);
                server.halt(false);
            }
        }
    }

    private void command(String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private void writeResult() {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("codeRevision", System.getProperty("pbg.d01.codeRevision"));
        result.addProperty("warmupTicks", warmup);
        result.addProperty("sampleTicks", samples);
        result.addProperty("sampleEndGameTime", server.overworld().getGameTime());
        result.addProperty("aeMachineEnergyInputEnabled", false);
        result.addProperty("timingMeaning", "ServerTickEvent Pre to Post, excluding fixture energy refill and snapshot; full MSPT is in Spark");
        long[] sorted = tickDurations.clone();
        Arrays.sort(sorted);
        result.addProperty("meanEventMs", Arrays.stream(sorted).average().orElse(0) / 1_000_000D);
        result.addProperty("p95EventMs", sorted[(int) Math.ceil(samples * 0.95) - 1] / 1_000_000D);
        result.addProperty("p99EventMs", sorted[(int) Math.ceil(samples * 0.99) - 1] / 1_000_000D);
        result.addProperty("maxEventMs", sorted[samples - 1] / 1_000_000D);
        JsonObject mods = new JsonObject();
        ModList.get().getMods().forEach(mod -> mods.addProperty(mod.getModId(), mod.getVersion().toString()));
        result.add("mods", mods);
        result.add("before", before);
        JsonObject after = scene.snapshot();
        result.add("after", after);
        boolean productive = scene.hasMeasuredProduction(before, after);
        result.addProperty("productionVerified", productive);
        try {
            Files.createDirectories(results);
            Files.writeString(results.resolve(scene.name + ".json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot persist D01 measurements", exception);
        }
        if (!productive) throw new IllegalStateException("D01 scene did not demonstrate production: " + scene.name);
    }
}
