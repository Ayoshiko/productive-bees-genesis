package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.VirtualLaneState;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** 未知字段类型、版本、产品身份或交叉引用使整个域隔离，绝不跳过某条余额后继续。 */
public final class NetworkCheckpointCodec {
	public static final int SCHEMA = 3;
	private final ProductRecordCodec products;
	private final Consumer<ProductKey> validateKey;
	private final RuleRecordCodec rules;
	public NetworkCheckpointCodec(Consumer<ProductKey> validateKey) {
		this.validateKey = java.util.Objects.requireNonNull(validateKey);
		products = new ProductRecordCodec(validateKey); rules = new RuleRecordCodec(products);
	}
	CheckpointDecoder decoder(com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadSession input) {
		return new CheckpointDecoder(input, validateKey);
	}
	public static NetworkCheckpointCodec forRegistries(HolderLookup.Provider registries) {
		return new NetworkCheckpointCodec(key -> ProductKeyCodec.validatePersisted(key, registries));
	}
	public static CompoundTag identity(NetworkIdentity identity) {
		var tag = new CompoundTag(); tag.putUUID("network", identity.networkId()); tag.putUUID("controller", identity.controllerId());
		tag.putUUID("owner", identity.ownerId()); tag.putLong("generation", identity.generation()); tag.put("origin", CapacityRecordCodec.origin(identity.origin())); return tag;
	}
	public static NetworkIdentity readIdentity(CompoundTag tag) {
		return new NetworkIdentity(StrictNbt.uuid(tag, "network"), StrictNbt.uuid(tag, "controller"), StrictNbt.uuid(tag, "owner"),
				StrictNbt.number(tag, "generation"), CapacityRecordCodec.readOrigin(StrictNbt.compound(tag, "origin")));
	}
	static CompoundTag transaction(LedgerCheckpoint.Pending transaction) {
		var tag = new CompoundTag(); tag.putUUID("id", transaction.id()); tag.putLong("policy", transaction.policyRevision()); tag.putString("state", transaction.state().name());
		tag.put("inputs", ProductRecordCodec.amounts(transaction.inputs())); tag.put("outputs", ProductRecordCodec.amounts(transaction.outputs())); return tag;
	}
	private LedgerCheckpoint.Pending readTransaction(CompoundTag tag) {
		return new LedgerCheckpoint.Pending(StrictNbt.uuid(tag, "id"), StrictNbt.number(tag, "policy"), StrictNbt.choice(tag, "state", LedgerTransaction.State.class),
				products.readAmounts(StrictNbt.list(tag, "inputs")), products.readAmounts(StrictNbt.list(tag, "outputs")));
	}
	static CompoundTag transfer(TransferStaging.View transfer) {
		var tag = new CompoundTag(); tag.putUUID("id", transfer.id()); tag.putString("endpoint", transfer.endpoint()); tag.put("key", ProductRecordCodec.key(transfer.key()));
		tag.putString("direction", transfer.direction().name()); tag.putLong("offered", transfer.offered()); tag.put("held", ProductRecordCodec.amount(transfer.held()));
		tag.putString("phase", transfer.phase().name()); tag.putString("failure", transfer.failure()); return tag;
	}
	private TransferStaging.View readTransfer(CompoundTag tag) {
		return new TransferStaging.View(StrictNbt.uuid(tag, "id"), StrictNbt.string(tag, "endpoint"), products.readKey(StrictNbt.compound(tag, "key")),
				StrictNbt.choice(tag, "direction", TransferStaging.Direction.class), StrictNbt.number(tag, "offered"), ProductRecordCodec.readAmount(tag, "held"),
				StrictNbt.choice(tag, "phase", TransferStaging.Phase.class), StrictNbt.string(tag, "failure"));
	}
	static CompoundTag discovery(ProductPolicyRegistry.Discovery discovery) {
		var tag = new CompoundTag(); tag.putString("adapter", discovery.adapterId()); tag.put("key", ProductRecordCodec.key(discovery.key())); return tag;
	}
	/** 元数据与余额使用同一个 checkpoint；调用者不得分别捕获不同 revision。 */
	static CompoundTag metadata(NetworkCheckpoint checkpoint) {
		var tag = new CompoundTag(); tag.putInt("schema", SCHEMA); tag.put("identity", identity(checkpoint.identity()));
		tag.putLong("revision", checkpoint.revision()); tag.putLong("policy", checkpoint.policyRevision()); tag.putLong("ledgerRevision", checkpoint.ledger().revision());
		var transactions = new ListTag(); checkpoint.ledger().transactions().forEach(value -> transactions.add(transaction(value))); tag.put("transactions", transactions);
		var transfers = new ListTag(); checkpoint.transfers().forEach(value -> transfers.add(transfer(value))); tag.put("transfers", transfers);
		var discoveries = new ListTag(); checkpoint.discoveries().forEach(value -> discoveries.add(discovery(value))); tag.put("discoveries", discoveries);
		var members = new ListTag(); checkpoint.members().forEach(value -> members.add(CapacityRecordCodec.member(value))); tag.put("members", members);
		var lanes = new ListTag(); checkpoint.lanes().forEach(value -> lanes.add(CapacityRecordCodec.lane(value))); tag.put("lanes", lanes);
		var owned = new ListTag(); checkpoint.ownedMachines().values().forEach(value -> owned.add(OwnershipRecordCodec.owned(value))); tag.put("ownership", owned);
		tag.put("scheduler", RuleRecordCodec.scheduler(checkpoint.scheduler())); return tag;
	}
	public static CompoundTag encode(NetworkCheckpoint checkpoint) {
		var tag = metadata(checkpoint); tag.put("balances", ProductRecordCodec.amounts(checkpoint.ledger().balances())); return tag;
	}
	public NetworkCheckpoint decode(CompoundTag tag) {
		if (StrictNbt.integer(tag, "schema") != SCHEMA) throw new IllegalArgumentException("Unsupported network schema");
		var transactions = new ArrayList<LedgerCheckpoint.Pending>();
		StrictNbt.list(tag, "transactions").forEach(raw -> transactions.add(readTransaction((CompoundTag) raw)));
		var ledger = new LedgerCheckpoint(StrictNbt.number(tag, "ledgerRevision"), products.readAmounts(StrictNbt.list(tag, "balances")), transactions);
		var transfers = new ArrayList<TransferStaging.View>(); StrictNbt.list(tag, "transfers").forEach(raw -> transfers.add(readTransfer((CompoundTag) raw)));
		var discoveries = ConcurrentHashMap.<ProductPolicyRegistry.Discovery>newKeySet();
		for (var raw : StrictNbt.list(tag, "discoveries")) {
			var entry = (CompoundTag) raw;
			if (!discoveries.add(new ProductPolicyRegistry.Discovery(StrictNbt.string(entry, "adapter"), products.readKey(StrictNbt.compound(entry, "key"))))) {
				throw new IllegalArgumentException("Duplicate discovery");
			}
		}
		var members = new ArrayList<MemberCapabilitySnapshot>(); StrictNbt.list(tag, "members").forEach(raw -> members.add(CapacityRecordCodec.readMember((CompoundTag) raw)));
		var lanes = new ArrayList<VirtualLaneState>(); StrictNbt.list(tag, "lanes").forEach(raw -> lanes.add(CapacityRecordCodec.readLane((CompoundTag) raw)));
		var checkpoint = new NetworkCheckpoint(readIdentity(StrictNbt.compound(tag, "identity")), StrictNbt.number(tag, "revision"), StrictNbt.number(tag, "policy"),
				ledger, transfers, discoveries, members, lanes, rules.readScheduler(StrictNbt.compound(tag, "scheduler")));
		var owned = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachines.Builder();
		for (var raw : StrictNbt.list(tag, "ownership")) {
			var record = OwnershipRecordCodec.readOwned((CompoundTag) raw, validateKey);
			NetworkCheckpoint.validateOwnership(record, checkpoint.identity(), checkpoint.policyRevision());
			owned.add(record);
		}
		return checkpoint.restoredOwnership(owned.finish());
	}
}
