package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** 设备物品只携带绑定引用与有限 FE；真实合成材料始终保存在世界账户中。 */
public final class WirelessTerminalItem extends Item {
	public static final int CAPACITY = 200_000;
	static final String KEY = "pbg_wireless";
	private final TerminalScope scope;
	private final boolean combined;
	public record MachineReference(UUID machine, UUID owner, long generation) {
		public MachineReference { Objects.requireNonNull(machine); Objects.requireNonNull(owner); if (generation < 1) throw new IllegalArgumentException("Invalid machine generation"); }
	}
	public record Binding(UUID device, UUID token, ResourceLocation dimension, BlockPos position, NetworkIdentity network, MachineReference machine, TerminalScope mode) {
		public Binding { if ((network == null) == (machine == null) || mode == TerminalScope.ALL) throw new IllegalArgumentException("Invalid wireless target"); }
	}
	public WirelessTerminalItem(TerminalScope scope, boolean combined) {
		super(new Properties().stacksTo(1)); this.scope = scope; this.combined = combined;
	}
	public boolean combined() { return combined; }
	boolean supports(TerminalScope mode) { return mode != TerminalScope.ALL && (combined || mode == scope); }
	static CompoundTag data(ItemStack stack) {
		var root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		if (root.contains(KEY) && !root.contains(KEY, 10)) throw new IllegalArgumentException("Invalid wireless data");
		return root.getCompound(KEY);
	}
	static void data(ItemStack stack, CompoundTag data) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(KEY, data));
	}
	public static int energy(ItemStack stack) {
		try { var data = data(stack); int value = data.contains("energy") ? StrictNbt.integer(data, "energy") : 0;
			return value >= 0 && value <= CAPACITY ? value : -1;
		} catch (RuntimeException invalid) { return -1; }
	}
	static boolean spend(ItemStack stack, int amount) {
		int energy = energy(stack); if (amount < 0 || energy < amount) return false;
		var data = data(stack); data.putInt("energy", energy - amount); data(stack, data); return true;
	}
	public static Binding binding(ItemStack stack) {
		try {
			var data = data(stack); if (!data.contains("binding")) return null;
			var tag = StrictNbt.compound(data, "binding");
			boolean machine = tag.contains("machine");
			if (!tag.getAllKeys().equals(Set.of("device", "token", "dimension", "position", machine ? "machine" : "network", "mode"))) return null;
			var ref = machine ? StrictNbt.compound(tag, "machine") : null;
			return new Binding(StrictNbt.uuid(tag, "device"), StrictNbt.uuid(tag, "token"), ResourceLocation.parse(StrictNbt.string(tag, "dimension")),
					BlockPos.of(StrictNbt.number(tag, "position")), machine ? null : NetworkCheckpointCodec.readIdentity(StrictNbt.compound(tag, "network")),
					machine ? new MachineReference(StrictNbt.uuid(ref, "id"), StrictNbt.uuid(ref, "owner"), StrictNbt.number(ref, "generation")) : null, StrictNbt.choice(tag, "mode", TerminalScope.class));
		} catch (RuntimeException invalid) { return null; }
	}
	private static void binding(ItemStack stack, Binding value) {
		var tag = new CompoundTag(); tag.putUUID("device", value.device()); tag.putUUID("token", value.token());
		tag.putString("dimension", value.dimension().toString()); tag.putLong("position", value.position().asLong());
		if (value.network() != null) tag.put("network", NetworkCheckpointCodec.identity(value.network()));
		else { var ref = new CompoundTag(); ref.putUUID("id", value.machine().machine()); ref.putUUID("owner", value.machine().owner()); ref.putLong("generation", value.machine().generation()); tag.put("machine", ref); }
		tag.putString("mode", value.mode().name());
		var data = data(stack); data.put("binding", tag); data(stack, data);
	}
	static void mode(ItemStack stack, Binding previous, TerminalScope mode) {
		binding(stack, new Binding(previous.device(), UUID.randomUUID(), previous.dimension(), previous.position(), previous.network(), previous.machine(), mode));
	}
	public boolean bind(ServerPlayer player, ItemStack stack, NetworkCoreBlockEntity core) {
		if (!ModConfig.SERVER.beeNetwork.enabled.get() || stack.getItem() != this || stack.getCount() != 1 || !core.allowed(player)
				|| !core.validNetworkReference() || core.ownership().readyAuthority() == null || energy(stack) < 0) return false;
		var old = binding(stack);
		try {
			if (data(stack).contains("binding") && old == null) return false;
			UUID device = old == null ? UUID.randomUUID() : old.device();
			var account = TerminalCraftingAccount.wireless(player, device, core.owner(), old == null);
			if (account == null || account.busy()) return false;
			binding(stack, new Binding(device, UUID.randomUUID(), core.getLevel().dimension().location(), core.getBlockPos(), core.network(), null, scope));
			player.getInventory().setChanged(); return true;
		} catch (RuntimeException error) {
			com.mojang.logging.LogUtils.getLogger().warn("Cannot bind wireless terminal for {}", player.getUUID(), error); return false;
		}
	}
	@Override public InteractionResult useOn(UseOnContext context) {
		var player = context.getPlayer(); if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
		var target = context.getLevel().getBlockEntity(context.getClickedPos());
		if (!(target instanceof NetworkCoreBlockEntity) && !(target instanceof com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity)
				&& !(target instanceof com.ayoshiko.productivebeesgenesis.multiblock.world.MachinePartEntity)) return InteractionResult.PASS;
		if (player instanceof ServerPlayer server) {
			boolean bound = target instanceof NetworkCoreBlockEntity core ? bind(server, context.getItemInHand(), core) : bindMachine(server, context.getItemInHand(), target);
			server.displayClientMessage(message(bound ? "bound" : "bind_failed"), true);
		}
		return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
	}
	public boolean bindMachine(ServerPlayer player, ItemStack stack, net.minecraft.world.level.block.entity.BlockEntity target) {
		var core = com.ayoshiko.productivebeesgenesis.multiblock.world.WirelessMachineAccess.resolve(player, target);
		if (core == null || !ModConfig.SERVER.beeNetwork.enabled.get() || stack.getItem() != this || stack.getCount() != 1 || energy(stack) < 0) return false;
		try {
			var old = binding(stack); if (data(stack).contains("binding") && old == null) return false;
			UUID device = old == null ? UUID.randomUUID() : old.device();
			var account = TerminalCraftingAccount.wireless(player, device, core.ownerId(), old == null); if (account == null || account.busy()) return false;
			binding(stack, new Binding(device, UUID.randomUUID(), core.getLevel().dimension().location(), core.getBlockPos(), null,
					new MachineReference(core.machineId(), core.ownerId(), core.generation()), scope)); player.getInventory().setChanged(); return true;
		} catch (RuntimeException error) { com.mojang.logging.LogUtils.getLogger().warn("Cannot bind wireless machine for {}", player.getUUID(), error); return false; }
	}
	@Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		var stack = player.getItemInHand(hand);
		if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && player.getOffhandItem().getItem() instanceof WirelessTerminalItem) {
			if (player instanceof ServerPlayer server) {
				var status = WirelessTerminalMerge.merge(server, false);
				server.displayClientMessage(message("merge." + status.name().toLowerCase(Locale.ROOT)), true);
			}
			return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
		}
		if (player instanceof ServerPlayer server && !WirelessTerminalAccess.open(server, hand)) server.displayClientMessage(message("unavailable"), true);
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
	}
	private static Component message(String key, Object... args) { return Component.translatable("item.productivebeesgenesis.wireless_terminal." + key, args); }
	@Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(message("energy", Math.max(0, energy(stack)), CAPACITY));
		var binding = binding(stack);
		tooltip.add(binding == null ? message("bind_hint") : message("target", binding.dimension(), binding.position().toShortString()));
		tooltip.add(message("range", ModConfig.SERVER.beeNetwork.wirelessRange.get()));
		if (!combined) tooltip.add(message("merge_hint"));
	}
	@Override public boolean isBarVisible(ItemStack stack) { return true; }
	@Override public int getBarWidth(ItemStack stack) { return Math.max(0, energy(stack)) * 13 / CAPACITY; }
	@Override public int getBarColor(ItemStack stack) { return 0xe9b949; }
	public static IEnergyStorage energyStorage(ItemStack stack) {
		return new IEnergyStorage() {
			@Override public int receiveEnergy(int maxReceive, boolean simulate) {
				int stored = energy(stack); if (stored < 0 || stack.getCount() != 1) return 0;
				int accepted = Math.min(Math.max(0, maxReceive), CAPACITY - stored);
				if (!simulate && accepted > 0) { var tag = data(stack); tag.putInt("energy", stored + accepted); data(stack, tag); }
				return accepted;
			}
			@Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
			@Override public int getEnergyStored() { return Math.max(0, energy(stack)); }
			@Override public int getMaxEnergyStored() { return CAPACITY; }
			@Override public boolean canExtract() { return false; }
			@Override public boolean canReceive() { return energy(stack) >= 0; }
		};
	}
}
