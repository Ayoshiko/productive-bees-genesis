package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.IOUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 资源账户的世界存储。读取失败与首次空存档区分；后台只写封闭 NBT 快照，
 * 完成结果回到服务器线程处理，旧快照成功不会清除新变更的 dirty 状态。
 */
public final class ResourceAccountsSavedData extends SavedData {
	/** 主世界 data 目录中的专用文件名。 */
	public static final String STORAGE_NAME = "productivebeesgenesis_resource_accounts";
	private static final long MAX_FILE_NBT_BYTES = 64L * 1024 * 1024;

	@FunctionalInterface
	interface Writer {
		void write(CompoundTag snapshot, Path path) throws IOException;
	}

	private record Completion(long revision, Exception failure) {}

	private final Path path;
	private final Writer writer;
	private final Consumer<Runnable> executor;
	private final ResourceAccounts accounts;
	private final boolean invalidFile;
	private boolean saving;
	private boolean retryRequested;
	private boolean closed;
	private boolean unsafeSession;
	// 只有此完成信箱跨 IO 线程；世界、账户和 dirty 状态始终由服务器线程拥有。
	private volatile Completion completion;

	private ResourceAccountsSavedData(Path path, CompoundTag raw, Exception loadFailure,
			Writer writer, Consumer<Runnable> executor) {
		this.path = path.toAbsolutePath().normalize();
		this.writer = Objects.requireNonNull(writer);
		this.executor = Objects.requireNonNull(executor);
		accounts = raw == null ? new ResourceAccounts(this::setDirty) : ResourceAccounts.load(raw, this::setDirty);
		invalidFile = loadFailure != null || accounts.hasInvalidData();
		if (loadFailure != null) {
			accounts.failPersistence(loadFailure.toString());
			ProductiveBeesGenesis.LOGGER.error("无法加载资源账户，保留原文件并停用资产变更：{}", path, loadFailure);
		} else if (accounts.hasInvalidData()) {
			ProductiveBeesGenesis.LOGGER.error("资源账户格式无效，保留原文件并隔离：{} ({})",
					path, accounts.persistenceFailure());
		}
	}

	/** 加载专用存档；现有文件异常时返回锁定存储，绝不创建空账户覆盖它。 */
	public static ResourceAccountsSavedData open(Path path) {
		return open(path, IOUtilities::writeNbtCompressed, IOUtilities::withIOWorker);
	}

	static ResourceAccountsSavedData open(Path path, Writer writer, Consumer<Runnable> executor) {
		CompoundTag raw = null;
		Exception failure = null;
		try {
			BasicFileAttributes attributes;
			try {
				attributes = Files.readAttributes(path, BasicFileAttributes.class);
			} catch (NoSuchFileException missing) {
				return new ResourceAccountsSavedData(path, null, null, writer, executor);
			}
			if (!attributes.isRegularFile()) throw new IOException("Resource account path is not a regular file");
			var root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_FILE_NBT_BYTES));
			if (!root.contains("data", Tag.TAG_COMPOUND)) throw new IOException("Missing resource account data");
			raw = root.getCompound("data");
		} catch (IOException | RuntimeException error) {
			failure = error;
		}
		return new ResourceAccountsSavedData(path, raw, failure, writer, executor);
	}

	/** 获取本线程的权威集合，并先应用已完成的写盘结果。 */
	public ResourceAccounts accounts() {
		poll();
		return accounts;
	}

	/** IO 失败信号由服务器线程消费；隔离不能通过下一次账户保存成功解除。 */
	void isolateWorldSaveFailure() {
		accounts.checkThread();
		if (unsafeSession) return;
		unsafeSession = true;
		accounts.failPersistence("World storage reported a save failure; restore a consistent complete-world backup");
		ProductiveBeesGenesis.LOGGER.error("世界保存失败，本次运行暂停扩展资源资产变更：{}", path);
	}

	public boolean sessionIsolated() {
		accounts.checkThread();
		return unsafeSession;
	}

	/** 服务器 tick 调用；写盘失败隔离变更，正常成功仅确认对应快照代际。 */
	public void poll() {
		accounts.checkThread();
		var finished = completion;
		if (finished == null) return;
		completion = null;
		saving = false;
		if (finished.failure != null) {
			accounts.failPersistence(finished.failure.toString());
			setDirty();
			ProductiveBeesGenesis.LOGGER.error("资源账户写盘失败，保留余额并暂停资产变更：{}", path, finished.failure);
		} else {
			if (!unsafeSession) accounts.confirmPersistence();
			setDirty(accounts.revision() != finished.revision);
		}
	}

	/** 写入故障排除后显式请求重试；实际写成功前仍禁止资产变更，不自动循环重试。 */
	public void requestSaveRetry() {
		poll();
		if (unsafeSession) throw new IllegalStateException("World save failure cannot be resolved by retrying only the resource account file");
		if (invalidFile || accounts.persistenceFailure().isEmpty()) {
			throw new IllegalStateException("Only a failed write can be retried");
		}
		retryRequested = true;
		setDirty();
	}

	@Override
	public CompoundTag save(CompoundTag target, HolderLookup.Provider provider) {
		accounts.checkThread();
		return accounts.save();
	}

	@Override
	public void save(File file, HolderLookup.Provider provider) {
		poll();
		if (!path.equals(file.toPath().toAbsolutePath().normalize())) {
			throw new IllegalArgumentException("Unexpected resource account save path");
		}
		if (closed || invalidFile || unsafeSession || saving || !isDirty()) return;
		if (!accounts.persistenceFailure().isEmpty() && !retryRequested) return;
		retryRequested = false;
		long revision = accounts.revision();
		var snapshot = new CompoundTag();
		snapshot.put("data", accounts.save());
		NbtUtils.addCurrentDataVersion(snapshot);
		if (snapshot.sizeInBytes() > MAX_FILE_NBT_BYTES) {
			accounts.failPersistence("Resource account snapshot exceeds the disk budget");
			ProductiveBeesGenesis.LOGGER.error("资源账户快照超过保存预算，暂停资产变更：{}", path);
			return;
		}
		saving = true;
		try {
			executor.accept(() -> {
				Exception failure = null;
				try {
					Files.createDirectories(path.getParent());
					writer.write(snapshot, path);
				} catch (IOException | RuntimeException error) {
					failure = error;
				}
				completion = new Completion(revision, failure);
			});
		} catch (RuntimeException failure) {
			completion = new Completion(revision, failure);
			poll();
		}
	}

	/** 完成最后快照并关闭引用；恢复只依据账户中的实际未决请求，不依赖旁路标记。 */
	public void finishAndClose(HolderLookup.Provider provider, boolean cleanWorldShutdown) {
		accounts.checkThread();
		if (!cleanWorldShutdown) {
			unsafeSession = true;
			accounts.failPersistence("World shutdown or saving did not complete cleanly; keep the last saved account snapshot");
			ProductiveBeesGenesis.LOGGER.error("世界未正常完成停服保存，保留最后已保存的资源账户快照：{}", path);
		}
		poll();
		IOUtilities.waitUntilIOWorkerComplete();
		poll();
		save(path.toFile(), provider);
		IOUtilities.waitUntilIOWorkerComplete();
		poll();
		if (isDirty()) {
			ProductiveBeesGenesis.LOGGER.error("停服时资源账户仍有未保存变更：{} ({})",
					path, accounts.persistenceFailure());
		}
		closed = true;
		accounts.close();
	}
}
