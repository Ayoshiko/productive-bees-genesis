package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** 世界目录的暂停索引；不是资产副本，旧 BE 即使没有绑定标签也受此索引约束。 */
public record MemberClaim(UUID network, UUID member, UUID transfer, Origin origin, String machine) {
	public MemberClaim {
		Objects.requireNonNull(network); Objects.requireNonNull(member); Objects.requireNonNull(transfer); Objects.requireNonNull(origin);
		if (!ResourceLocation.parse(machine).getNamespace().equals("productivebeesgenesis")) throw new IllegalArgumentException("Unsupported member namespace");
	}
}
