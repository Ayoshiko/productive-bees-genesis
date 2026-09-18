package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** 显式创建与已有身份读取分离；域坏损只隔离该域，目录坏损拒绝新建。 */
public final class NetworkDirectory {
	private static final String NAME = "productivebeesgenesis_network_directory";
	private final Path folder;
	private final HolderLookup.Provider registries;
	private final NetworkCheckpointCodec codec;
	private final Executor executor;
	private final AcknowledgedSavedData.Writer writer;
	private final NetworkDirectoryData index;
	private final String failure;
	private final Map<UUID, NetworkSavedData> loaded = new ConcurrentHashMap<>();
	private final DimensionDataStorage storage;
	private final Thread owner = Thread.currentThread();
	public NetworkDirectory(Path folder, HolderLookup.Provider registries, Executor executor, DimensionDataStorage storage) {
		this(folder, registries, executor, storage, NetworkCheckpointCodec.forRegistries(registries), CheckpointFiles::write);
	}
	NetworkDirectory(Path folder, HolderLookup.Provider registries, Executor executor, DimensionDataStorage storage,
			NetworkCheckpointCodec codec, AcknowledgedSavedData.Writer writer) {
		this.folder = folder.toAbsolutePath().normalize(); this.registries = registries; this.executor = executor;
		this.storage = storage; this.codec = codec; this.writer = writer;
		NetworkDirectoryData data = null; String problem = "";
		try { data = Files.exists(file(NAME)) ? NetworkDirectoryData.load(read(file(NAME)), executor, writer) : NetworkDirectoryData.create(executor, writer); }
		catch (IOException | RuntimeException error) { problem = error.toString(); }
		index = data; failure = problem;
		if (index != null && storage != null) storage.set(NAME, index);
	}
	public String failure() { return failure; }
	public NetworkSavedData create(NetworkIdentity identity) throws IOException {
		check();
		if (index == null) throw new IOException("Directory is unreadable: " + failure);
		if (index.find(identity.networkId()) != null || Files.exists(domainFile(identity.networkId())) || loaded.containsKey(identity.networkId())) {
			throw new IllegalArgumentException("Refusing to overwrite an existing network identity");
		}
		index.validateAddition(identity);
		var domain = NetworkSavedData.create(NetworkCheckpoint.empty(identity), executor, writer);
		// 新建通常为空域；完整域落盘之前不发布目录和可执行句柄。
		domain.flush(domainFile(identity.networkId()).toFile(), registries);
		index.add(identity); index.flush(file(NAME).toFile(), registries);
		register(domain); return domain;
	}
	public NetworkSavedData loadExisting(NetworkIdentity expected) {
		check();
		if (index == null || !expected.equals(index.find(expected.networkId()))) {
			return NetworkSavedData.recovery(expected, "Missing or mismatched directory identity: " + failure, executor, writer);
		}
		var cached = loaded.get(expected.networkId());
		if (cached != null) return cached;
		NetworkSavedData domain;
		try {
			var checkpoint = codec.decode(read(domainFile(expected.networkId())));
			if (!expected.equals(checkpoint.identity())) throw new IllegalArgumentException("Directory/domain identity mismatch");
			domain = NetworkSavedData.loaded(checkpoint, executor, writer);
		} catch (IOException | RuntimeException error) {
			domain = NetworkSavedData.recovery(expected, error.toString(), executor, writer);
			LogUtils.getLogger().error("Bee network {} quarantined; original data retained", expected.networkId(), error);
		}
		register(domain); return domain;
	}
	/** 人工修复文件后必须显式重新解码；不会因普通 get 调用丢弃隔离结果。 */
	public NetworkSavedData reloadRecovered(NetworkIdentity expected) {
		check(); var current = loaded.get(expected.networkId());
		if (current != null && current.status() != NetworkSavedData.Status.RECOVERY) throw new IllegalStateException("Cannot reload a writable live domain");
		loaded.remove(expected.networkId()); return loadExisting(expected);
	}
	public void tick() {
		check();
		if (index != null) index.retry(file(NAME).toFile(), registries);
		for (var domain : loaded.values()) domain.retry(domainFile(domain.identity().networkId()).toFile(), registries);
	}
	public void flush() throws IOException {
		check(); IOException combined = null;
		for (var domain : loaded.values()) {
			if (domain.status() == NetworkSavedData.Status.RECOVERY) continue;
			try { domain.flush(domainFile(domain.identity().networkId()).toFile(), registries); }
			catch (IOException error) { if (combined == null) combined = error; else combined.addSuppressed(error); }
		}
		if (index != null) {
			try { index.flush(file(NAME).toFile(), registries); }
			catch (IOException error) { if (combined == null) combined = error; else combined.addSuppressed(error); }
		}
		if (combined != null) throw combined;
	}
	private void register(NetworkSavedData domain) {
		loaded.put(domain.identity().networkId(), domain);
		if (storage != null) storage.set(domainName(domain.identity().networkId()), domain);
	}
	private static CompoundTag read(Path file) throws IOException {
		return StrictNbt.compound(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), "data");
	}
	private Path file(String name) { return folder.resolve(name + ".dat"); }
	Path domainFile(UUID id) { return file(domainName(id)); }
	private static String domainName(UUID id) { return "productivebeesgenesis_network_" + id; }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Network directory belongs to its server thread"); }
}
