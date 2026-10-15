package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import java.util.Objects;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeMemberState;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeAssetProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingAssetProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeAssetProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeWorkState;

/** RETURNED 只留收据，不能再发放资产；不确定的源／目标保持 RECOVERY。 */
public record OwnedMachineRecord(MemberClaim claim, Phase phase, AssetImage assets, String fingerprint, String failure, BeeMemberState bees, CentrifugeWorkState centrifuge, com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork combined) {
	public OwnedMachineRecord(MemberClaim claim, Phase phase, AssetImage assets, String fingerprint, String failure, BeeMemberState bees, CentrifugeWorkState centrifuge) {
		this(claim, phase, assets, fingerprint, failure, bees, centrifuge, null);
	}
	public enum Phase { SEALED, OWNED, RETURNING, RETURNED, RECOVERY }
	public OwnedMachineRecord(MemberClaim claim, Phase phase, AssetImage assets, String fingerprint, String failure) {
		this(claim, phase, assets, fingerprint, failure, null);
	}
	public OwnedMachineRecord(MemberClaim claim, Phase phase, AssetImage assets, String fingerprint, String failure, BeeMemberState bees) {
		this(claim, phase, assets, fingerprint, failure, bees, null);
	}
	public OwnedMachineRecord {
		Objects.requireNonNull(claim); Objects.requireNonNull(phase); Objects.requireNonNull(assets); Objects.requireNonNull(failure);
		if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid asset fingerprint");
		if (phase == Phase.RETURNED ? !assets.isEmpty() : assets.isEmpty() || !assets.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Asset state differs from receipt");
		if ((phase == Phase.RECOVERY) == failure.isBlank()) throw new IllegalArgumentException("Recovery requires a reason; normal phases cannot carry failure");
		if (combined != null) {
			if (bees != null || centrifuge != null || phase != Phase.OWNED && phase != Phase.RECOVERY) throw new IllegalArgumentException("Conflicting combined authority");
			com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.validate(claim, assets, combined);
		} else if (assets.copy().contains(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.MARKER)) throw new IllegalArgumentException("Missing combined authority");
		if (bees != null) {
			var layout = com.ayoshiko.productivebeesgenesis.apiculture.capacity.ApiaryWorkLayout.forMachine(claim.machine());
			if (phase != Phase.OWNED && phase != Phase.RECOVERY || !claim.member().equals(bees.member())
					|| layout == null || bees.slotCount() != layout.bees()
					|| bees.feeding() != null && bees.feeding().legacySlots() != layout.feeders()) throw new IllegalArgumentException("Bee authority differs from owned apiary layout");
			BeeAssetProjection.validate(assets, bees);
		} else if (assets.copy().contains(BeeAssetProjection.MARKER) || assets.copy().contains(FeedingAssetProjection.MARKER)) throw new IllegalArgumentException("Missing detached bee authority");
		if (centrifuge != null) {
			if (bees != null || phase != Phase.OWNED && phase != Phase.RECOVERY || !claim.member().equals(centrifuge.member())
					|| centrifuge.laneCount() != com.ayoshiko.productivebeesgenesis.apiculture.capacity.CentrifugeWorkLayout.lanes(claim.machine()))
				throw new IllegalArgumentException("Centrifuge authority differs from owned layout");
			CentrifugeAssetProjection.validate(assets, centrifuge);
		} else if (assets.copy().contains(CentrifugeAssetProjection.MARKER)) throw new IllegalArgumentException("Missing detached centrifuge authority");
	}
	/** 当前载体的工作布局；不授予新类型接管资格，复合适配接入时在本边界扩展。 */
	public com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberWorkLayout workLayout() {
		if (claim.machine().equals(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.MACHINE))
			return new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberWorkLayout(6, 3);
		var apiary = com.ayoshiko.productivebeesgenesis.apiculture.capacity.ApiaryWorkLayout.forMachine(claim.machine());
		int hiveSlots = apiary == null ? 0 : apiary.bees();
		int processSlots = com.ayoshiko.productivebeesgenesis.apiculture.capacity.CentrifugeWorkLayout.lanes(claim.machine());
		return new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberWorkLayout(hiveSlots, processSlots);
	}
	public CentrifugeWorkState centrifugeState() { return combined == null ? centrifuge : combined.centrifugeView(); }
	/** 统一管理只读视图；不构造第二份可写蜂箱状态。 */
	public boolean beeReady() { return bees != null || combined != null; }
	public java.util.List<com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord> beeRecords() {
		return combined != null ? combined.bees() : bees == null ? java.util.List.of() : bees.bees();
	}
	public java.util.List<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore.Slot> feedingSlots() {
		return combined != null ? combined.feeding() : bees == null || bees.feeding() == null ? java.util.List.of() : bees.feeding().slots();
	}
	public long beeRevision() { return combined != null ? combined.revision() : bees == null ? -1 : bees.revision(); }
	public long feedingRevision() { return combined != null ? combined.feedingRevision() : bees == null || bees.feeding() == null ? -1 : bees.feeding().revision(); }
	public Object rosterVersion() { return combined != null ? combined.rosterVersion() : bees == null ? null : bees.rosterVersion(); }
	public Object upgradeVersion() { return combined != null ? combined.upgrades() : assets; }
	public OwnedMachineRecord withCentrifuge(CentrifugeWorkState state) {
		if (phase != Phase.OWNED) throw new IllegalStateException("Member is not owned");
		if (centrifuge == null) CentrifugeAssetProjection.validateMigration(assets, state); else centrifuge.validateSuccessor(state);
		var residual = centrifuge == null ? CentrifugeAssetProjection.detach(assets) : assets;
		return new OwnedMachineRecord(claim, phase, residual, residual.fingerprint(), "", null, state);
	}
	public OwnedMachineRecord withBees(BeeMemberState state) {
		if (phase != Phase.OWNED) throw new IllegalStateException("Member is not owned");
		if (bees == null) BeeAssetProjection.validateMigration(assets, state); else bees.validateSuccessor(state);
		var residual = residualFor(state);
		return new OwnedMachineRecord(claim, phase, residual, residual.fingerprint(), "", state);
	}
	/** 单蜂进出使用专门的原根凭据，不放宽普通生产后继的固定名册约束。 */
	OwnedMachineRecord exchangeBee(com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRosterChange change) {
		if (phase != Phase.OWNED || bees == null || !change.matches(bees)) {
			throw new IllegalArgumentException("Stale or unavailable bee roster exchange");
		}
		return new OwnedMachineRecord(claim, phase, assets, fingerprint, "", change.candidate());
	}
	private AssetImage residualFor(BeeMemberState state) {
		var residual = bees == null ? BeeAssetProjection.detach(assets) : assets;
		if (state.feeding() != null && (bees == null || bees.feeding() == null)) residual = FeedingAssetProjection.detach(residual, state.feeding());
		return residual;
	}
	public OwnedMachineRecord withCombined(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork state) {
		if (phase != Phase.OWNED) throw new IllegalStateException("Member is not owned");
		var residual = com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.detach(state);
		return new OwnedMachineRecord(claim, phase, residual, residual.fingerprint(), "", null, null, state);
	}
	public AssetImage returnImage() {
		if (combined != null) {
			if (!combined.drained()) throw new IllegalStateException("Settle combined paid work before return");
			return com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.capture(combined);
		}
		if (centrifuge != null) return CentrifugeAssetProjection.attach(assets, centrifuge);
		return bees == null ? assets : FeedingAssetProjection.attach(BeeAssetProjection.attach(assets, bees), bees.feeding());
	}
	public OwnedMachineRecord phase(Phase next) {
		if (!canAdvance(phase, next)) throw new IllegalStateException("Invalid ownership transition: " + phase + " -> " + next);
		var image = next == Phase.RETURNING ? returnImage() : assets;
		return new OwnedMachineRecord(claim, next, next == Phase.RETURNED ? AssetImage.EMPTY : image, image.isEmpty() ? fingerprint : image.fingerprint(), "");
	}
	public OwnedMachineRecord quarantine(String reason) {
		if (phase == Phase.RETURNED) throw new IllegalStateException("A completed receipt cannot issue assets again");
		return new OwnedMachineRecord(claim, Phase.RECOVERY, assets, fingerprint, reason, bees, centrifuge, combined);
	}
	static boolean canAdvance(Phase from, Phase to) {
		return from == Phase.SEALED && to == Phase.OWNED || from == Phase.OWNED && to == Phase.RETURNING
				|| from == Phase.RETURNING && to == Phase.RETURNED || from != Phase.RETURNED && to == Phase.RECOVERY;
	}
	void validateSuccessor(OwnedMachineRecord next) {
		if (phase == Phase.OWNED && claim.equals(next.claim) && next.combined != null && next.phase == Phase.OWNED) {
			if (combined != null) {
				if (!assets.equals(next.assets) || combined.generation() != next.combined.generation()
						|| next.combined.revision() != Math.incrementExact(combined.revision())) throw new IllegalArgumentException("Stale combined authority");
			} else {
				var expected = assets.copy();
				if (!expected.getAllKeys().equals(java.util.Set.of("combinedWork"))) throw new IllegalArgumentException("Missing combined source");
				var source = expected.getCompound("combinedWork"); source.putLong("energy", 0);
				source.putLong("revision", Math.incrementExact(com.ayoshiko.productivebeesgenesis.apiculture.persistence.StrictNbt.number(source, "revision")));
				if (!expected.equals(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.capture(next.combined).copy()))
					throw new IllegalArgumentException("Combined source assets changed during activation");
			}
			return;
		}
		if (phase == Phase.OWNED && combined != null && next.phase == Phase.RETURNING && claim.equals(next.claim)) {
			if (!returnImage().equals(next.assets) || next.combined != null) throw new IllegalArgumentException("Incomplete combined return");
			return;
		}
		if (phase == Phase.OWNED && next.phase == Phase.OWNED && claim.equals(next.claim) && next.centrifuge != null) {
			var residual = centrifuge == null ? CentrifugeAssetProjection.detach(assets) : assets;
			if (!residual.equals(next.assets)) throw new IllegalArgumentException("Centrifuge assets changed within work");
			if (centrifuge == null) CentrifugeAssetProjection.validateMigration(assets, next.centrifuge); else centrifuge.validateSuccessor(next.centrifuge);
			return;
		}
		if (phase == Phase.OWNED && next.phase == Phase.RETURNING && centrifuge != null && claim.equals(next.claim)) {
			if (!returnImage().equals(next.assets) || next.centrifuge != null) throw new IllegalArgumentException("Incomplete centrifuge return");
			return;
		}
		if (phase == Phase.OWNED && next.phase == Phase.OWNED && claim.equals(next.claim) && next.bees != null) {
			if ((bees == null ? next.bees.revision() != 0 : next.bees.revision() != Math.incrementExact(bees.revision()))
					|| !residualFor(next.bees).equals(next.assets)) throw new IllegalArgumentException("Stale bee authority update");
			if (bees == null) BeeAssetProjection.validateMigration(assets, next.bees); else bees.validateSuccessor(next.bees);
			return;
		}
		if (phase == Phase.OWNED && next.phase == Phase.RETURNING && bees != null && claim.equals(next.claim)) {
			if (!returnImage().equals(next.assets) || next.bees != null) throw new IllegalArgumentException("Incomplete bee return");
			return;
		}
		if (!claim.equals(next.claim) || !fingerprint.equals(next.fingerprint) || !canAdvance(phase, next.phase)
				|| next.phase != Phase.RETURNED && !assets.equals(next.assets) || !Objects.equals(bees, next.bees)
				|| !Objects.equals(centrifuge, next.centrifuge) || combined != next.combined) throw new IllegalArgumentException("Ownership receipt changed or replayed");
	}
}
