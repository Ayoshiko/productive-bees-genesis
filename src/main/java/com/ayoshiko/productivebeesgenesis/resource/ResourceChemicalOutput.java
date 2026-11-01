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
final class ResourceChemicalOutput {
	private record Entry(ResourceKey key, Chemical chemical) {}
	private ResourceOutputAccess port;
    private final int kind;
	private Map<ResourceKey, Long> observed;
	private List<Entry> entries = List.of();

	ResourceChemicalOutput(ResourceOutputAccess port,int kind) { this.port = port;this.kind=kind; }

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
				if (chemical != null && com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.kind(chemical)==kind) next.add(new Entry(key, chemical));
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
		if (id == null || !com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.containsKey(id)) return null;
		var chemical = com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.get(id);
		return chemical.isEmptyType() ? null : chemical;
	}

	public int getTanks() {
		refresh();
		return Math.max(1, entries.size());
	}

	public ChemicalStack getChemicalInTank(int tank) {
		var account = refresh();
		if (account == null || tank < 0 || tank >= entries.size()) return com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.empty(kind);
		var entry = entries.get(tank);
		return com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.stack(entry.chemical(), account.available().getOrDefault(entry.key(), 0L));
	}

	/** 第三方不得通过 setter 绕过只出不入约束或清空账户。 */
	public void setChemicalInTank(int tank, ChemicalStack stack) {}
	public boolean isValid(int tank, ChemicalStack stack) { return false; }
	public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) { return stack.copy(); }

	public long getTankCapacity(int tank) {
		refresh();
		return port != null && tank >= 0 && tank < Math.max(1, entries.size()) ? port.capacity() : 0;
	}

	public ChemicalStack extractChemical(int tank, long amount, Action action) {
		var account = refresh();
		if (account == null || amount <= 0 || tank < 0 || tank >= entries.size()) return com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.empty(kind);
		var entry = entries.get(tank);
		if (!account.canExtract(port.reference(), entry.key())) return com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.empty(kind);
		long extracted = Math.min(amount, account.available().getOrDefault(entry.key(), 0L));
		if (extracted <= 0) return com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.empty(kind);
		// 返回栈先构造；扣账后不再执行外部代码，不留下可自动重试的半份交接。
		var result = com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.stack(entry.chemical(), extracted);
		if (action.execute()) account.extract(port.reference(), entry.key(), extracted);
		return result;
	}

    Object nativeHandler() {
        Class<?> type=switch(kind){case 0->mekanism.api.chemical.gas.IGasHandler.class;case 1->mekanism.api.chemical.infuse.IInfusionHandler.class;case 2->mekanism.api.chemical.pigment.IPigmentHandler.class;default->mekanism.api.chemical.slurry.ISlurryHandler.class;};
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(method.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
            return switch(method.getName()){
                case "getTanks"->getTanks(); case "getChemicalInTank"->getChemicalInTank((int)args[0]);
                case "getTankCapacity"->getTankCapacity((int)args[0]);case "isValid"->false;
                case "setChemicalInTank"->null;case "insertChemical"->((ChemicalStack)args[1]).copy();
                case "extractChemical"->extractChemical((int)args[0],(long)args[1],(Action)args[2]);
                case "getEmptyStack"->com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.empty(kind);
                case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];case "toString"->"Genesis chemical output "+kind;
                default->throw new UnsupportedOperationException(method.toString());
            };
        });
    }
}
