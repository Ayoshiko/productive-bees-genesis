package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompiler;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompilation;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetStore;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 独立 JVM 的首调和热调用分开计量；不把这些阶段计时当成完整 MSPT。 */
final class ColdInitializationProbe {
	private static final com.sun.management.ThreadMXBean ALLOCATIONS =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static Object retained;
	static void verify(ServerLevel level, JsonObject report) {
		var metrics = new JsonObject();
		metrics.addProperty("scope", "First invocation and warm server-thread work; excludes disk IO and full MSPT");
		metrics.addProperty("java", System.getProperty("java.version"));
		metrics.addProperty("vm", System.getProperty("java.vm.name"));
		metrics.addProperty("processors", Runtime.getRuntime().availableProcessors());
		boolean reference = Boolean.getBoolean("pbg.cold.reference"); metrics.addProperty("referenceCompiler", reference);
		if (!reference) incremental(level, metrics);
		metrics.add("policyCompilation", measure(() -> reference ? ReferenceProductPolicyCompiler.compile(level, 1) : PbProductPolicyCompiler.compile(level, 1), 8));
		var snapshot = reference ? ((ReferenceProductPolicyCompiler.Result) retained).snapshot() : ((PbProductPolicyCompiler.Result) retained).snapshot();
		var diagnostics = reference ? ((ReferenceProductPolicyCompiler.Result) retained).diagnostics() : ((PbProductPolicyCompiler.Result) retained).diagnostics();
		require(snapshot.descriptorCount() > 100, "Cold policy catalog was empty");
		metrics.addProperty("descriptors", snapshot.descriptorCount()); metrics.addProperty("diagnostics", String.join("; ", diagnostics));
		var components = new JsonArray();
		for (int size : new int[] {0, 16_384, 65_536}) {
			var stack = new ItemStack(Items.IRON_INGOT);
			if (size != 0) { var tag = new CompoundTag(); tag.putByteArray("probe", new byte[size]); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); }
			var sample = measure(() -> ProductKeyCodec.item(stack, level.registryAccess()), 64);
			sample.addProperty("payloadBytes", size); components.add(sample);
		}
		metrics.add("productKeyEncoding", components);
		var tile = new TileEntityMekCentrifuge(ModBlocks.MEK_CENTRIFUGE, new BlockPos(0, 150, 0), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
		var store = new MachineAssetStore(tile);
		tile.primaryOutputSlot(0).setStack(new ItemStack(Items.IRON_INGOT, 64));
		metrics.add("handoffCapture", measure(() -> store.capture(level.registryAccess()), 64));
		var asset = store.capture(level.registryAccess());
		metrics.add("handoffValidate", measure(() -> { store.validate(asset, level); return asset; }, 16));
		var fixture = CheckpointCaptureBenchmark.fixture(1_000, 100);
		metrics.add("checkpointCapture", measure(() -> fixture.source().capture(1), 64));
		metrics.addProperty("checkpointKeys", 1_000); metrics.addProperty("checkpointMembers", 100);
		report.add("coldInitialization", metrics);
		retained = null;
	}
	private static void incremental(ServerLevel level, JsonObject metrics) {
		long start = System.nanoTime(); var cursor = new PbProductPolicyCompilation(level, 1);
		var sample = new JsonObject(); sample.addProperty("createNanos", System.nanoTime() - start);
		var phases = PbProductPolicyCompilation.Phase.values(); long[] times = new long[phases.length], longest = new long[phases.length]; int[] counts = new int[phases.length];
		int calls = 0; boolean done = false;
		while (!done) {
			int phase = cursor.phase().ordinal(); start = System.nanoTime(); done = cursor.step(); long elapsed = System.nanoTime() - start;
			counts[phase]++; times[phase] += elapsed; longest[phase] = Math.max(longest[phase], elapsed);
			require(++calls < 1_000_000, "Unbounded cold policy compilation");
		}
		var steps = new JsonObject();
		for (var phase : phases) { var row = new JsonObject(); int i = phase.ordinal(); row.addProperty("steps", counts[i]); row.addProperty("totalNanos", times[i]); row.addProperty("maxNanos", longest[i]); steps.add(phase.name(), row); }
		sample.add("phases", steps); sample.addProperty("steps", calls); sample.addProperty("recipeCount", level.getRecipeManager().getRecipes().size());
		var expected = ReferenceProductPolicyCompiler.compile(level, 1); var actual = cursor.result();
		require(expected.snapshot().descriptorCount() == actual.snapshot().descriptorCount(), "Incremental policy lost or added products");
		for (var key : expected.keys()) require(expected.snapshot().descriptors(key).equals(actual.snapshot().descriptors(key)), "Policy differs from pre-change compiler for " + key);
		require(expected.diagnostics().equals(actual.diagnostics()), "Incremental diagnostics changed");
		try { actual.snapshot().descriptors(expected.keys().getFirst()).clear(); throw new IllegalStateException("Mutable published catalog"); }
		catch (UnsupportedOperationException correct) { }
		var interrupted = new PbProductPolicyCompilation(level, 1); interrupted.step();
		com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
		boolean refused = false; try { interrupted.step(); } catch (IllegalStateException correct) { refused = true; }
		require(refused, "Stale recipe generation resumed");
		refused = false; try { cursor.result(); } catch (IllegalStateException correct) { refused = true; }
		require(refused, "Stale recipe generation published");
		com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimePolicyProbe.verify(level, calls);
		sample.addProperty("matchesPreviousCompiler", true); sample.addProperty("staleGenerationRejected", true); sample.addProperty("sharedRuntimeCursorVerified", true);
		metrics.add("incrementalCompilation", sample);
	}
	private static JsonObject measure(Supplier<?> operation, int count) {
		long thread = Thread.currentThread().threadId();
		long allocated = ALLOCATIONS.getThreadAllocatedBytes(thread), start = System.nanoTime();
		retained = operation.get();
		long cold = System.nanoTime() - start, coldBytes = ALLOCATIONS.getThreadAllocatedBytes(thread) - allocated;
		long[] times = new long[count]; allocated = ALLOCATIONS.getThreadAllocatedBytes(thread);
		for (int i = 0; i < count; i++) { start = System.nanoTime(); retained = operation.get(); times[i] = System.nanoTime() - start; }
		long warmBytes = ALLOCATIONS.getThreadAllocatedBytes(thread) - allocated; Arrays.sort(times);
		var metrics = new JsonObject(); metrics.addProperty("firstNanos", cold); metrics.addProperty("firstAllocatedBytes", coldBytes);
		metrics.addProperty("warmSamples", count); metrics.addProperty("warmMedianNanos", times[count / 2]);
		metrics.addProperty("warmP95Nanos", times[(int) Math.ceil(count * .95) - 1]); metrics.addProperty("warmMaxNanos", times[count - 1]);
		metrics.addProperty("warmAllocatedBytesPerCall", warmBytes / count); return metrics;
	}
	private ColdInitializationProbe() { }
}
