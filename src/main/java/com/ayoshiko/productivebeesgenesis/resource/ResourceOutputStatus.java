package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/** 有权限的附近观察者最多获得四项余额；不会同步账户标识、未决事务或故障原文。 */
public record ResourceOutputStatus(State state, boolean meEnabled, int totalKinds, java.util.List<ResourceStack> balances) {
	public enum State { UNBOUND, UNAVAILABLE, PRIVATE, READY, PAUSED, MISSING_RECIPE,
		MISSING_OWNER, BINDING_UNAVAILABLE, ME_OFFLINE, ME_MISSING_BRIDGE, ME_FULL, ME_DISABLED, PARTIAL }
	public static final int MAX_ROWS = 4;
	public ResourceOutputStatus {
		java.util.Objects.requireNonNull(state);
		balances = java.util.List.copyOf(balances);
		if (totalKinds < balances.size() || balances.size() > MAX_ROWS) throw new IllegalArgumentException("Invalid resource summary");
	}
	/** 机器菜单和 Jade 共用只读快照，所有者与 Mekanism 权限均在服务端检查。 */
	public static ResourceOutputStatus capture(net.minecraft.world.level.block.entity.BlockEntity host, ServerPlayer player) {
		return capture(host, player, 0);
	}
	public static ResourceOutputStatus capture(net.minecraft.world.level.block.entity.BlockEntity host, ServerPlayer player, int page) {
		if (host.getLevel() != player.serverLevel() || host.isRemoved()
				|| player.serverLevel().getBlockEntity(host.getBlockPos()) != host
				|| player.isSpectator() || !player.canInteractWithBlock(host.getBlockPos(), 4)
				|| !ResourceHiveHost.canAccess(player, host)) return empty(State.PRIVATE, false);
		var source = ResourceHiveHost.existing(host);
		if (source == null) return empty(State.UNBOUND, true);
		if (source.reference() == null) return empty(switch (source.status()) {
			case "missing_owner" -> State.MISSING_OWNER;
			case "binding_unavailable" -> State.BINDING_UNAVAILABLE;
			default -> State.UNAVAILABLE;
		}, source.meOutputEnabled());
		try {
			var account = source.account(host);
			if (!account.owner().equals(player.getUUID()) && !player.hasPermissions(2)) return empty(State.PRIVATE, false);
			var summary = summarize(account, source.meOutputEnabled(), page);
			if (summary.state() != State.READY) return summary;
			State state = "missing_pb_recipe".equals(source.status()) ? State.MISSING_RECIPE : switch (source.meStatus()) {
				case "offline" -> State.ME_OFFLINE;
				case "missing_bridge" -> State.ME_MISSING_BRIDGE;
				case "full" -> State.ME_FULL;
				case "disabled" -> State.ME_DISABLED;
				default -> State.READY;
			};
			return new ResourceOutputStatus(state, summary.meEnabled(), summary.totalKinds(), summary.balances());
		} catch (IllegalStateException invalid) { return empty(State.UNAVAILABLE, source.meOutputEnabled()); }
	}
	static ResourceOutputStatus summarize(ResourceAccount account, boolean enabled) {
		return summarize(account, enabled, 0);
	}
	static ResourceOutputStatus summarize(ResourceAccount account, boolean enabled, int page) {
		var available = account.availableExact();
		// 菜单只取权限／状态头，目录独立增量同步；Jade 的有界摘要才需要排序。
		var rows = page < 0 ? java.util.List.<ResourceStack>of() : available.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
				.skip((long) Math.max(0, page) * MAX_ROWS).limit(MAX_ROWS).map(entry -> new ResourceStack(entry.getKey(), entry.getValue())).toList();
		State state = State.PAUSED;
		if (account.canExtract(account.reference())) state = account.hasIsolatedRequests() ? State.PARTIAL : State.READY;
		return new ResourceOutputStatus(state, enabled, available.size(), rows);
	}
	private static ResourceOutputStatus empty(State state, boolean enabled) { return new ResourceOutputStatus(state, enabled, 0, java.util.List.of()); }
	public CompoundTag save() {
		var data = new CompoundTag();
		data.putInt("state", state.ordinal()); data.putBoolean("me", meEnabled); data.putInt("kinds", totalKinds);
		var rows = new ListTag();
		for (var balance : balances) {
			var row = new CompoundTag(); row.put("key", balance.key().save()); ResourceExactAmounts.write(row, "amount", balance.key(), balance.exactAmount()); rows.add(row);
		}
		data.put("balances", rows);
		return data;
	}
	public static ResourceOutputStatus load(CompoundTag tag) {
		int state = tag.getInt("state");
		if (state < 0 || state >= State.values().length) throw new IllegalArgumentException("Unknown resource state");
		var rows = tag.getList("balances", Tag.TAG_COMPOUND);
		if (rows.size() > MAX_ROWS || tag.sizeInBytes() > 8192) throw new IllegalArgumentException("Resource summary exceeds budget");
		var balances = new java.util.ArrayList<ResourceStack>();
		for (var row : rows) {
			var entry = (CompoundTag) row;
			var key = ResourceKey.load(entry.getCompound("key"));
            balances.add(new ResourceStack(key, ResourceExactAmounts.read(entry, "amount", key)));
		}
		return new ResourceOutputStatus(State.values()[state], tag.getBoolean("me"), tag.getInt("kinds"), balances);
	}
}
