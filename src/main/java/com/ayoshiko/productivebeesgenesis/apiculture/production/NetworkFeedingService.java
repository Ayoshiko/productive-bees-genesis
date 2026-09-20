package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 服务器内的有限供给事务；来源、目标和蜂位变更一同发布，无外部回调或掉落物。 */
public final class NetworkFeedingService {
	private final NetworkSavedData authority;
	private final NetworkDirectory directory;
	private final NetworkBeeService access;
	public NetworkFeedingService(NetworkSavedData authority, NetworkDirectory directory) {
		this.authority = authority; this.directory = directory; access = new NetworkBeeService(authority, directory);
	}
	public boolean migrate(ServerLevel level, UUID member, long expectedRevision) {
		var current = authority.checkpoint(); var owned = current.ownedMachines().get(member);
		if (owned == null || owned.bees() == null || owned.bees().revision() != expectedRevision || owned.bees().feeding() != null || access.member(level, owned) == null) return false;
		var next = owned.bees().withFeeding(StaticFeedingAdapter.migrate(owned.assets(), level.registryAccess()));
		authority.publish(current.withOwnership(owned.withBees(next))); directory.requestSave(authority); return true;
	}
	public boolean apply(ServerLevel level, UUID member, long expectedFeedingRevision, FeedingSlotStore.Plan plan, boolean simulate) {
		var current = authority.checkpoint(); var owned = current.ownedMachines().get(member);
		if (owned == null || owned.bees() == null || owned.bees().feeding() == null
				|| owned.bees().feeding().revision() != expectedFeedingRevision || access.member(level, owned) == null) return false;
		var next = owned.bees().withFeeding(plan.apply(owned.bees().feeding()));
		if (!simulate && next != owned.bees()) { authority.publish(current.withOwnership(owned.withBees(next))); directory.requestSave(authority); }
		return true;
	}
	public boolean moveBee(ServerLevel level, UUID member, long expectedRevision, int from, int to, boolean withFeeding, boolean simulate) {
		var current = authority.checkpoint(); var owned = current.ownedMachines().get(member);
		if (owned == null || owned.bees() == null || owned.bees().revision() != expectedRevision || access.member(level, owned) == null) return false;
		var next = owned.bees().moveBee(from, to, withFeeding);
		if (!simulate) { authority.publish(current.withOwnership(owned.withBees(next))); directory.requestSave(authority); } return true;
	}
}
