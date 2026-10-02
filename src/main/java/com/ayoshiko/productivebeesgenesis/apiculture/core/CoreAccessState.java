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
	private Set<UUID> upgradeGuests = Set.of();
	private Object sessionToken = new Object();
	private Tag invalidData;
	private String failure = "";

	public boolean allows(UUID player) { return player != null && invalidData == null && guests.contains(player); }
	public boolean allowsUpgrades(UUID player) { return allows(player) && upgradeGuests.contains(player); }
	public boolean valid() { return invalidData == null; }
	public String failure() { return failure; }
	public List<UUID> guests() { return guests.stream().sorted().toList(); }
	public List<UUID> upgradeGuests() { return upgradeGuests.stream().sorted().toList(); }
	Object sessionToken() { return sessionToken; }

	Change change(UUID owner, UUID target, boolean grant) {
		if (!valid() || target == null || target.equals(owner) || target.equals(new UUID(0, 0))) return Change.INVALID;
		if (guests.contains(target) == grant) return Change.UNCHANGED;
		if (grant && guests.size() >= MAX_GUESTS) return Change.FULL;
		var next = new HashSet<>(guests);
		if (grant) next.add(target); else next.remove(target);
		guests = Set.copyOf(next);
		if (!grant && upgradeGuests.contains(target)) {
			var upgrades = new HashSet<>(upgradeGuests); upgrades.remove(target); upgradeGuests = Set.copyOf(upgrades);
		}
		sessionToken = new Object(); return Change.CHANGED;
	}

	/** 升级权必须依附已存在的访客身份；不会隐式增加访问或结构管理权限。 */
	Change changeUpgrades(UUID owner, UUID target, boolean grant) {
		if (!valid() || target == null || target.equals(owner) || !guests.contains(target)) return Change.INVALID;
		if (upgradeGuests.contains(target) == grant) return Change.UNCHANGED;
		var next = new HashSet<>(upgradeGuests);
		if (grant) next.add(target); else next.remove(target);
		upgradeGuests = Set.copyOf(next); sessionToken = new Object(); return Change.CHANGED;
	}

	Tag save(UUID owner, UUID controller) {
		if (invalidData != null) return invalidData.copy();
		if (guests.isEmpty()) return null;
		var tag = new CompoundTag();
		tag.putInt("version", 2); tag.putUUID("owner", owner); tag.putUUID("controller", controller);
		tag.put("guests", uuidList(guests())); tag.put("upgrades", uuidList(upgradeGuests())); return tag;
	}
	private static ListTag uuidList(List<UUID> values) {
		var list = new ListTag(); for (var value : values) list.add(NbtUtils.createUUID(value)); return list;
	}
	private static Set<UUID> readUuids(Tag raw, UUID owner) {
		if (!(raw instanceof ListTag list) || list.size() > MAX_GUESTS || !list.isEmpty() && list.getElementType() != Tag.TAG_INT_ARRAY)
			throw new IllegalArgumentException("Invalid access UUID list");
		var result = new HashSet<UUID>();
		for (var value : list) {
			var id = NbtUtils.loadUUID(value);
			if (id.equals(owner) || id.equals(new UUID(0, 0)) || !result.add(id))
				throw new IllegalArgumentException("Invalid or duplicate access UUID");
		}
		return Set.copyOf(result);
	}

	static CoreAccessState read(Tag raw, UUID owner, UUID controller) {
		var state = new CoreAccessState(); if (raw == null) return state;
		try {
			if (!(raw instanceof CompoundTag tag) || !tag.contains("version", Tag.TAG_INT)
					|| tag.getInt("version") < 1 || tag.getInt("version") > 2
					|| !tag.getAllKeys().equals(tag.getInt("version") == 1
							? Set.of("version", "owner", "controller", "guests") : Set.of("version", "owner", "controller", "guests", "upgrades"))
					|| !tag.hasUUID("owner") || !tag.getUUID("owner").equals(owner)
					|| !tag.hasUUID("controller") || !tag.getUUID("controller").equals(controller))
				throw new IllegalArgumentException("Invalid access schema or core identity");
			var guests = readUuids(tag.get("guests"), owner);
			var upgrades = tag.getInt("version") == 1 ? Set.<UUID>of() : readUuids(tag.get("upgrades"), owner);
			if (!guests.containsAll(upgrades)) throw new IllegalArgumentException("Upgrade grant without guest access");
			state.guests = guests; state.upgradeGuests = upgrades;
		} catch (RuntimeException failure) {
			state.invalidData = raw.copy(); state.failure = failure.toString();
		}
		return state;
	}
}
