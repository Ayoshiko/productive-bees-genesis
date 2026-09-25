package com.ayoshiko.productivebeesgenesis.dispatchprobe;

import com.ayoshiko.productivebeesgenesis.mek.ae2.CentrifugeDispatchProbe;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * 独立源集中的专服发配自检：真实 tick、外部存储与供应器 Mixin，加上可配置的每次推送模拟成本。
 * 洪水晚于监视器 Pre 执行，耗时计入 MSPT，驱动共享预算的滞回与退避冷却。
 * 验证同刻拒收、模拟容量、跨刻恢复及稳定段耗时；不代表完整 CPU 或玩家存档的端到端性能。
 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class DispatchBenchmarkServer {

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int WARMUP_TICK = 80;
	private static final int MEASURE_UNTIL_TICK = 400;
	private static final int FLOOD_CAP = 4_000_000;
	/** 前半程留给 100 刻滚动均值和预算爬升，稳定段固定取 141 个连续样本。 */
	private static final int STABLE_FROM_TICK = 260;
	private static final double MAX_SWING_RATIO = 0.6;
	private static final double MAX_STABLE_MEDIAN_MS = 75.0;
	private static final double MAX_STABLE_P95_FLOOD_MS = 100.0;
	/** 默认每次注入 3µs 模拟开销，不是实测的第三方 CPU 成本。 */
	private static final long PER_PUSH_NANOS = Long.getLong("pbg.benchPerPushNanos", 3000L);
	/** 0=关闭；启用时考察同一套通过条件下的突发负载恢复。 */
	private static final int SPIKE_EVERY_TICKS = Integer.getInteger("pbg.benchSpikeEveryTicks", 0);
	private static final long SPIKE_MILLIS = Long.getLong("pbg.benchSpikeMillis", 90L);

	private static CentrifugeDispatchProbe probe;
	private static boolean finished;
	private static final List<Sample> samples = new ArrayList<>();

	private record Sample(int tick, int budget, int accepted, double avgMspt, long floodNanos,
			boolean exhaustedWithHonestCapacity) {
	}

	private DispatchBenchmarkServer() {
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void tick(ServerTickEvent.Pre event) {
		if (!Boolean.getBoolean("pbg.dispatchBenchmark.enabled") || finished) {
			return;
		}
		int gameTick = event.getServer().getTickCount();
		if (gameTick < WARMUP_TICK) {
			return;
		}
		try {
			if (PER_PUSH_NANOS < 0 || SPIKE_EVERY_TICKS < 0 || SPIKE_MILLIS < 0
					|| SPIKE_MILLIS > 10_000) {
				throw new IllegalArgumentException("Invalid dispatch benchmark cost or spike settings");
			}
			if (probe == null) {
				probe = new CentrifugeDispatchProbe();
			}
			// 尖峰计入真实 tick；floodNanos 单独量测洪水本身，避免混淆两类成本。
			if (SPIKE_EVERY_TICKS > 0 && gameTick % SPIKE_EVERY_TICKS == 0) {
				long start = System.nanoTime();
				while (System.nanoTime() - start < SPIKE_MILLIS * 1_000_000L) {
					// 自旋制造主线程尖峰
				}
			}
			long started = System.nanoTime();
			int accepted = probe.floodPerCopyOneTick(FLOOD_CAP, PER_PUSH_NANOS);
			long floodNanos = System.nanoTime() - started;
			samples.add(new Sample(gameTick, probe.currentBudget(), accepted, currentAvgMspt(),
					floodNanos, probe.exhaustedWithoutLyingAboutCapacity()));
			if (gameTick >= MEASURE_UNTIL_TICK) {
				finish(event, buildReport());
			}
		} catch (Exception | LinkageError failure) {
			JsonObject report = new JsonObject();
			report.addProperty("passed", false);
			report.addProperty("probeFailure", String.valueOf(failure));
			report.addProperty("completedSamples", samples.size());
			LOGGER.error("DISPATCH_BENCHMARK_FAILED", failure);
			finish(event, report);
		}
	}

	private static JsonObject buildReport() {
		JsonObject report = new JsonObject();
		List<Integer> budgets = new ArrayList<>();
		List<Double> mspts = new ArrayList<>();
		List<Double> floodMs = new ArrayList<>();
		boolean complete = samples.size() == MEASURE_UNTIL_TICK - WARMUP_TICK + 1;
		boolean countsMatchBudget = true;
		boolean capacityHonestWhenExhausted = true;
		boolean resumesNextTick = true;
		boolean validMeasurements = true;
		int recoveries = 0;
		long peakFloodNanos = 0;
		for (int i = 0; i < samples.size(); i++) {
			Sample s = samples.get(i);
			complete &= s.tick() == WARMUP_TICK + i;
			countsMatchBudget &= s.accepted() > 0 && s.accepted() == s.budget()
					&& s.accepted() < FLOOD_CAP;
			capacityHonestWhenExhausted &= s.exhaustedWithHonestCapacity();
			validMeasurements &= Double.isFinite(s.avgMspt()) && s.avgMspt() > 0
					&& s.floodNanos() > 0;
			peakFloodNanos = Math.max(peakFloodNanos, s.floodNanos());
			if (i > 0) {
				Sample previous = samples.get(i - 1);
				boolean recovered = previous.exhaustedWithHonestCapacity()
						&& s.tick() == previous.tick() + 1 && s.accepted() > 0;
				resumesNextTick &= recovered;
				if (recovered) recoveries++;
			}
			if (s.tick() >= STABLE_FROM_TICK) {
				budgets.add(s.budget());
				mspts.add(s.avgMspt());
				floodMs.add(s.floodNanos() / 1_000_000.0);
			}
		}
		complete &= budgets.size() == MEASURE_UNTIL_TICK - STABLE_FROM_TICK + 1;
		resumesNextTick &= recoveries == MEASURE_UNTIL_TICK - WARMUP_TICK;
		budgets.sort(null);
		mspts.sort(null);
		floodMs.sort(null);
		int median = budgets.isEmpty() ? 0 : budgets.get(budgets.size() / 2);
		int min = budgets.isEmpty() ? 0 : budgets.get(0);
		int max = budgets.isEmpty() ? 0 : budgets.get(budgets.size() - 1);
		double mspt = mspts.isEmpty() ? -1 : mspts.get(mspts.size() / 2);
		double medianFlood = floodMs.isEmpty() ? -1 : floodMs.get(floodMs.size() / 2);
		double p95Flood = floodMs.isEmpty() ? -1 : floodMs.get((int) Math.ceil(floodMs.size() * 0.95) - 1);
		double swing = median > 0 ? (double) (max - min) / median : -1;
		boolean stable = swing >= 0 && swing < MAX_SWING_RATIO;
		boolean timeBounded = mspt > 0 && mspt <= MAX_STABLE_MEDIAN_MS
				&& medianFlood > 0 && medianFlood <= MAX_STABLE_MEDIAN_MS
				&& p95Flood > 0 && p95Flood <= MAX_STABLE_P95_FLOOD_MS;

		report.addProperty("passed", complete && countsMatchBudget && capacityHonestWhenExhausted
				&& resumesNextTick && validMeasurements && stable && timeBounded);
		report.addProperty("samplesComplete", complete);
		report.addProperty("countsMatchBudget", countsMatchBudget);
		report.addProperty("capacityHonestWhenExhausted", capacityHonestWhenExhausted);
		report.addProperty("resumesNextTick", resumesNextTick);
		report.addProperty("recoveryCount", recoveries);
		report.addProperty("validMeasurements", validMeasurements);
		report.addProperty("stable", stable);
		report.addProperty("timeBounded", timeBounded);
		report.addProperty("sampleCount", samples.size());
		report.addProperty("stableSampleCount", budgets.size());
		report.addProperty("perPushNanos", PER_PUSH_NANOS);
		report.addProperty("spikeEveryTicks", SPIKE_EVERY_TICKS);
		report.addProperty("spikeMillis", SPIKE_MILLIS);
		report.addProperty("convergedBudgetMedian", median);
		report.addProperty("budgetMin", min);
		report.addProperty("budgetMax", max);
		report.addProperty("swingRatio", swing);
		report.addProperty("maxSwingRatioExclusive", MAX_SWING_RATIO);
		report.addProperty("medianMsptAtConverge", mspt);
		report.addProperty("medianFloodMsAtConverge", medianFlood);
		report.addProperty("p95FloodMsAtConverge", p95Flood);
		report.addProperty("peakFloodMs", peakFloodNanos / 1_000_000.0);
		report.addProperty("maxStableMedianMs", MAX_STABLE_MEDIAN_MS);
		report.addProperty("maxStableP95FloodMs", MAX_STABLE_P95_FLOOD_MS);
		report.addProperty("note", "真实 tick/目标/Mixin + 注入模拟成本；稳定段预算振幅<60%，"
				+ "MSPT与洪水中位数≤75ms，洪水P95≤100ms。非完整CPU或玩家存档性能验收。");
		JsonArray series = new JsonArray();
		for (Sample s : samples) {
			JsonObject p = new JsonObject();
			p.addProperty("tick", s.tick());
			p.addProperty("budget", s.budget());
			p.addProperty("accepted", s.accepted());
			p.addProperty("mspt", s.avgMspt());
			p.addProperty("floodMs", s.floodNanos() / 1_000_000.0);
			p.addProperty("exhaustedWithHonestCapacity", s.exhaustedWithHonestCapacity());
			series.add(p);
		}
		report.add("series", series);
		LOGGER.info("DISPATCH_BENCHMARK passed={} budgetMedian={} min={} max={} swing={} mspt={} floodP95={}",
				report.get("passed"), median, min, max, swing, mspt, p95Flood);
		return report;
	}

	private static double currentAvgMspt() {
		return com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor.getInstance().getAvgMspt();
	}

	private static void finish(ServerTickEvent.Pre event, JsonObject report) {
		finished = true;
		try {
			report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
			report.addProperty("ecoLoaded", ModList.get().isLoaded("neoecoae"));
			report.addProperty("mekanismLoaded", ModList.get().isLoaded("mekanism"));
			Files.createDirectories(Path.of("results"));
			Files.writeString(Path.of("results/dispatch-benchmark.json"),
					new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception writeFailure) {
			LOGGER.error("Cannot persist dispatch benchmark report", writeFailure);
		} finally {
			event.getServer().halt(false);
		}
	}
}
