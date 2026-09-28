package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.*;

/** 核心服务器线程独占的有限访问表；内存代际使撤销后再授权也不能复活旧菜单。 */
public final class CoreAccessState {
	public static final int MAX_GUESTS = 16;
	public enum Change { CHANGED, UNCHANGED, DENIED, FULL, INVALID }
	private Set<UUID> guests = Set.of();
	private Object sessionToken = new Object();
	private Tag invalidData;
	private String failure = "";

	public boolean allows(UUID player) { return player != null && invalidData == null && guests.contains(player); }
	public boolean valid() { return invalidData == null; }
	public String failure() { return failure; }
	public List<UUID> guests() { return guests.stream().sorted().toList(); }
	Object sessionToken() { return sessionToken; }

	Change change(UUID owner, UUID target, boolean grant) {
		if (!valid() || target == null || target.equals(owner) || target.equals(new UUID(0, 0))) return Change.INVALID;
		if (guests.contains(target) == grant) return Change.UNCHANGED;
		if (grant && guests.size() >= MAX_GUESTS) return Change.FULL;
		var next = new HashSet<>(guests);
		if (grant) next.add(target); else next.remove(target);
		guests = Set.copyOf(next); sessionToken = new Object();
		return Change.CHANGED;
	}

	Tag save(UUID owner, UUID controller) {
		if (invalidData != null) return invalidData.copy();
		if (guests.isEmpty()) return null;
		var tag = new CompoundTag();
		tag.putInt("version", 1); tag.putUUID("owner", owner); tag.putUUID("controller", controller);
		var list = new ListTag(); for (var guest : guests()) list.add(NbtUtils.createUUID(guest));
		tag.put("guests", list); return tag;
	}

	static CoreAccessState read(Tag raw, UUID owner, UUID controller) {
		var state = new CoreAccessState();
		if (raw == null) return state;
		try {
			if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("version", "owner", "controller", "guests"))
					|| !tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1
					|| !tag.hasUUID("owner") || !tag.getUUID("owner").equals(owner)
					|| !tag.hasUUID("controller") || !tag.getUUID("controller").equals(controller)
					|| !(tag.get("guests") instanceof ListTag list) || list.size() > MAX_GUESTS
					|| !list.isEmpty() && list.getElementType() != Tag.TAG_INT_ARRAY) {
				throw new IllegalArgumentException("Invalid access schema or core identity");
			}
			var guests = new HashSet<UUID>();
			for (var value : list) {
				var guest = NbtUtils.loadUUID(value);
				if (guest.equals(owner) || guest.equals(new UUID(0, 0)) || !guests.add(guest)) {
					throw new IllegalArgumentException("Invalid or duplicate guest UUID");
				}
			}
			state.guests = Set.copyOf(guests);
		} catch (RuntimeException failure) {
			state.invalidData = raw.copy(); state.failure = failure.toString();
		}
		return state;
	}
}
