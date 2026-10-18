package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 原生只出不入化学品视图；账户才是库存，栈和类型列表都不成为独立余额。 */
final class ResourceChemicalOutput implements IChemicalHandler {
	private record Entry(ResourceKey key, Chemical chemical) {}
	private ResourceOutputAccess port;
	private Map<ResourceKey, Long> observed;
	private List<Entry> entries = List.of();

	ResourceChemicalOutput(ResourceOutputAccess port) { this.port = port; }

	void invalidate() {
		port = null;
		observed = null;
		entries = List.of();
	}

	private ResourceAccount refresh() {
		var account = port == null ? null : port.account();
		if (account == null || !port.outputAllowed(ResourceNativeType.CHEMICAL) || !account.canExtract(port.reference())) {
			observed = null;
			entries = List.of();
			return null;
		}
		var available = account.available();
		if (observed != available) {
			if (observed != null && observed.keySet().equals(available.keySet())) {
				observed = available;
				return account;
			}
			var next = new ArrayList<Entry>();
			for (var key : available.keySet()) {
				var chemical = resolve(key);
				if (chemical != null) next.add(new Entry(key, chemical));
			}
			next.sort(Comparator.comparing(entry -> entry.key().toString()));
			entries = List.copyOf(next);
			observed = available;
		}
		return account;
	}

	private static Chemical resolve(ResourceKey key) {
		if (key.version() != 1 || !key.kind().toString().equals("productivebeesgenesis:chemical")) return null;
		var payload = key.payload();
		if (!payload.getAllKeys().equals(Set.of("id"))) return null;
		var id = ResourceLocation.tryParse(payload.getString("id"));
		if (id == null || !MekanismAPI.CHEMICAL_REGISTRY.containsKey(id)) return null;
		var chemical = MekanismAPI.CHEMICAL_REGISTRY.get(id);
		return chemical.isEmptyType() ? null : chemical;
	}

	@Override public int getChemicalTanks() {
		refresh();
		return Math.max(1, entries.size());
	}

	@Override public ChemicalStack getChemicalInTank(int tank) {
		var account = refresh();
		if (account == null || tank < 0 || tank >= entries.size()) return ChemicalStack.EMPTY;
		var entry = entries.get(tank);
		return new ChemicalStack(entry.chemical().getAsHolder(), account.available().getOrDefault(entry.key(), 0L));
	}

	/** 第三方不得通过 setter 绕过只出不入约束或清空账户。 */
	@Override public void setChemicalInTank(int tank, ChemicalStack stack) {}
	@Override public boolean isValid(int tank, ChemicalStack stack) { return false; }
	@Override public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) { return stack.copy(); }

	@Override public long getChemicalTankCapacity(int tank) {
		refresh();
		return port != null && tank >= 0 && tank < Math.max(1, entries.size()) ? port.capacity() : 0;
	}

	@Override public ChemicalStack extractChemical(int tank, long amount, Action action) {
		var account = refresh();
		if (account == null || amount <= 0 || tank < 0 || tank >= entries.size()) return ChemicalStack.EMPTY;
		var entry = entries.get(tank);
		if (!account.canExtract(port.reference(), entry.key())) return ChemicalStack.EMPTY;
		long extracted = Math.min(amount, account.available().getOrDefault(entry.key(), 0L));
		if (extracted <= 0) return ChemicalStack.EMPTY;
		// 返回栈先构造；扣账后不再执行外部代码，不留下可自动重试的半份交接。
		var result = new ChemicalStack(entry.chemical().getAsHolder(), extracted);
		if (action.execute()) account.extract(port.reference(), entry.key(), extracted);
		return result;
	}
}
