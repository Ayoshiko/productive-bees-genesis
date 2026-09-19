package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkDirectory;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import static com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord.Phase.*;

/** 单次交接的回执状态机；调用者每步提供当前加载的机器，不长期持有 BE 或 Level。 */
public final class OwnershipTransferService {
	public enum Step { CLAIM, PREPARE, SEAL, OWNED_RECEIPT, OWNED, RETURN_INTENT, RETURN_WRITE, RETURN_RECEIPT, RELEASE_CLAIM, RELEASE_BINDING, RETURNED, RECOVERY }
	private final Thread owner = Thread.currentThread();
	private final NetworkDirectory directory;
	private final NetworkSavedData authority;
	private final MemberClaim claim;
	private Step step;
	private long directoryRevision, domainRevision;
	private CompletableFuture<Void> returnWrite;
	private String failure = "";
	private boolean advancing;
	private OwnershipTransferService(NetworkDirectory directory, NetworkSavedData authority, MemberClaim claim, Step step) {
		this.directory = Objects.requireNonNull(directory); this.authority = Objects.requireNonNull(authority);
		this.claim = Objects.requireNonNull(claim); this.step = step;
		if (!authority.identity().networkId().equals(claim.network())) throw new IllegalArgumentException("Foreign member claim");
	}
	public static OwnershipTransferService begin(NetworkDirectory directory, NetworkSavedData authority, MemberClaim claim, OwnershipEndpoint endpoint) {
		var service = new OwnershipTransferService(directory, authority, claim, Step.CLAIM);
		if (directory.claimAt(claim.origin()) != null || authority.checkpoint().ownedMachines().hasTransfer(claim.transfer()) || authority.checkpoint().ownedMachines().get(claim.member()) != null
				|| authority.checkpoint().ownedMachines().at(claim.origin()) != null) throw new IllegalArgumentException("Member already claimed or transfer replayed");
		endpoint.validate(authority.identity(), claim);
		endpoint.freeze(authority.identity(), claim);
		try { service.directoryRevision = directory.reserveMember(authority, claim); }
		catch (RuntimeException error) { service.quarantine(endpoint, error); }
		return service;
	}
	/** 重启只接受完整的目录／域／BE 身份链；不会把旧 BE 中的库存再次导入。 */
	public static OwnershipTransferService resume(NetworkDirectory directory, NetworkSavedData authority, MemberClaim claim, OwnershipEndpoint endpoint) {
		var service = new OwnershipTransferService(directory, authority, claim, Step.RECOVERY);
		try {
			endpoint.validate(authority.identity(), claim);
			if (authority.checkpoint().ownedMachines().get(claim.member()) == null) {
				service.requireClaim(); service.requireBinding(endpoint, MemberBinding.Mode.JOINING);
				// 同一进程重建核心时，内存目录可能领先磁盘；恢复也必须等待该版本的回执。
				service.directoryRevision = directory.directoryRevision(); service.step = Step.CLAIM;
				return service;
			}
			var record = service.record(); var indexed = directory.claimAt(claim.origin());
			if (record.phase() != RETURNED && !claim.equals(indexed) || indexed != null && !claim.equals(indexed)) throw new IllegalStateException("Directory ownership mismatch");
			switch (record.phase()) {
				case SEALED -> {
					service.requireBinding(endpoint, MemberBinding.Mode.JOINING);
					if (!record.assets().equals(endpoint.capture())) throw new IllegalStateException("Sealed source changed");
					service.step = Step.SEAL;
				}
				case OWNED -> { service.requireBinding(endpoint, MemberBinding.Mode.MANAGED); service.requireEmpty(endpoint); endpoint.validateReturn(record.assets()); service.step = Step.OWNED_RECEIPT; }
				case RETURNING -> {
					if (endpoint.matches(authority.identity(), claim, MemberBinding.Mode.LEAVING)) {
						service.requireEmpty(endpoint); service.step = Step.RETURN_INTENT;
					}
					else {
						service.requireBinding(endpoint, MemberBinding.Mode.RETURNED);
						if (!record.assets().equals(endpoint.capture())) throw new IllegalStateException("Return target differs from intent");
						service.returnWrite = endpoint.saveReturn().toCompletableFuture(); service.step = Step.RETURN_WRITE;
					}
				}
				case RETURNED -> {
					service.requireBinding(endpoint, MemberBinding.Mode.RETURNED);
					if (!record.fingerprint().equals(endpoint.capture().fingerprint())) throw new IllegalStateException("Completed return target changed");
					service.step = indexed == null ? Step.RELEASE_BINDING : Step.RELEASE_CLAIM;
					service.directoryRevision = directory.directoryRevision();
				}
				case RECOVERY -> throw new IllegalStateException(record.failure());
			}
			service.domainRevision = authority.checkpoint().revision();
		} catch (RuntimeException error) { service.quarantine(endpoint, error); }
		return service;
	}
	public Step step() { check(); return step; }
	public String failure() { check(); return failure; }
	public void requestReturn(OwnershipEndpoint endpoint) {
		check(); if (advancing || step != Step.OWNED) throw new IllegalStateException("Member is not available for return");
		advancing = true;
		try {
			endpoint.validate(authority.identity(), claim); requireClaim(); requireBinding(endpoint, MemberBinding.Mode.MANAGED); requireEmpty(endpoint);
			var record = record(); endpoint.validateReturn(record.assets());
			endpoint.mode(MemberBinding.Mode.LEAVING); publish(record.phase(RETURNING)); step = Step.RETURN_INTENT;
		} catch (RuntimeException error) { quarantine(endpoint, error); }
		finally { advancing = false; }
	}
	/** 一次最多推进一个可观察阶段，后台保存失败由原保存队列退避重试。 */
	public void advance(OwnershipEndpoint endpoint) {
		check(); if (advancing) throw new IllegalStateException("Reentrant ownership transfer");
		if (step == Step.RECOVERY || step == Step.RETURNED) return;
		advancing = true;
		try {
			endpoint.validate(authority.identity(), claim);
			if (step != Step.RELEASE_BINDING) requireClaim();
			switch (step) {
				case CLAIM -> { requireBinding(endpoint, MemberBinding.Mode.JOINING); if (directory.persistedDirectoryRevision() >= directoryRevision) step = Step.PREPARE; }
				case PREPARE -> {
					requireBinding(endpoint, MemberBinding.Mode.JOINING); if (!endpoint.prepare()) return;
					var image = endpoint.capture(); publish(new OwnedMachineRecord(claim, SEALED, image, image.fingerprint(), "")); step = Step.SEAL;
				}
				case SEAL -> {
					if (!saved()) return; requireBinding(endpoint, MemberBinding.Mode.JOINING);
					var record = record(); if (!record.assets().equals(endpoint.capture())) throw new IllegalStateException("Source changed after sealing");
					endpoint.seal(record.assets()); requireEmpty(endpoint); endpoint.mode(MemberBinding.Mode.MANAGED);
					publish(record.phase(OWNED)); step = Step.OWNED_RECEIPT;
				}
				case OWNED_RECEIPT -> { requireBinding(endpoint, MemberBinding.Mode.MANAGED); requireEmpty(endpoint); if (saved()) step = Step.OWNED; }
				case OWNED -> { requireBinding(endpoint, MemberBinding.Mode.MANAGED); requireEmpty(endpoint); }
				case RETURN_INTENT -> {
					if (!saved()) return; requireBinding(endpoint, MemberBinding.Mode.LEAVING); requireEmpty(endpoint);
					var image = record().assets(); endpoint.validateReturn(image); endpoint.restore(image);
					if (!image.equals(endpoint.capture())) throw new IllegalStateException("Incomplete physical return");
					endpoint.mode(MemberBinding.Mode.RETURNED);
					returnWrite = endpoint.saveReturn().toCompletableFuture(); step = Step.RETURN_WRITE;
				}
				case RETURN_WRITE -> {
					requireBinding(endpoint, MemberBinding.Mode.RETURNED);
					if (!record().assets().equals(endpoint.capture())) throw new IllegalStateException("Return target changed before receipt");
					if (!returnWrite.isDone()) return; returnWrite.join(); returnWrite = null;
					publish(record().phase(RETURNED)); step = Step.RETURN_RECEIPT;
				}
				case RETURN_RECEIPT -> { verifyReturned(endpoint); if (saved()) step = Step.RELEASE_CLAIM; }
				case RELEASE_CLAIM -> {
					verifyReturned(endpoint); if (!saved()) return;
					directoryRevision = directory.releaseMember(authority, claim); step = Step.RELEASE_BINDING;
				}
				case RELEASE_BINDING -> {
					verifyReturned(endpoint);
					if (directory.claimAt(claim.origin()) != null) throw new IllegalStateException("Return claim replaced");
					if (directory.persistedDirectoryRevision() < directoryRevision) return;
					endpoint.release(); step = Step.RETURNED;
				}
				default -> throw new IllegalStateException("Unexpected ownership step");
			}
		} catch (RuntimeException error) { quarantine(endpoint, error); }
		finally { advancing = false; }
	}
	private OwnedMachineRecord record() {
		var record = authority.checkpoint().ownedMachines().get(claim.member());
		if (record == null || !record.claim().equals(claim)) throw new IllegalStateException("Missing or conflicting transfer record");
		return record;
	}
	private void publish(OwnedMachineRecord record) {
		var next = authority.checkpoint().withOwnership(record); authority.publish(next); domainRevision = next.revision(); directory.requestSave(authority);
	}
	private boolean saved() { return authority.persistedRevision() >= domainRevision; }
	private void requireClaim() { if (!claim.equals(directory.claimAt(claim.origin()))) throw new IllegalStateException("Member claim changed"); }
	private void requireBinding(OwnershipEndpoint endpoint, MemberBinding.Mode mode) {
		if (!endpoint.matches(authority.identity(), claim, mode)) throw new IllegalStateException("Member binding changed or rolled back");
	}
	private void requireEmpty(OwnershipEndpoint endpoint) { if (!endpoint.empty()) throw new IllegalStateException("Managed source still holds assets"); }
	private void verifyReturned(OwnershipEndpoint endpoint) {
		requireBinding(endpoint, MemberBinding.Mode.RETURNED);
		var record = record();
		if (record.phase() != RETURNED || !record.fingerprint().equals(endpoint.capture().fingerprint())) throw new IllegalStateException("Returned assets changed");
	}
	private void quarantine(OwnershipEndpoint endpoint, RuntimeException error) {
		step = Step.RECOVERY; failure = error.toString(); returnWrite = null;
		try {
			var record = authority.checkpoint().ownedMachines().get(claim.member());
			if (record != null && record.claim().equals(claim) && record.phase() != RETURNED && record.phase() != RECOVERY) publish(record.quarantine(failure));
		} catch (RuntimeException secondary) { error.addSuppressed(secondary); }
		try { endpoint.quarantine(failure); } catch (RuntimeException secondary) { error.addSuppressed(secondary); }
		com.mojang.logging.LogUtils.getLogger().error("Bee member {} transfer {} quarantined", claim.member(), claim.transfer(), error);
	}
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Ownership transfer belongs to the server thread"); }
}
