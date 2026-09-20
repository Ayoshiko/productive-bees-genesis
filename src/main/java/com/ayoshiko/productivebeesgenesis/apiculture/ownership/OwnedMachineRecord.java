package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import java.util.Objects;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeMemberState;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeAssetProjection;

/** RETURNED 只留收据，不能再发放资产；不确定的源／目标保持 RECOVERY。 */
public record OwnedMachineRecord(MemberClaim claim, Phase phase, AssetImage assets, String fingerprint, String failure, BeeMemberState bees) {
	public enum Phase { SEALED, OWNED, RETURNING, RETURNED, RECOVERY }
	public OwnedMachineRecord(MemberClaim claim, Phase phase, AssetImage assets, String fingerprint, String failure) {
		this(claim, phase, assets, fingerprint, failure, null);
	}
	public OwnedMachineRecord {
		Objects.requireNonNull(claim); Objects.requireNonNull(phase); Objects.requireNonNull(assets); Objects.requireNonNull(failure);
		if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid asset fingerprint");
		if (phase == Phase.RETURNED ? !assets.isEmpty() : assets.isEmpty() || !assets.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Asset state differs from receipt");
		if ((phase == Phase.RECOVERY) == failure.isBlank()) throw new IllegalArgumentException("Recovery requires a reason; normal phases cannot carry failure");
		if (bees != null) {
			if (phase != Phase.OWNED && phase != Phase.RECOVERY || !claim.member().equals(bees.member())
					|| !claim.machine().equals("productivebeesgenesis:mek_apiary")) throw new IllegalArgumentException("Bee authority outside owned basic apiary");
			BeeAssetProjection.validate(assets, bees);
		} else if (assets.copy().contains(BeeAssetProjection.MARKER)) throw new IllegalArgumentException("Missing detached bee authority");
	}
	public OwnedMachineRecord withBees(BeeMemberState state) {
		if (phase != Phase.OWNED) throw new IllegalStateException("Member is not owned");
		if (bees == null) BeeAssetProjection.validateMigration(assets, state); else bees.validateSuccessor(state);
		var residual = bees == null ? BeeAssetProjection.detach(assets) : assets;
		return new OwnedMachineRecord(claim, phase, residual, residual.fingerprint(), "", state);
	}
	public AssetImage returnImage() { return bees == null ? assets : BeeAssetProjection.attach(assets, bees); }
	public OwnedMachineRecord phase(Phase next) {
		if (!canAdvance(phase, next)) throw new IllegalStateException("Invalid ownership transition: " + phase + " -> " + next);
		var image = next == Phase.RETURNING ? returnImage() : assets;
		return new OwnedMachineRecord(claim, next, next == Phase.RETURNED ? AssetImage.EMPTY : image, image.isEmpty() ? fingerprint : image.fingerprint(), "");
	}
	public OwnedMachineRecord quarantine(String reason) {
		if (phase == Phase.RETURNED) throw new IllegalStateException("A completed receipt cannot issue assets again");
		return new OwnedMachineRecord(claim, Phase.RECOVERY, assets, fingerprint, reason, bees);
	}
	static boolean canAdvance(Phase from, Phase to) {
		return from == Phase.SEALED && to == Phase.OWNED || from == Phase.OWNED && to == Phase.RETURNING
				|| from == Phase.RETURNING && to == Phase.RETURNED || from != Phase.RETURNED && to == Phase.RECOVERY;
	}
	void validateSuccessor(OwnedMachineRecord next) {
		if (phase == Phase.OWNED && next.phase == Phase.OWNED && claim.equals(next.claim) && next.bees != null) {
			if (bees == null ? next.bees.revision() != 0 || !BeeAssetProjection.detach(assets).equals(next.assets)
					: next.bees.revision() != Math.incrementExact(bees.revision()) || !assets.equals(next.assets)) throw new IllegalArgumentException("Stale bee authority update");
			if (bees == null) BeeAssetProjection.validateMigration(assets, next.bees); else bees.validateSuccessor(next.bees);
			return;
		}
		if (phase == Phase.OWNED && next.phase == Phase.RETURNING && bees != null && claim.equals(next.claim)) {
			if (!returnImage().equals(next.assets) || next.bees != null) throw new IllegalArgumentException("Incomplete bee return");
			return;
		}
		if (!claim.equals(next.claim) || !fingerprint.equals(next.fingerprint) || !canAdvance(phase, next.phase)
				|| next.phase != Phase.RETURNED && !assets.equals(next.assets) || !Objects.equals(bees, next.bees)) throw new IllegalArgumentException("Ownership receipt changed or replayed");
	}
}
