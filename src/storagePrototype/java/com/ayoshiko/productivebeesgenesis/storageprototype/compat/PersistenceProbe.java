package com.ayoshiko.productivebeesgenesis.storageprototype.compat;

import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;

/** 所有故障文件只写入独立 D02 目录；不对运行存档做破坏测试。 */
public final class PersistenceProbe {
	private static final BigInteger BIG = BigInteger.ONE.shiftLeft(180);
	private final HolderLookup.Provider registries;
	private final Path directory;

	public PersistenceProbe(HolderLookup.Provider registries, Path directory) throws IOException {
		this.registries = registries;
		this.directory = directory;
		Files.createDirectories(directory);
	}

	private DimensionDataStorage storage(Path folder) {
		return new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries);
	}

	public JsonObject faults() throws IOException {
		JsonObject result = new JsonObject();
		expectUnavailable(directory, "missing");
		require(!Files.exists(directory.resolve("missing.dat")), "Missing load created a domain");
		result.addProperty("missingReferenceRejected", true);
		Files.write(directory.resolve("truncated.dat"), new byte[]{31, -117, 8, 0});
		checkPreserved("truncated");
		result.addProperty("truncatedPreserved", true);
		PrototypeSavedData sample = new PrototypeSavedData();
		sample.amounts().set(key(0), BIG);
		sample.changed();
		var repeatedSave = storage(directory);
		repeatedSave.set("updated", sample);
		repeatedSave.save();
		IOUtilities.waitUntilIOWorkerComplete();
		sample.amounts().add(key(0), 1);
		sample.changed();
		repeatedSave.save();
		IOUtilities.waitUntilIOWorkerComplete();
		require(ExistingDomainLoader.load(storage(directory), "updated").amounts().exact(key(0)).equals(BIG.add(BigInteger.ONE)),
				"A second save did not replace the prior checkpoint");
		result.addProperty("changedRevisionReplacedExistingFile", true);
		CompoundTag valid = sample.save(new CompoundTag(), registries);
		CompoundTag bad = valid.copy();
		bad.putInt("schema", 99);
		writeFault("schema", bad);
		bad = valid.copy();
		bad.getList("entries", 10).getCompound(0).putByteArray("amount", BigInteger.valueOf(-1).toByteArray());
		writeFault("negative", bad);
		bad = valid.copy();
		bad.getList("entries", 10).add(bad.getList("entries", 10).getCompound(0).copy());
		writeFault("duplicate", bad);
		bad = valid.copy();
		bad.getList("entries", 10).getCompound(0).getCompound("key").putString("id", "d02_missing:product");
		writeFault("unknown_key", bad);
		result.addProperty("schemaNegativeDuplicateUnknownKeyRejected", true);

		// 显式复现原生便利方法的失败回退，原文件仍保留，绝不 save 此空对象。
		var silentlyCreated = storage(directory).computeIfAbsent(PrototypeSavedData.FACTORY, "truncated");
		require(silentlyCreated.amounts().size() == 0, "Unexpected fallback behavior");
		result.addProperty("nativeComputeIfAbsentReturnedEmptyAfterReadFailure", true);

		Path blocker = directory.resolve("blocked-parent");
		Files.writeString(blocker, "This file deliberately blocks a data directory");
		var failedStorage = storage(blocker);
		failedStorage.set("failed", sample);
		sample.setDirty();
		failedStorage.save();
		IOUtilities.waitUntilIOWorkerComplete();
		require(!Files.exists(blocker.resolve("failed.dat")), "Failure injection did not fail");
		require(!sample.isDirty(), "NeoForge dirty semantics changed; review evidence");
		result.addProperty("nativeWriteFailureClearsDirty", true);
		sample.setDirty();
		result.addProperty("explicitRetryRestoresDirty", sample.isDirty());
		return result;
	}

	private void writeFault(String id, CompoundTag payload) throws IOException {
		CompoundTag root = new CompoundTag();
		root.put("data", payload);
		IOUtilities.writeNbtCompressed(root, directory.resolve(id + ".dat"));
		checkPreserved(id);
	}

	private void checkPreserved(String id) throws IOException {
		byte[] before = Files.readAllBytes(directory.resolve(id + ".dat"));
		expectUnavailable(directory, id);
		require(Arrays.equals(before, Files.readAllBytes(directory.resolve(id + ".dat"))), "Fault file was overwritten");
	}

	private void expectUnavailable(Path folder, String id) throws IOException {
		try {
			ExistingDomainLoader.load(storage(folder), id);
		} catch (IOException expected) {
			return;
		}
		throw new IllegalStateException("Expected RECOVERY for " + id);
	}

	public JsonObject benchmark(int count) throws IOException {
		JsonObject row = saveGenerated(count);
		System.gc();
		long heapBefore = heapUsed();
		long begin = System.nanoTime();
		var restored = ExistingDomainLoader.load(storage(directory), "size_" + count);
		row.addProperty("loadMs", (System.nanoTime() - begin) / 1_000_000D);
		require(restored.amounts().size() == count, "Load lost product types");
		for (int index : new int[]{0, count / 2, count - 1}) {
			require(restored.amounts().exact(key(index)).equals(amount(index)), "Load changed exact components or amount");
		}
		BigInteger[] sum = {BigInteger.ZERO};
		restored.amounts().visitExact((key, value) -> sum[0] = sum[0].add(value));
		require(sum[0].toString().equals(row.get("totalAmount").getAsString()), "Round trip changed total quantity");
		System.gc();
		row.addProperty("approximateLoadedHeapBytes", heapUsed() - heapBefore);
		// 确认无配额的精确键数量后释放整表，下一档在单独方法中创建。
		return row;
	}

	private JsonObject saveGenerated(int count) throws IOException {
		System.gc();
		long heapBefore = heapUsed();
		var data = new PrototypeSavedData();
		BigInteger total = BigInteger.ZERO;
		long begin = System.nanoTime();
		for (int i = 0; i < count; i++) {
			BigInteger amount = amount(i);
			data.amounts().set(key(i), amount);
			total = total.add(amount);
		}
		JsonObject row = new JsonObject();
		row.addProperty("keys", count);
		row.addProperty("constructionMs", (System.nanoTime() - begin) / 1_000_000D);
		row.addProperty("totalAmount", total.toString());
		System.gc();
		row.addProperty("approximateStoreHeapBytes", heapUsed() - heapBefore);
		DimensionDataStorage storage = storage(directory);
		storage.set("size_" + count, data);
		data.changed();
		begin = System.nanoTime();
		storage.save();
		row.addProperty("mainThreadSnapshotAndCopyMs", (System.nanoTime() - begin) / 1_000_000D);
		IOUtilities.waitUntilIOWorkerComplete();
		row.addProperty("saveAndWaitMs", (System.nanoTime() - begin) / 1_000_000D);
		row.addProperty("encodeMs", data.lastEncodingNanos() / 1_000_000D);
		row.addProperty("compressedBytes", Files.size(directory.resolve("size_" + count + ".dat")));
		require(!data.isDirty(), "Successful save remains dirty");
		return row;
	}

	public static AEItemKey key(int index) {
		ItemStack stack = new ItemStack(Items.GOLD_INGOT);
		CompoundTag components = new CompoundTag();
		components.putInt("d02_variant", index);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(components));
		return AEItemKey.of(stack);
	}

	private static BigInteger amount(int index) {
		return index % 100 == 0 ? BIG.add(BigInteger.valueOf(index)) : BigInteger.valueOf(10_000L + index);
	}

	private static long heapUsed() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }

	public static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
