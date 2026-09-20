package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.VirtualLaneState;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.WorkCapacity;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRule;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.ProductMatcher;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.ReservePolicy;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.LedgerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtUtils;

/** 只读取已经冻结的领域值；不访问世界、注册表、策略回调或可变服务。 */
public final class NetworkCheckpointStream {
	private NetworkCheckpointStream() { }
	public static void write(NetworkCheckpoint checkpoint, DataOutput output) throws IOException {
		write(checkpoint, SharedConstants.getCurrentVersion().getDataVersion().getVersion(), output);
	}
	static void write(NetworkCheckpoint checkpoint, int dataVersion, DataOutput output) throws IOException {
		var stream = new NbtStream(output); stream.root(dataVersion);
		stream.integer("schema", NetworkCheckpointCodec.SCHEMA);
		stream.tag("energy", EnergyRecordCodec.encode(checkpoint.energy()));
		stream.tag("identity", NetworkCheckpointCodec.identity(checkpoint.identity()));
		stream.number("revision", checkpoint.revision()); stream.number("policy", checkpoint.policyRevision());
		stream.number("ledgerRevision", checkpoint.ledger().revision());
		amounts(stream, "balances", checkpoint.ledger().balances());
		stream.list("transactions", checkpoint.ledger().transactions().size());
		for (var transaction : checkpoint.ledger().transactions()) transaction(stream, transaction);
		stream.list("transfers", checkpoint.transfers().size());
		for (var transfer : checkpoint.transfers()) NetworkCheckpointCodec.transfer(transfer).write(output);
		stream.list("discoveries", checkpoint.discoveries().size());
		for (var discovery : checkpoint.discoveries()) NetworkCheckpointCodec.discovery(discovery).write(output);
		stream.list("members", checkpoint.members().size());
		for (var member : checkpoint.members()) member(stream, member);
		stream.list("lanes", checkpoint.lanes().size());
		for (var lane : checkpoint.lanes()) lane(stream, lane);
		stream.compound("scheduler"); scheduler(stream, checkpoint.scheduler());
		stream.list("ownership", checkpoint.ownedMachines().size());
		for (var record : checkpoint.ownedMachines().values()) OwnershipRecordCodec.owned(record).write(output);
		stream.end(); stream.end();
	}
	private static void amounts(NbtStream stream, String name, Map<ProductKey, ProductAmount> values) throws IOException {
		stream.list(name, values.size());
		for (var entry : values.entrySet()) {
			key(stream, "key", entry.getKey());
			stream.tag("amount", ProductRecordCodec.amount(entry.getValue())); stream.end();
		}
	}
	private static void key(NbtStream stream, String name, ProductKey key) throws IOException {
		stream.compound(name); stream.string("kind", key.kind().name()); stream.string("id", key.id().toString());
		stream.tag("components", key.components()); stream.end();
	}
	private static void matcher(NbtStream stream, ProductMatcher matcher) throws IOException {
		stream.compound("matcher"); stream.string("mode", matcher.mode().name()); key(stream, "template", matcher.template()); stream.end();
	}
	private static void transaction(NbtStream stream, LedgerCheckpoint.Pending value) throws IOException {
		stream.tag("id", NbtUtils.createUUID(value.id())); stream.number("policy", value.policyRevision());
		stream.string("state", value.state().name());
		amounts(stream, "inputs", value.inputs()); amounts(stream, "outputs", value.outputs()); stream.end();
	}
	private static void capacity(NbtStream stream, WorkCapacity value) throws IOException {
		var work = value.work(); stream.string("kind", work.kind().name()); stream.string("id", work.id());
		stream.number("recipe", work.recipeRevision()); stream.string("context", work.contextKey());
		stream.integer("operations", value.operationsPerCycle()); stream.integer("ticks", value.cycleTicks());
		stream.number("energy", value.energyPerOperation()); stream.number("laneEnergy", value.fullLaneEnergyPerTick());
		stream.decimal("multiplier", value.outputMultiplier()); stream.decimal("stability", value.stabilityBonus());
		stream.compound("effects");
		for (var effect : value.effects().entrySet()) stream.integer(effect.getKey(), effect.getValue());
		stream.end(); stream.end();
	}
	private static void member(NbtStream stream, MemberCapabilitySnapshot value) throws IOException {
		stream.tag("id", NbtUtils.createUUID(value.memberId())); stream.number("revision", value.revision());
		stream.string("machine", value.machineId()); stream.tag("origin", CapacityRecordCodec.origin(value.origin()));
		stream.string("availability", value.availability().name()); stream.integer("bees", value.beeSlots());
		stream.integer("lanes", value.laneCount()); stream.list("alternatives", value.alternatives().size());
		for (var alternative : value.alternatives()) capacity(stream, alternative);
		stream.end();
	}
	private static void lane(NbtStream stream, VirtualLaneState value) throws IOException {
		stream.tag("member", NbtUtils.createUUID(value.memberId())); stream.number("revision", value.capabilityRevision());
		stream.integer("index", value.laneIndex()); stream.integer("progress", value.progress());
		stream.compound("capacity"); capacity(stream, value.capability()); stream.end();
	}
	private static void layer(NbtStream stream, String name, ReservePolicy.Layer value) throws IOException {
		stream.compound(name); stream.tag("fallback", RuleRecordCodec.limit(value.fallback()));
		stream.list("entries", value.entries().size());
		for (var entry : value.entries().entrySet()) {
			matcher(stream, entry.getKey());
			stream.tag("limit", RuleRecordCodec.limit(entry.getValue())); stream.end();
		}
		stream.end();
	}
	private static void rule(NbtStream stream, ProcessingRule rule) throws IOException {
		stream.string("id", rule.id()); stream.number("revision", rule.revision()); stream.bool("enabled", rule.enabled());
		stream.integer("priority", rule.priority()); stream.integer("weight", rule.weight()); stream.integer("batch", rule.batchLimit());
		stream.compound("selector");
		switch (rule.selector()) {
			case ProcessingRule.Match match -> { stream.string("type", "match"); matcher(stream, match.matcher()); }
			case ProcessingRule.Tag match -> {
				stream.string("type", "tag"); stream.string("kind", match.kind().name()); stream.string("id", match.tag().toString());
			}
			case ProcessingRule.Goal goal -> {
				stream.string("type", "goal"); key(stream, "product", goal.product());
				stream.tag("lower", ProductRecordCodec.amount(goal.lower())); stream.tag("upper", ProductRecordCodec.amount(goal.upper()));
			}
		}
		stream.end(); stream.string("scope", rule.reserves().scope().name());
		layer(stream, "global", rule.reserves().global()); layer(stream, "local", rule.reserves().rule()); stream.end();
	}
	private static void scheduler(NbtStream stream, SchedulerCheckpoint state) throws IOException {
		stream.string("mode", state.mode().name()); stream.string("cursor", state.cursorRule()); stream.integer("used", state.used());
		stream.list("rules", state.rules().size());
		for (var rule : state.rules()) rule(stream, rule);
		stream.compound("watermarks");
		for (var entry : state.watermarks().entrySet()) stream.bool(entry.getKey(), entry.getValue());
		stream.end(); stream.end();
	}
}
