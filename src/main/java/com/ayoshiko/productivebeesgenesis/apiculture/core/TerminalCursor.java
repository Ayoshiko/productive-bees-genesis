package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.StrictNbt;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView;
import java.util.Set;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.fluids.FluidStack;

/** 鼠标物品由玩家存档保管；菜单只投影，关闭后无空间的余量不掉落、不进入共享合成账户。 */
public final class TerminalCursor {
	public static final IAttachmentSerializer<Tag, TerminalCursor> SERIALIZER = new IAttachmentSerializer<>() {
		@Override public TerminalCursor read(IAttachmentHolder holder, Tag original, HolderLookup.Provider registries) {
			var result = new TerminalCursor();
			try {
				if (!(original instanceof CompoundTag tag)) throw new IllegalArgumentException("Invalid terminal cursor tag");
				int schema = StrictNbt.integer(tag, "schema");
				if (schema < 1 || schema > 7 || !tag.getAllKeys().equals(schema == 1 ? Set.of("schema", "item") : schema == 2 ? Set.of("schema", "item", "pending", "request")
						: schema == 3 ? Set.of("schema", "item", "pending", "request", "fluid", "fluid_request")
						: schema == 4 ? Set.of("schema", "item", "pending", "request", "fluid", "fluid_request", "energy", "energy_request")
						: schema == 5 ? Set.of("schema", "item", "pending", "request", "fluid", "fluid_request", "energy", "energy_request", "chemical", "chemical_request")
						: Set.of("schema", "item", "pending", "request", "fluid", "fluid_request", "energy", "energy_request", "chemical", "chemical_request", "pattern_buffer")))
					throw new IllegalArgumentException("Unsupported terminal cursor");
				var item = StrictNbt.compound(tag, "item");
				result.stack = item.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(registries, item).orElseThrow();
				if (!result.stack.saveOptional(registries).equals(item)) throw new IllegalArgumentException("Lossy terminal cursor");
				if (schema >= 2) {
					var pending = StrictNbt.compound(tag, "pending");
					result.pending = pending.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(registries, pending).orElseThrow();
					if (!result.pending.saveOptional(registries).equals(pending) || result.pending.getCount() > 64) throw new IllegalArgumentException("Invalid retained receipt");
					var request = StrictNbt.compound(tag, "request");
					if (!request.isEmpty()) {
						if (!request.getAllKeys().equals(schema == 7 ? Set.of("item", "insert", "source", "observed", "observed_count") : Set.of("item", "insert", "source"))) throw new IllegalArgumentException("Invalid cursor request");
						var raw = StrictNbt.compound(request, "item"); var wanted = ItemStack.parse(registries, raw).orElseThrow();
						if (!wanted.save(registries).equals(raw)) throw new IllegalArgumentException("Lossy cursor request");
						TerminalCursorExchange.Observed observed = null;
						if (schema == 7) {
							var evidence = StrictNbt.compound(request, "observed"); var sample = ItemStack.parse(registries, evidence).orElseThrow();
							if (!sample.save(registries).equals(evidence)) throw new IllegalArgumentException("Lossy observed cursor return");
							observed = new TerminalCursorExchange.Observed(sample, StrictNbt.integer(request, "observed_count"));
						}
						result.request = new TerminalCursorExchange.Request(wanted, StrictNbt.bool(request, "insert"), StrictNbt.string(request, "source"), observed);
					}
					if (result.request != null && !result.pending.isEmpty()) throw new IllegalArgumentException("Conflicting cursor receipts");
				}
				if (schema >= 3) {
					result.fluid = readFluid(StrictNbt.compound(tag, "fluid"), registries);
					var request = StrictNbt.compound(tag, "fluid_request");
					if (!request.isEmpty()) {
						if (!request.getAllKeys().equals(Set.of("fluid", "insert", "source"))) throw new IllegalArgumentException("Invalid fluid request");
						result.fluidRequest = new TerminalFluidExchange.Request(readFluid(StrictNbt.compound(request, "fluid"), registries), StrictNbt.bool(request, "insert"), StrictNbt.string(request, "source"));
						if (result.request != null || !result.fluid.isEmpty() && !FluidStack.isSameFluidSameComponents(result.fluid, result.fluidRequest.fluid())) throw new IllegalArgumentException("Conflicting fluid requests");
					}
				}
				if (schema >= 4) {
					result.energy = StrictNbt.integer(tag, "energy");
					if (result.energy < 0) throw new IllegalArgumentException("Invalid retained FE");
					var request = StrictNbt.compound(tag, "energy_request");
					if (!request.isEmpty()) {
						if (!request.getAllKeys().equals(Set.of("energy", "insert", "source"))) throw new IllegalArgumentException("Invalid energy request");
						result.energyRequest = new TerminalEnergyExchange.Request(StrictNbt.integer(request, "energy"), StrictNbt.bool(request, "insert"), StrictNbt.string(request, "source"));
						if (result.request != null || result.fluidRequest != null) throw new IllegalArgumentException("Conflicting energy requests");
					}
				}
				if (schema >= 5) {
					result.chemical = readChemical(StrictNbt.compound(tag, "chemical"), registries);
					var request = StrictNbt.compound(tag, "chemical_request");
					if (!request.isEmpty()) {
						if (!request.getAllKeys().equals(Set.of("chemical", "insert", "source"))) throw new IllegalArgumentException("Invalid chemical request");
						result.chemicalRequest = new TerminalChemicalExchange.Request(readChemical(StrictNbt.compound(request, "chemical"), registries), StrictNbt.bool(request, "insert"), StrictNbt.string(request, "source"));
						if (result.request != null || result.fluidRequest != null || result.energyRequest != null
								|| !result.chemical.isEmpty() && !ChemicalStack.isSameChemical(result.chemical, result.chemicalRequest.chemical()))
							throw new IllegalArgumentException("Conflicting chemical requests");
					}
				}
				if (schema >= 6) result.patternBuffer = TerminalPatternBuffer.read(tag.get("pattern_buffer"), registries);
				if (schema == 7 && result.request == null) throw new IllegalArgumentException("Missing observed cursor request");
			} catch (RuntimeException failure) {
				result.invalid = original.copy();
				com.mojang.logging.LogUtils.getLogger().error("Terminal cursor retained in player data after decode failure", failure);
			}
			return result;
		}
		@Override public Tag write(TerminalCursor value, HolderLookup.Provider registries) {
			if (value.invalid != null) return value.invalid.copy();
			var tag = new CompoundTag(); boolean observed = value.request != null && value.request.observed() != null;
			boolean patterns = observed || value.patternBuffer.stream().anyMatch(item -> !item.isEmpty());
			boolean chemicals = patterns || !value.chemical.isEmpty() || value.chemicalRequest != null;
			boolean energy = chemicals || value.energy != 0 || value.energyRequest != null;
			boolean fluids = energy || !value.fluid.isEmpty() || value.fluidRequest != null;
			boolean extended = fluids || value.request != null || !value.pending.isEmpty();
			tag.putInt("schema", observed ? 7 : patterns ? 6 : chemicals ? 5 : energy ? 4 : fluids ? 3 : extended ? 2 : 1); tag.put("item", value.stack.saveOptional(registries));
			if (extended) {
				tag.put("pending", value.pending.saveOptional(registries)); var request = new CompoundTag();
				if (value.request != null) { request.put("item", value.request.item().save(registries)); request.putBoolean("insert", value.request.insert()); request.putString("source", value.request.source()); }
				if (observed) { request.put("observed", value.request.observed().item().save(registries)); request.putInt("observed_count", value.request.observed().amount()); }
				tag.put("request", request);
			}
			if (fluids) {
				tag.put("fluid", saveFluid(value.fluid, registries)); var request = new CompoundTag();
				if (value.fluidRequest != null) { request.put("fluid", saveFluid(value.fluidRequest.fluid(), registries)); request.putBoolean("insert", value.fluidRequest.insert()); request.putString("source", value.fluidRequest.source()); }
				tag.put("fluid_request", request);
			}
			if (energy) {
				tag.putInt("energy", value.energy); var request = new CompoundTag();
				if (value.energyRequest != null) { request.putInt("energy", value.energyRequest.energy()); request.putBoolean("insert", value.energyRequest.insert()); request.putString("source", value.energyRequest.source()); }
				tag.put("energy_request", request);
			}
			if (chemicals) {
				tag.put("chemical", value.chemical.saveOptional(registries)); var request = new CompoundTag();
				if (value.chemicalRequest != null) { request.put("chemical", value.chemicalRequest.chemical().save(registries)); request.putBoolean("insert", value.chemicalRequest.insert()); request.putString("source", value.chemicalRequest.source()); }
				tag.put("chemical_request", request);
			}
			if (patterns) tag.put("pattern_buffer", TerminalPatternBuffer.write(value.patternBuffer, registries));
			return tag;
		}
	};
	private ItemStack stack = ItemStack.EMPTY;
	private Tag invalid;
	ItemStack pending = ItemStack.EMPTY;
	TerminalCursorExchange.Request request;
	FluidStack fluid = FluidStack.EMPTY;
	TerminalFluidExchange.Request fluidRequest;
	int energy;
	TerminalEnergyExchange.Request energyRequest;
	ChemicalStack chemical = ChemicalStack.EMPTY;
	TerminalChemicalExchange.Request chemicalRequest;
	boolean containerBusy;
	java.util.List<ItemStack> patternBuffer = TerminalPatternBuffer.empty();
	private static Tag saveFluid(FluidStack fluid, HolderLookup.Provider registries) { return fluid.isEmpty() ? new CompoundTag() : fluid.save(registries); }
	private static FluidStack readFluid(CompoundTag raw, HolderLookup.Provider registries) {
		if (raw.isEmpty()) return FluidStack.EMPTY;
		var fluid = FluidStack.parseOptional(registries, raw);
		if (fluid.isEmpty() || !saveFluid(fluid, registries).equals(raw)) throw new IllegalArgumentException("Lossy terminal fluid");
		return fluid;
	}
	private static ChemicalStack readChemical(CompoundTag raw, HolderLookup.Provider registries) {
		if (raw.isEmpty()) return ChemicalStack.EMPTY;
		var chemical = ChemicalStack.parse(registries, raw).orElseThrow();
		if (!chemical.save(registries).equals(raw)) throw new IllegalArgumentException("Lossy terminal chemical");
		return chemical;
	}
	public static MeTerminalView.Receipt receipt(ServerPlayer player) {
		var cursor = get(player);
		if (!cursor.available()) return MeTerminalView.Receipt.EMPTY;
		var fluid = !cursor.fluid.isEmpty() ? cursor.fluid : cursor.fluidRequest == null ? FluidStack.EMPTY : cursor.fluidRequest.fluid();
		String label = fluid.isEmpty() ? "" : fluid.getHoverName().getString();
		if (label.length() > 128) label = label.substring(0, Character.isHighSurrogate(label.charAt(127)) ? 127 : 128);
		var chemical = !cursor.chemical.isEmpty() ? cursor.chemical : cursor.chemicalRequest == null ? ChemicalStack.EMPTY : cursor.chemicalRequest.chemical();
		String chemicalLabel = chemical.isEmpty() ? "" : chemical.getTextComponent().getString();
		if (chemicalLabel.length() > 128) chemicalLabel = chemicalLabel.substring(0, Character.isHighSurrogate(chemicalLabel.charAt(127)) ? 127 : 128);
		return new MeTerminalView.Receipt(label, cursor.fluid.getAmount(), cursor.fluidRequest == null ? 0 : cursor.fluidRequest.fluid().getAmount(),
				cursor.energy, cursor.energyRequest == null ? 0 : cursor.energyRequest.energy(), chemicalLabel,
				cursor.chemical.getAmount(), cursor.chemicalRequest == null ? 0 : cursor.chemicalRequest.chemical().getAmount());
	}
	public boolean available() { return invalid == null; }
	public ItemStack item() { return stack.copy(); }
	void set(ItemStack value) {
		if (!available()) throw new IllegalStateException("Terminal cursor is quarantined");
		stack = value.copy();
	}
	static TerminalCursor get(ServerPlayer player) {
		if (!player.server.isSameThread()) throw new IllegalStateException("Terminal cursor belongs to the server thread");
		return player.getData(NetworkContent.TERMINAL_CURSOR);
	}
	static void restore(ServerPlayer player, AbstractContainerMenu menu) {
		var cursor = get(player);
		if (cursor.available()) { menu.setCarried(cursor.item()); TerminalCursorExchange.recover(player, menu, false); }
	}
	static void close(ServerPlayer player, AbstractContainerMenu menu) {
		var cursor = get(player);
		if (cursor.available()) {
			TerminalCursorExchange.recover(player, menu, false);
			cursor.set(menu.getCarried());
			if (player.isAlive()) {
				var before = TerminalCraftingPlan.copy(player.getInventory().items);
				var after = TerminalCraftingPlan.copy(before);
				var rest = TerminalCraftingPlan.insert(after, cursor.item());
				// 有限接收完成后再发布；这里没有外部库存或丢弃回调。
				cursor.set(rest);
				for (int i = 0; i < 36; i++) if (!ItemStack.matches(before.get(i), after.get(i))) player.getInventory().items.set(i, after.get(i));
				player.getInventory().setChanged();
			}
		}
		menu.setCarried(ItemStack.EMPTY);
	}
}
