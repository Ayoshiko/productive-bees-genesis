package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

/** 菜单注册类型固定查询范围；客户端不能通过请求切换成员类别。 */
public enum TerminalScope {
	ALL(null), APIARY("productivebeesgenesis:mek_apiary"), CENTRIFUGE("productivebeesgenesis:mek_centrifuge");
	private final String machine;
	TerminalScope(String machine) { this.machine = machine; }
	public String machine() { return machine; }
	public boolean accepts(String candidate) {
		return machine == null || machine.equals(candidate)
				|| this == APIARY && com.ayoshiko.productivebeesgenesis.apiculture.capacity.ApiaryWorkLayout.forMachine(candidate) != null
				|| this == CENTRIFUGE && com.ayoshiko.productivebeesgenesis.apiculture.capacity.CentrifugeWorkLayout.lanes(candidate) > 0
				|| com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.MACHINE.equals(candidate);
	}
	public com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord next(
			com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachines machines,
			com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin cursor, boolean reverse) {
		return this == ALL ? machines.activeEntry(null, cursor, reverse) : machines.workEntry(
				this == APIARY ? com.ayoshiko.productivebeesgenesis.apiculture.capacity.WorkKey.Kind.BEE_CYCLE
						: com.ayoshiko.productivebeesgenesis.apiculture.capacity.WorkKey.Kind.CENTRIFUGE_RECIPE, cursor, reverse);
	}
	public Iterable<com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord> values(
			com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachines machines) {
		return () -> new java.util.Iterator<>() {
			private com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord next = TerminalScope.this.next(machines, null, false);
			public boolean hasNext() { return next != null; }
			public com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord next() {
				if (next == null) throw new java.util.NoSuchElementException();
				var result = next; next = TerminalScope.this.next(machines, result.claim().origin(), false); return result;
			}
		};
	}
}
