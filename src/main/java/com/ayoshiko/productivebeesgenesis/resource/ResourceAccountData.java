package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;

/** 账户 NBT 合同；拒绝损坏的状态组合，调用者保留整个原始存档。 */
final class ResourceAccountData {
	private ResourceAccountData() {}

	static CompoundTag save(ResourceAccount account) {
		var tag = new CompoundTag();
		tag.putUUID("id", account.id);
		tag.putUUID("owner", account.owner);
		tag.putLong("generation", account.generation);
		tag.putLong("next_sequence", account.nextSequence);
		if (account.binding != null) {
			var binding = new CompoundTag();
			binding.putString("dimension", account.binding.dimension().toString());
			binding.putLong("position", account.binding.packedPosition());
			binding.putString("host_kind", account.binding.hostKind().toString());
			tag.put("binding", binding);
		}
		if (!account.centrifugeWork.isEmpty()) {
			var work = new CompoundTag();
			account.centrifugeWork.forEach((lane, value) -> work.put(Integer.toString(lane), value.copy()));
			tag.put("centrifuge_work", work);
		}
		tag.put("available", ResourceExactAmounts.save(account.available));
		tag.putString("suspension", account.suspension);
		if (account.production != null) {
			tag.put("production", saveProduction(account.production));
		}
		if (!account.suspendedLanes.isEmpty()) {
			var lanes = new CompoundTag();
			account.suspendedLanes.forEach((lane, pending) -> {
				var entry = new CompoundTag();
				entry.put("production", saveProduction(pending.production()));
				entry.putString("reason", pending.reason());
				lanes.put(Integer.toString(lane), entry);
			});
			tag.put("suspended_lanes", lanes);
		}
		if (account.transfer != null) {
			tag.put("transfer", saveTransfer(account.transfer));
		}
		if (!account.suspendedTransfers.isEmpty()) {
			var transfers = new net.minecraft.nbt.ListTag();
			for (var pending : account.suspendedTransfers.values()) {
				var entry = saveTransfer(pending.transfer());
				entry.putString("reason", pending.reason());
				transfers.add(entry);
			}
			tag.put("suspended_transfers", transfers);
		}
		return tag;
	}

	static ResourceAccount load(ResourceAccounts accounts, CompoundTag tag) {
		keys(tag, Set.of("id", "owner", "generation", "next_sequence", "binding",
				"available", "suspension", "production", "transfer", "centrifuge_work", "suspended_lanes", "suspended_transfers"));
		if (!tag.hasUUID("id") || !tag.hasUUID("owner")) throw invalid();
		var account = new ResourceAccount(accounts, tag.getUUID("id"), tag.getUUID("owner"), null);
		account.generation = positiveLong(tag, "generation");
		account.nextSequence = positiveLong(tag, "next_sequence");
		if (tag.contains("binding")) {
			var binding = compound(tag, "binding");
			keys(binding, Set.of("dimension", "position", "host_kind"));
			account.binding = new ResourceAccount.Binding(
					ResourceLocation.parse(string(binding, "dimension")),
					number(binding, "position"), ResourceLocation.parse(string(binding, "host_kind")));
		}
		if (tag.contains("centrifuge_work")) {
			var work = compound(tag, "centrifuge_work");
			for (var key : work.getAllKeys()) {
				int lane = Integer.parseInt(key);
				if (!Integer.toString(lane).equals(key)) throw invalid();
				var value = compound(work, key).copy();
				ResourceAccount.validateWork(lane, value, account.centrifugeWork);
				account.centrifugeWork.put(lane, value);
			}
		}
		account.available = ResourceExactAmounts.load(tag.get("available"));
		account.suspension = string(tag, "suspension");
		if (account.suspension.length() > 512 || (tag.contains("production") && tag.contains("transfer"))) {
			throw invalid();
		}
		if (tag.contains("production")) {
			account.production = loadProduction(compound(tag, "production"), account.nextSequence);
		}
		if (tag.contains("suspended_lanes")) {
			var lanes = compound(tag, "suspended_lanes");
			var sequences = new java.util.HashSet<Long>();
			if (account.production != null) sequences.add(account.production.sequence);
			for (String key : lanes.getAllKeys()) {
				int lane = Integer.parseInt(key);
				var entry = compound(lanes, key);
				keys(entry, Set.of("production", "reason"));
				var pending = loadProduction(compound(entry, "production"), account.nextSequence);
				String reason = string(entry, "reason");
				if (lane < 0 || lane >= 256 || !Integer.toString(lane).equals(key) || lane != ResourceAccount.productionLane(pending.context)
						|| reason.isBlank() || reason.length() > 512 || !sequences.add(pending.sequence)) throw invalid();
				account.suspendedLanes.put(lane, new ResourceAccount.SuspendedProduction(pending, reason));
			}
		}
		if (tag.contains("transfer")) {
			var transfer = compound(tag, "transfer");
			keys(transfer, Set.of("sequence", "key", "amount"));
			long sequence = positiveLong(transfer, "sequence");
			var key = ResourceKey.load(compound(transfer, "key"));
            var amount = ResourceExactAmounts.read(transfer, "amount", key);
			if (sequence >= account.nextSequence) throw invalid();
			if (account.suspendedLanes.values().stream().anyMatch(pending -> pending.production().sequence == sequence)) throw invalid();
			ResourceExactAmounts.plus(account.available, Map.of(key, amount));
			account.transfer = new ResourceAccount.Transfer(sequence, key, amount);
		}
		if (account.production != null || account.transfer != null) {
			if (account.suspension.isEmpty()) {
				account.suspension = "Unfinished request restored from save; explicit outcome resolution required";
			}
		} else if (!account.suspension.isEmpty()) {
			throw invalid();
		}
		if (account.production != null && ResourceAccount.productionLane(account.production.context) >= 0) {
			int lane = ResourceAccount.productionLane(account.production.context);
			if (account.suspendedLanes.containsKey(lane)) throw invalid();
			account.suspendedLanes.put(lane, new ResourceAccount.SuspendedProduction(account.production, account.suspension));
			account.production = null;
			account.suspension = "";
		}
		if (account.transfer != null) {
			account.suspendedTransfers.put(account.transfer.key(), new ResourceAccount.SuspendedTransfer(account.transfer, account.suspension));
			account.transfer = null;
			account.suspension = "";
		}
		if (tag.contains("suspended_transfers")) {
			if (!(tag.get("suspended_transfers") instanceof net.minecraft.nbt.ListTag list)
					|| !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND
					|| list.size() > ResourceAmounts.MAX_KEYS) throw invalid();
			for (var value : list) {
				var entry = (CompoundTag) value;
				keys(entry, Set.of("sequence", "key", "amount", "reason"));
				long sequence = positiveLong(entry, "sequence");
				var key = ResourceKey.load(compound(entry, "key"));
				var amount = ResourceExactAmounts.read(entry, "amount", key);
				String reason = string(entry, "reason");
				if (sequence >= account.nextSequence || reason.isBlank() || reason.length() > 512
						|| account.suspendedTransfers.putIfAbsent(key, new ResourceAccount.SuspendedTransfer(
								new ResourceAccount.Transfer(sequence, key, amount), reason)) != null) throw invalid();
			}
		}
		var sequences = new java.util.HashSet<Long>();
		if (account.production != null) sequences.add(account.production.sequence);
		for (var pending : account.suspendedLanes.values()) if (!sequences.add(pending.production().sequence)) throw invalid();
		for (var pending : account.suspendedTransfers.values()) if (!sequences.add(pending.transfer().sequence())) throw invalid();
		var maximum = account.available;
		if (account.production != null) maximum = ResourceExactAmounts.plus(maximum, account.production.maximum);
		for (var pending : account.suspendedLanes.values()) maximum = ResourceExactAmounts.plus(maximum, pending.production().maximum);
		for (var pending : account.suspendedTransfers.values()) maximum = ResourceExactAmounts.plus(maximum, Map.of(pending.transfer().key(), pending.transfer().amount()));
		if (account.transfer != null) ResourceExactAmounts.plus(maximum, Map.of(account.transfer.key(), account.transfer.amount()));
		return account;
	}

	private static CompoundTag saveProduction(ResourceAccount.Production pending) {
		var production = new CompoundTag();
		production.putLong("sequence", pending.sequence);
		production.putLong("recipe_revision", pending.recipeRevision);
		production.putString("phase", pending.phase.name());
		production.put("context", pending.context.copy());
		production.put("maximum", ResourceExactAmounts.save(pending.maximum));
		production.put("result", ResourceExactAmounts.save(pending.result));
		return production;
	}

	private static CompoundTag saveTransfer(ResourceAccount.Transfer pending) {
		var tag = new CompoundTag();
		tag.putLong("sequence", pending.sequence());
		tag.put("key", pending.key().save());
		ResourceExactAmounts.write(tag, "amount", pending.key(), pending.amount());
		return tag;
	}

	private static ResourceAccount.Production loadProduction(CompoundTag production, long nextSequence) {
		keys(production, Set.of("sequence", "recipe_revision", "phase", "context", "maximum", "result"));
		long sequence = positiveLong(production, "sequence");
		long revision = number(production, "recipe_revision");
		var phase = ResourceAccount.Phase.valueOf(string(production, "phase"));
		var context = compound(production, "context");
		var maximum = ResourceExactAmounts.load(production.get("maximum"));
		var result = ResourceExactAmounts.load(production.get("result"));
		if (sequence >= nextSequence || revision < 0 || maximum.isEmpty()
				|| context.sizeInBytes() > ResourceAccount.MAX_CONTEXT_BYTES || (phase != ResourceAccount.Phase.FROZEN && !result.isEmpty())) throw invalid();
		ResourceExactAmounts.within(result, maximum);
		return new ResourceAccount.Production(sequence, revision, context, maximum, result, phase);
	}

	static long number(CompoundTag tag, String key) {
		if (!tag.contains(key, Tag.TAG_LONG)) throw invalid();
		return tag.getLong(key);
	}

	private static long positiveLong(CompoundTag tag, String key) {
		long value = number(tag, key);
		if (value < 1) throw invalid();
		return value;
	}

	private static String string(CompoundTag tag, String key) {
		if (!tag.contains(key, Tag.TAG_STRING)) throw invalid();
		return tag.getString(key);
	}

	private static CompoundTag compound(CompoundTag tag, String key) {
		if (!tag.contains(key, Tag.TAG_COMPOUND)) throw invalid();
		return tag.getCompound(key);
	}

	static void keys(CompoundTag tag, Set<String> allowed) {
		if (!allowed.containsAll(tag.getAllKeys())) throw invalid();
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid resource account data");
	}
}
