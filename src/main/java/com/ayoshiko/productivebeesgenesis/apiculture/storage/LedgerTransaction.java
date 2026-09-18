package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Map;
import java.util.UUID;

/** 仅由所属账本签发的事务身份；终态保存在句柄，不在账本永久积累完成历史。 */
public final class LedgerTransaction {
	public enum State { RESERVED, PAID, COMMITTED, CANCELLED }
	private final UUID id = UUID.randomUUID();
	private final Object authority;
	private final long policyRevision;
	private final Map<ProductKey, ProductAmount> inputs;
	private final Map<ProductKey, ProductAmount> outputs;
	private volatile State state = State.RESERVED;

	LedgerTransaction(Object authority, long policyRevision, Map<ProductKey, ProductAmount> inputs, Map<ProductKey, ProductAmount> outputs) {
		this.authority = authority;
		this.policyRevision = policyRevision;
		this.inputs = Map.copyOf(inputs);
		this.outputs = Map.copyOf(outputs);
	}
	public UUID id() { return id; }
	public long policyRevision() { return policyRevision; }
	public Map<ProductKey, ProductAmount> inputs() { return inputs; }
	public Map<ProductKey, ProductAmount> outputs() { return outputs; }
	public State state() { return state; }
	boolean belongsTo(Object authority) { return this.authority == authority; }
	void state(State state) { this.state = state; }
}
