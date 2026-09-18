package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import java.util.Objects;
import java.util.UUID;

/** 坐标只定位核心，身份与代际用于拒绝复制引用和过期绑定。 */
public record NetworkIdentity(UUID networkId, UUID controllerId, UUID ownerId, long generation, Origin origin) {
	public NetworkIdentity {
		Objects.requireNonNull(networkId); Objects.requireNonNull(controllerId); Objects.requireNonNull(ownerId); Objects.requireNonNull(origin);
		if (generation < 0) throw new IllegalArgumentException("Negative network generation");
	}
}
