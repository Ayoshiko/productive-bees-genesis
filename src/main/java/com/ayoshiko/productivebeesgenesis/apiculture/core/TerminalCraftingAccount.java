package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.StrictNbt;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** 每个终端位置的有限保管账户；BE 和菜单只持引用，拆除后原位重建可取回。 */
public final class TerminalCraftingAccount extends SavedData {
	public record State(long revision, List<ItemStack> grid, ItemStack pending, boolean uncertain) {
		public State {
			if (revision < 0 || grid.size() != 9 || uncertain && pending.isEmpty()) throw new IllegalArgumentException("Invalid crafting state");
			grid = List.copyOf(TerminalCraftingPlan.copy(grid)); pending = pending.copy();
			for (var stack : grid) if (!stack.isEmpty() && (stack.getCount() < 1 || stack.getCount() > Math.min(64, stack.getMaxStackSize())))
				throw new IllegalArgumentException("Invalid crafting stack size");
		}
		@Override public List<ItemStack> grid() { return List.copyOf(TerminalCraftingPlan.copy(grid)); }
		@Override public ItemStack pending() { return pending.copy(); }
	}
	private final Thread thread = Thread.currentThread();
	private UUID owner;
	private final ResourceLocation dimension;
	private final BlockPos position;
	private final UUID device;
	private final CompoundTag quarantined;
	private State state;
	private boolean persisted, busy;
	private String failure = "";
	private Path boundPath;
	private TerminalCraftingAccount(UUID owner, ResourceLocation dimension, BlockPos position) {
		this(owner, dimension, position, null);
	}
	private TerminalCraftingAccount(UUID owner, ResourceLocation dimension, BlockPos position, UUID device) {
		this.owner = Objects.requireNonNull(owner); this.dimension = dimension; this.position = position == null ? null : position.immutable(); this.device = device; quarantined = null;
		state = new State(0, Collections.nCopies(9, ItemStack.EMPTY), ItemStack.EMPTY, false); setDirty();
	}
	private TerminalCraftingAccount(CompoundTag raw, RuntimeException failure) {
		owner = null; dimension = null; position = null; device = null; quarantined = raw.copy(); this.failure = failure.toString();
	}
	public static String name(ResourceLocation dimension, BlockPos pos) {
		return "pbg_terminal_crafting_" + UUID.nameUUIDFromBytes((dimension + "/" + pos.asLong()).getBytes(StandardCharsets.UTF_8));
	}
	static TerminalCraftingAccount attach(NetworkTerminalBlockEntity terminal, NetworkCoreBlockEntity core) {
		var level = (ServerLevel) terminal.getLevel(); var server = level.getServer();
		if (!server.isSameThread()) throw new IllegalStateException("Crafting accounts belong to the server thread");
		String name = name(level.dimension().location(), terminal.getBlockPos());
		var storage = server.overworld().getDataStorage();
		var file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
		var factory = new SavedData.Factory<TerminalCraftingAccount>(() -> { throw new IllegalStateException("Explicit account creation required"); }, TerminalCraftingAccount::load, null);
		var account = storage.get(factory, name);
		if (account == null) {
			if (terminal.craftingReferenced() || !Files.notExists(file)) throw new IllegalStateException("Missing or unreadable crafting account: " + name);
			account = new TerminalCraftingAccount(core.owner(), level.dimension().location(), terminal.getBlockPos()); storage.set(name, account);
			account.save(file.toFile(), level.registryAccess());
		}
		if (account.available() && !account.owner.equals(core.owner()) && !account.busy
				&& account.state.pending().isEmpty() && account.state.grid().stream().allMatch(ItemStack::isEmpty)) {
			account.owner = core.owner(); account.publish(account.state, account.state.grid(), ItemStack.EMPTY, false);
		}
		if (!account.matches(core.owner(), level.dimension().location(), terminal.getBlockPos()) || !account.available())
			throw new IllegalStateException("Crafting account owner/location mismatch or unavailable: " + name + " " + account.failure);
		terminal.referenceCrafting(); return account;
	}
	public boolean matches(UUID owner, ResourceLocation dimension, BlockPos pos) {
		check(); return quarantined == null && device == null && this.owner.equals(owner) && this.dimension.equals(dimension) && position.equals(pos);
	}
	public static String wirelessName(UUID device) { return "pbg_wireless_crafting_" + device; }
	/** 已有设备缺失文件时拒绝；只有首次绑定新 UUID 可以建立空账户。 */
	public static TerminalCraftingAccount wireless(net.minecraft.server.level.ServerPlayer player, UUID device, UUID owner, boolean create) {
		if (!player.server.isSameThread()) throw new IllegalStateException("Crafting accounts belong to the server thread");
		var name = wirelessName(device); var storage = player.server.overworld().getDataStorage();
		var factory = new SavedData.Factory<TerminalCraftingAccount>(() -> { throw new IllegalStateException("Explicit account creation required"); }, TerminalCraftingAccount::load, null);
		var account = storage.get(factory, name);
		if (account == null) {
			var file = player.server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
			if (!create || !Files.notExists(file)) throw new IllegalStateException("Missing or unreadable wireless account: " + name);
			account = new TerminalCraftingAccount(owner, null, null, device); storage.set(name, account); account.save(file.toFile(), player.registryAccess());
		}
		if (!account.available() || !device.equals(account.device) || !owner.equals(account.owner)) return null;
		return account;
	}
	public boolean available() { check(); return persisted && quarantined == null && failure.isEmpty(); }
	public State state() { check(); return state; }
	boolean busy() { check(); return busy; }
	void busy(boolean value) { check(); busy = value; }
	void publish(State expected, List<ItemStack> grid, ItemStack pending, boolean uncertain) {
		publishPrepared(expected, prepare(expected, grid, pending, uncertain));
	}
	State prepare(State expected, List<ItemStack> grid, ItemStack pending, boolean uncertain) {
		check(); if (!available() || state != expected) throw new IllegalStateException("Stale crafting account");
		return new State(Math.incrementExact(state.revision()), grid, pending, uncertain);
	}
	void publishPrepared(State expected, State prepared) {
		check(); if (!available() || state != expected || prepared.revision() != expected.revision() + 1) throw new IllegalStateException("Stale crafting account");
		state = prepared; setDirty();
	}
	public static TerminalCraftingAccount load(CompoundTag tag, HolderLookup.Provider registries) {
		try {
			int schema = StrictNbt.integer(tag, "schema");
			if (schema != 1 && schema != 2 || !tag.getAllKeys().equals(schema == 1
					? Set.of("schema", "owner", "dimension", "position", "revision", "grid", "pending", "uncertain")
					: Set.of("schema", "owner", "device", "revision", "grid", "pending", "uncertain"))) throw new IllegalArgumentException("Invalid crafting account schema");
			var result = schema == 1 ? new TerminalCraftingAccount(StrictNbt.uuid(tag, "owner"), ResourceLocation.parse(StrictNbt.string(tag, "dimension")), BlockPos.of(StrictNbt.number(tag, "position")))
					: new TerminalCraftingAccount(StrictNbt.uuid(tag, "owner"), null, null, StrictNbt.uuid(tag, "device"));
			var list = StrictNbt.list(tag, "grid"); if (list.size() != 9) throw new IllegalArgumentException("Invalid crafting grid length");
			var grid = new ArrayList<ItemStack>(9); for (var raw : list) grid.add(stack((CompoundTag) raw, registries));
			result.state = new State(StrictNbt.number(tag, "revision"), grid, stack(StrictNbt.compound(tag, "pending"), registries), StrictNbt.bool(tag, "uncertain"));
			result.persisted = true; result.setDirty(false); return result;
		} catch (RuntimeException failure) { return new TerminalCraftingAccount(tag, failure); }
	}
	private static ItemStack stack(CompoundTag tag, HolderLookup.Provider registries) {
		if (tag.isEmpty()) return ItemStack.EMPTY;
		var stack = ItemStack.parse(registries, tag).orElseThrow(() -> new IllegalArgumentException("Invalid crafting item"));
		if (!stack.save(registries).equals(tag)) throw new IllegalArgumentException("Lossy crafting item decode"); return stack;
	}
	@Override public CompoundTag save(CompoundTag ignored, HolderLookup.Provider registries) {
		check(); if (quarantined != null) return quarantined.copy();
		var tag = new CompoundTag(); tag.putInt("schema", device == null ? 1 : 2); tag.putUUID("owner", owner);
		if (device == null) { tag.putString("dimension", dimension.toString()); tag.putLong("position", position.asLong()); } else tag.putUUID("device", device);
		tag.putLong("revision", state.revision()); tag.putBoolean("uncertain", state.uncertain());
		var grid = new ListTag(); state.grid().forEach(stack -> grid.add(stack.saveOptional(registries))); tag.put("grid", grid);
		tag.put("pending", state.pending().saveOptional(registries)); return tag;
	}
	@Override public void save(File file, HolderLookup.Provider registries) {
		check(); if (quarantined != null || !isDirty()) return;
		var target = file.toPath().toAbsolutePath().normalize();
		if (boundPath != null && !boundPath.equals(target)) throw new IllegalStateException("Crafting account path changed"); boundPath = target;
		try {
			var root = new CompoundTag(); NbtUtils.addCurrentDataVersion(root); root.put("data", save(new CompoundTag(), registries));
			Files.createDirectories(target.getParent()); var temporary = target.resolveSibling(target.getFileName() + ".pbg-pending");
			NbtIo.writeCompressed(root, temporary);
			try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			persisted = true; failure = ""; setDirty(false);
		} catch (java.io.IOException | RuntimeException error) {
			if (failure.isEmpty()) com.mojang.logging.LogUtils.getLogger().error("Crafting account retained after save failure: {}", target, error);
			failure = error.toString(); setDirty();
		}
	}
	private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Crafting accounts belong to the server thread"); }
}
