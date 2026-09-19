package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import java.util.Objects;

/** RETURNED 只留收据，不能再发放资产；不确定的源／目标保持 RECOVERY。 */
public record OwnedMachineRecord(MemberClaim claim, Phase phase, AssetImage assets, String fingerprint, String failure) {
	public enum Phase { SEALED, OWNED, RETURNING, RETURNED, RECOVERY }
	public OwnedMachineRecord {
		Objects.requireNonNull(claim); Objects.requireNonNull(phase); Objects.requireNonNull(assets); Objects.requireNonNull(failure);
		if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid asset fingerprint");
		if (phase == Phase.RETURNED ? !assets.isEmpty() : assets.isEmpty() || !assets.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Asset state differs from receipt");
		if ((phase == Phase.RECOVERY) == failure.isBlank()) throw new IllegalArgumentException("Recovery requires a reason; normal phases cannot carry failure");
	}
	public OwnedMachineRecord phase(Phase next) {
		if (!canAdvance(phase, next)) throw new IllegalStateException("Invalid ownership transition: " + phase + " -> " + next);
		return new OwnedMachineRecord(claim, next, next == Phase.RETURNED ? AssetImage.EMPTY : assets, fingerprint, "");
	}
	public OwnedMachineRecord quarantine(String reason) {
		if (phase == Phase.RETURNED) throw new IllegalStateException("A completed receipt cannot issue assets again");
		return new OwnedMachineRecord(claim, Phase.RECOVERY, assets, fingerprint, reason);
	}
	static boolean canAdvance(Phase from, Phase to) {
		return from == Phase.SEALED && to == Phase.OWNED || from == Phase.OWNED && to == Phase.RETURNING
				|| from == Phase.RETURNING && to == Phase.RETURNED || from != Phase.RETURNED && to == Phase.RECOVERY;
	}
	void validateSuccessor(OwnedMachineRecord next) {
		if (!claim.equals(next.claim) || !fingerprint.equals(next.fingerprint) || !canAdvance(phase, next.phase)
				|| next.phase != Phase.RETURNED && !assets.equals(next.assets)) throw new IllegalArgumentException("Ownership receipt changed or replayed");
	}
}
