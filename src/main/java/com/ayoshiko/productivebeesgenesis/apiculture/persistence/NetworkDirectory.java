package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** 目录和域共用一个有界读取器；正常 tick 只推进预算工作，不等待文件或写入回执。 */
public final class NetworkDirectory implements AutoCloseable {
	public enum State { LOADING, READY, RECOVERY, CLOSED }
	public record SaveStatus(int waitingDomains, int activeSnapshots, int reservedBufferBytes, long submitted, long completed) { }
	private static final String NAME = "productivebeesgenesis_network_directory";
	private final Path folder;
	private final HolderLookup.Provider registries;
	private final NetworkCheckpointCodec codec;
	private final CheckpointSaveQueue saves;
	private final CheckpointReadService reads = new CheckpointReadService();
	private final Map<UUID, NetworkOpenHandle> handles = new ConcurrentHashMap<>();
	private final Map<UUID, NetworkSavedData> loaded = new ConcurrentHashMap<>();
	private final ArrayDeque<NetworkOpenHandle> waiting = new ArrayDeque<>();
	private final DimensionDataStorage storage;
	private final Thread owner = Thread.currentThread();
	private NetworkDirectoryData index;
	private State state = State.LOADING;
	private String failure = "";
	private long generation;
	private CheckpointDecoder decoder;
	private NetworkOpenHandle active;
	private NetworkSavedData creating;
	private long creationIndexRevision = -1;
	public NetworkDirectory(Path folder, HolderLookup.Provider registries, Executor executor, DimensionDataStorage storage) {
		this(folder, registries, executor, storage, NetworkCheckpointCodec.forRegistries(registries), CheckpointFiles::write);
	}
	NetworkDirectory(Path folder, HolderLookup.Provider registries, Executor executor, DimensionDataStorage storage,
			NetworkCheckpointCodec codec, AcknowledgedSavedData.Writer writer) {
		this.folder = folder.toAbsolutePath().normalize(); this.registries = registries;
		this.storage = storage; this.codec = codec; saves = new CheckpointSaveQueue(executor, writer);
		if (Files.notExists(file(NAME))) { index = NetworkDirectoryData.create(saves); directoryReady(); }
	}
	public State state() { check(); return state; }
	public String failure() { check(); return failure; }
	public boolean holdsMember(net.minecraft.world.level.block.entity.BlockEntity tile) {
		checkOpen(); if (state != State.READY) return true;
		if (!index.hasClaims()) return false;
		var pos = tile.getBlockPos();
		return index.claimAt(new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin(tile.getLevel().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ())) != null;
	}
	public com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim claimAt(com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin origin) {
		checkOpen(); if (state != State.READY) throw new IllegalStateException("Membership directory unavailable"); return index.claimAt(origin);
	}
	public long reserveMember(NetworkSavedData authority, com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim claim) {
		requireAuthority(authority); if (!authority.identity().networkId().equals(claim.network())) throw new IllegalArgumentException("Foreign claim");
		var record = authority.checkpoint().ownedMachines().get(claim.member());
		if (record != null && (!record.claim().equals(claim) || record.phase() == com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord.Phase.RETURNED)) throw new IllegalArgumentException("Replayed member claim");
		long revision = index.reserve(claim); index.save(file(NAME).toFile(), registries); return revision;
	}
	public long releaseMember(NetworkSavedData authority, com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim claim) {
		requireAuthority(authority); if (!authority.identity().networkId().equals(claim.network())) throw new IllegalArgumentException("Foreign claim");
		var checkpoint = authority.checkpoint(); var record = checkpoint.ownedMachines().get(claim.member());
		if (record == null || !record.claim().equals(claim) || record.phase() != com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord.Phase.RETURNED
				|| authority.persistedRevision() < checkpoint.revision()) throw new IllegalStateException("A durable returned receipt is required before releasing a member");
		long revision = index.release(claim); index.save(file(NAME).toFile(), registries); return revision;
	}
	public long persistedDirectoryRevision() { checkOpen(); return index == null ? -1 : index.persistedRevision(); }
	public long directoryRevision() {
		checkOpen(); if (state != State.READY) throw new IllegalStateException("Membership directory unavailable");
		return index.revision();
	}
	public void requestSave(NetworkSavedData authority) { requireAuthority(authority); authority.save(domainFile(authority.identity().networkId()).toFile(), registries); }
	private void requireAuthority(NetworkSavedData authority) {
		checkOpen(); if (state != State.READY || loaded.get(authority.identity().networkId()) != authority) throw new IllegalStateException("Unpublished network authority");
	}
	public NetworkOpenHandle create(NetworkIdentity identity) {
		checkOpen();
		if (state == State.RECOVERY) throw new IllegalStateException("Directory unreadable: " + failure);
		if (handles.containsKey(identity.networkId()) || Files.exists(domainFile(identity.networkId()))) throw new IllegalArgumentException("Existing network identity");
		if (index != null) index.validateAddition(identity);
		return enqueue(identity, true);
	}
	public NetworkOpenHandle loadExisting(NetworkIdentity expected) {
		checkOpen(); var cached = handles.get(expected.networkId());
		if (cached != null) {
			if (!cached.identity().equals(expected)) return rejected(expected, "Mismatched cached identity");
			return cached;
		}
		return enqueue(expected, false);
	}
	private NetworkOpenHandle enqueue(NetworkIdentity identity, boolean create) {
		var handle = new NetworkOpenHandle(identity, create, generation); handles.put(identity.networkId(), handle); waiting.addLast(handle); return handle;
	}
	private NetworkOpenHandle rejected(NetworkIdentity identity, String reason) {
		var handle = new NetworkOpenHandle(identity, false, generation); handle.reject(reason); return handle;
	}
	/** 人工修复／重载后显式重开；旧加载代际不能发布到新的句柄。 */
	public NetworkOpenHandle reloadRecovered(NetworkIdentity expected) {
		checkOpen(); var current = handles.get(expected.networkId());
		if (current != null && (current.state() == NetworkOpenHandle.State.READY || current.pending())) throw new IllegalStateException("Cannot replace an active authority");
		handles.remove(expected.networkId()); return loadExisting(expected);
	}
	public void invalidateLoads() {
		checkOpen(); generation = Math.incrementExact(generation);
		// 不遍历所有句柄；generation 在出队／发布边界使旧请求失效。
		if (active != null) active.cancel();
		if (decoder != null) { decoder.close(); decoder = null; }
	}
	public void reloadDirectory() {
		checkOpen(); if (state != State.RECOVERY) throw new IllegalStateException("Only quarantined directory can be reloaded");
		invalidateLoads(); state = State.LOADING; failure = "";
	}
	public void tick() { tick(2048, 2_000_000); }
	public void tick(int steps, long budgetNanos) {
		tick(steps, budgetNanos, 32);
	}
	public boolean hasPendingWork() {
		checkOpen(); return state == State.LOADING || active != null || !waiting.isEmpty() || saves.pending();
	}
	void tick(int steps, long budgetNanos, int saveChecks) {
		checkOpen(); if (steps <= 0 || budgetNanos <= 0) throw new IllegalArgumentException("Positive load budget required");
		saves.tick(saveChecks);
		try {
			if (state == State.LOADING) { advanceDirectory(steps, budgetNanos); return; }
			if (active == null) { active = waiting.pollFirst(); if (active == null) return; }
			if (active.generation != generation) active.cancel();
			if (!active.pending() && creating == null) { clearActive(); return; }
			if (state == State.RECOVERY) { active.reject("Directory unreadable: " + failure); clearActive(); return; }
			if (active.creating) advanceCreation(); else advanceLoad(steps, budgetNanos);
		} catch (RuntimeException error) {
			if (state == State.LOADING) { state = State.RECOVERY; failure = error.toString(); }
			else if (active != null) { active.reject(error.toString()); LogUtils.getLogger().error("Bee network {} quarantined; data retained", active.identity().networkId(), error); }
			clearActive();
		}
	}
	private void advanceDirectory(int steps, long budget) {
		if (decoder == null) {
			var input = reads.tryOpen(file(NAME)); if (input.isEmpty()) return;
			decoder = CheckpointDecoder.directory(input.get());
		}
		var progress = decoder.step(steps, budget);
		if (progress.state() == CheckpointDecoder.State.COMPLETE) {
			index = NetworkDirectoryData.restore(decoder.directoryState(), saves); decoder.close(); decoder = null; directoryReady();
		} else if (progress.state() == CheckpointDecoder.State.FAILED) throw new IllegalArgumentException(progress.failure());
	}
	private void directoryReady() { state = State.READY; if (storage != null) storage.set(NAME, index); }
	private void advanceLoad(int steps, long budget) {
		if (!active.identity().equals(index.find(active.identity().networkId()))) throw new IllegalArgumentException("Missing or mismatched directory identity");
		if (decoder == null) {
			var input = reads.tryOpen(domainFile(active.identity().networkId())); if (input.isEmpty()) return;
			decoder = codec.decoder(input.get()); active.state(NetworkOpenHandle.State.LOADING);
		}
		var progress = decoder.step(steps, budget);
		if (progress.state() == CheckpointDecoder.State.COMPLETE) {
			var checkpoint = decoder.checkpoint();
			if (!active.identity().equals(checkpoint.identity())) throw new IllegalArgumentException("Directory/domain identity mismatch");
			var data = NetworkSavedData.loaded(checkpoint, saves); register(data); active.complete(data); clearActive();
		} else if (progress.state() == CheckpointDecoder.State.FAILED) throw new IllegalArgumentException(progress.failure());
	}
	private void advanceCreation() {
		if (creating == null) {
			index.validateAddition(active.identity());
			if (Files.exists(domainFile(active.identity().networkId()))) throw new IllegalArgumentException("Refusing to replace domain file");
			creating = NetworkSavedData.create(NetworkCheckpoint.empty(active.identity()), saves);
			active.state(NetworkOpenHandle.State.CREATING); creating.save(domainFile(active.identity().networkId()).toFile(), registries); return;
		}
		active.failure(creating.lastFailure());
		if (creating.persistedRevision() < 0) return;
		if (creationIndexRevision < 0) {
			index.add(active.identity()); creationIndexRevision = index.checkpoint().revision(); index.save(file(NAME).toFile(), registries); return;
		}
		active.failure(index.lastFailure());
		if (index.persistedRevision() < creationIndexRevision) return;
		if (active.pending()) { register(creating); active.complete(creating); }
		clearActive();
	}
	private void clearActive() {
		if (decoder != null) decoder.close(); decoder = null; active = null; creating = null; creationIndexRevision = -1;
	}
	public SaveStatus saveStatus() { check(); return saves.status(); }
	/** 唯一允许等待 IO 的边界；未发布的创建也要保存完整身份链，加载候选直接丢弃。 */
	public void flush() throws IOException {
		checkOpen(); IOException combined = null;
		if (creating != null) {
			try {
				creating.flush(domainFile(creating.identity().networkId()).toFile(), registries);
				if (creationIndexRevision < 0) { index.add(creating.identity()); creationIndexRevision = index.checkpoint().revision(); }
				index.flush(file(NAME).toFile(), registries);
				if (active.pending() && active.generation == generation) { register(creating); active.complete(creating); }
				clearActive();
			} catch (IOException error) { combined = error; }
		}
		for (var domain : loaded.values()) {
			try { domain.flush(domainFile(domain.identity().networkId()).toFile(), registries); }
			catch (IOException error) { if (combined == null) combined = error; else combined.addSuppressed(error); }
		}
		if (index != null) {
			try { index.flush(file(NAME).toFile(), registries); }
			catch (IOException error) { if (combined == null) combined = error; else combined.addSuppressed(error); }
		}
		if (combined != null) throw combined;
	}
	@Override public void close() {
		check(); if (state == State.CLOSED) return;
		if (active != null) active.cancel(); handles.values().forEach(NetworkOpenHandle::close); loaded.values().forEach(NetworkSavedData::closeAuthority);
		waiting.clear(); handles.clear(); loaded.clear();
		clearActive(); reads.close(); state = State.CLOSED;
	}
	private void register(NetworkSavedData domain) {
		loaded.put(domain.identity().networkId(), domain);
		if (storage != null) storage.set(domainName(domain.identity().networkId()), domain);
	}
	private Path file(String name) { return folder.resolve(name + ".dat"); }
	Path domainFile(UUID id) { return file(domainName(id)); }
	private static String domainName(UUID id) { return "productivebeesgenesis_network_" + id; }
	private void checkOpen() { check(); if (state == State.CLOSED) throw new IllegalStateException("Directory closed"); }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Network directory belongs to its server thread"); }
}
