package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** schema 1 的流式领域映射；集合直接进入对应候选索引，不生成整域 NBT。 */
final class CheckpointSchema {
	enum Kind { ROOT, NETWORK, DIRECTORY, IDENTITY, ORIGIN, KEY, AMOUNT_ENTRY, TRANSACTION, TRANSFER, DISCOVERY,
		MEMBER, LANE, CAPACITY, EFFECTS, SCHEDULER, WATERMARKS, RULE, SELECTOR, MATCHER, LIMIT, LAYER, RESERVE_ENTRY, CLAIM, OWNERSHIP, RAW }
	record Field(int type, Kind nested, Kind element) { }
	record AmountEntry(ProductKey key, ProductAmount amount) { }
	record SchedulerState(SchedulerCheckpoint.RestoreBuilder builder, ProcessingRuleScheduler.Mode mode, String cursor, int used) { }
	private static final Map<Kind, Map<String, Field>> FIELDS = definitions();
	private static Map<Kind, Map<String, Field>> definitions() {
		Map<Kind, Map<String, Field>> result = new EnumMap<>(Kind.class);
		define(result, Kind.ROOT, "DataVersion:i data:NETWORK");
		define(result, Kind.NETWORK, "schema:i identity:IDENTITY revision:l policy:l ledgerRevision:l balances:[AMOUNT_ENTRY transactions:[TRANSACTION transfers:[TRANSFER discoveries:[DISCOVERY members:[MEMBER lanes:[LANE scheduler:SCHEDULER ownership:[OWNERSHIP");
		define(result, Kind.DIRECTORY, "schema:i revision:l networks:[IDENTITY claims:[CLAIM");
		define(result, Kind.CLAIM, "network:u member:u transfer:u origin:ORIGIN machine:s");
		define(result, Kind.OWNERSHIP, "claim:CLAIM phase:s assets:RAW fingerprint:s failure:s bees:RAW centrifuge:RAW");
		define(result, Kind.IDENTITY, "network:u controller:u owner:u generation:l origin:ORIGIN");
		define(result, Kind.ORIGIN, "dimension:s x:i y:i z:i");
		define(result, Kind.KEY, "kind:s id:s components:RAW");
		define(result, Kind.AMOUNT_ENTRY, "key:KEY amount:a");
		define(result, Kind.TRANSACTION, "id:u policy:l state:s inputs:[AMOUNT_ENTRY outputs:[AMOUNT_ENTRY");
		define(result, Kind.TRANSFER, "id:u endpoint:s key:KEY direction:s offered:l held:a phase:s failure:s");
		define(result, Kind.DISCOVERY, "adapter:s key:KEY");
		define(result, Kind.MEMBER, "id:u revision:l machine:s origin:ORIGIN availability:s bees:i lanes:i alternatives:[CAPACITY");
		define(result, Kind.LANE, "member:u revision:l index:i progress:i capacity:CAPACITY");
		define(result, Kind.CAPACITY, "kind:s id:s recipe:l context:s operations:i ticks:i energy:l laneEnergy:l multiplier:d stability:d effects:EFFECTS");
		define(result, Kind.SCHEDULER, "mode:s cursor:s used:i rules:[RULE watermarks:WATERMARKS");
		define(result, Kind.RULE, "id:s revision:l enabled:b priority:i weight:i batch:i selector:SELECTOR scope:s global:LAYER local:LAYER");
		define(result, Kind.SELECTOR, "type:s matcher:MATCHER kind:s id:s product:KEY lower:a upper:a");
		define(result, Kind.MATCHER, "mode:s template:KEY");
		define(result, Kind.LIMIT, "mode:s floor:a");
		define(result, Kind.LAYER, "fallback:LIMIT entries:[RESERVE_ENTRY");
		define(result, Kind.RESERVE_ENTRY, "matcher:MATCHER limit:LIMIT");
		return result;
	}
	private static void define(Map<Kind, Map<String, Field>> result, Kind kind, String definition) {
		Map<String, Field> fields = new ConcurrentHashMap<>();
		for (String text : definition.split(" ")) {
			String[] parts = text.split(":"); String type = parts[1];
			Field field = switch (type) {
				case "i" -> new Field(Tag.TAG_INT, null, null);
				case "l" -> new Field(Tag.TAG_LONG, null, null);
				case "s" -> new Field(Tag.TAG_STRING, null, null);
				case "d" -> new Field(Tag.TAG_DOUBLE, null, null);
				case "b" -> new Field(Tag.TAG_BYTE, null, null);
				case "u" -> new Field(Tag.TAG_INT_ARRAY, null, null);
				case "a" -> new Field(-1, null, null);
				default -> type.startsWith("[") ? new Field(Tag.TAG_LIST, null, Kind.valueOf(type.substring(1)))
						: new Field(Tag.TAG_COMPOUND, Kind.valueOf(type), null);
			};
			fields.put(parts[0], field);
		}
		result.put(kind, Map.copyOf(fields));
	}
	static final class DirectoryState {
		final SnapshotRecords<UUID, NetworkIdentity> identities = new SnapshotRecords<>(UUID::compareTo);
		final Map<UUID, UUID> controllers = new ConcurrentHashMap<>();
		final Map<MemberCapabilitySnapshot.Origin, UUID> positions = new ConcurrentHashMap<>();
		long revision;
		final SnapshotRecords<MemberCapabilitySnapshot.Origin, com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim> claims = new SnapshotRecords<>(NetworkDirectoryData.CLAIM_ORDER);
		final Map<UUID, MemberCapabilitySnapshot.Origin> claimedMembers = new ConcurrentHashMap<>();
		final Map<UUID, MemberCapabilitySnapshot.Origin> claimedTransfers = new ConcurrentHashMap<>();
		private Iterator<com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim> checks;
		void claim(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim claim) {
			if (claims.get(claim.origin()) != null || claimedMembers.putIfAbsent(claim.member(), claim.origin()) != null || claimedTransfers.putIfAbsent(claim.transfer(), claim.origin()) != null) throw new IllegalArgumentException("Duplicate member claim or transfer");
			claims.put(claim.origin(), claim);
		}
		boolean validateStep() {
			if (checks == null) checks = claims.valuesSnapshot().iterator();
			if (!checks.hasNext()) return true;
			var claim = checks.next(); var identity = identities.get(claim.network());
			if (identity == null || !identity.origin().dimension().equals(claim.origin().dimension())) throw new IllegalArgumentException("Orphaned directory claim");
			return false;
		}
		void add(NetworkIdentity identity) {
			if (identities.get(identity.networkId()) != null || controllers.putIfAbsent(identity.controllerId(), identity.networkId()) != null
					|| positions.putIfAbsent(identity.origin(), identity.networkId()) != null) throw new IllegalArgumentException("Duplicate directory identity");
			identities.put(identity.networkId(), identity);
		}
	}
	static final class Node {
		final Kind kind;
		private final boolean directory;
		private final Consumer<ProductKey> validateKey;
		private final Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> validateFeeding;
		private final Map<String, Object> values = new ConcurrentHashMap<>();
		private final Set<String> seen = ConcurrentHashMap.newKeySet();
		private final Object state;
		Node watermarkTarget;
		Node(Kind kind, boolean directory, Consumer<ProductKey> validateKey, Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> validateFeeding) {
			this.kind = kind; this.directory = directory; this.validateKey = validateKey;
			this.validateFeeding = validateFeeding;
			state = switch (kind) {
				case NETWORK -> new NetworkRestoreState();
				case DIRECTORY -> new DirectoryState();
				case SCHEDULER -> new SchedulerCheckpoint.RestoreBuilder();
				case EFFECTS -> new WorkCapacity.EffectsBuilder();
				case WATERMARKS -> new SnapshotRecords<String, Boolean>(String::compareTo);
				default -> Boolean.TRUE;
			};
		}
		Field begin(String name, int type) {
			if (name == null || !seen.add(name)) throw new IllegalArgumentException("Duplicate or unnamed field in " + kind + ": " + name);
			Field field = kind == Kind.EFFECTS ? new Field(Tag.TAG_INT, null, null)
					: kind == Kind.WATERMARKS ? new Field(Tag.TAG_BYTE, null, null) : FIELDS.get(kind).get(name);
			if (kind == Kind.ROOT && directory && "data".equals(name)) field = new Field(Tag.TAG_COMPOUND, Kind.DIRECTORY, null);
			if (field == null || (field.type() == -1 ? type != Tag.TAG_LONG && type != Tag.TAG_BYTE_ARRAY : field.type() != type)) {
				throw new IllegalArgumentException("Unknown field or incorrect type in " + kind + ": " + name);
			}
			return field;
		}
		void put(String name, Object value) {
			if (kind == Kind.EFFECTS) ((WorkCapacity.EffectsBuilder) state).add(name, ((IntTag) value).getAsInt());
			else if (kind == Kind.WATERMARKS) watermarkTarget.watermark(name, bool(value));
			else values.put(name, value);
		}
		@SuppressWarnings("unchecked") private SnapshotRecords<String, Boolean> watermarks() { return (SnapshotRecords<String, Boolean>) state; }
		ListSink list(String name) {
			return switch (kind) {
				case NETWORK -> new ListSink(value -> ((NetworkRestoreState) state).add(name, value), () -> Boolean.TRUE);
				case DIRECTORY -> new ListSink(value -> { if (name.equals("claims")) ((DirectoryState) state).claim((com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim) value); else ((DirectoryState) state).add((NetworkIdentity) value); }, () -> Boolean.TRUE);
				case SCHEDULER -> new ListSink(value -> ((SchedulerCheckpoint.RestoreBuilder) state).rule((ProcessingRule) value), () -> Boolean.TRUE);
				case MEMBER -> { var builder = new MemberCapabilitySnapshot.AlternativesBuilder(); yield new ListSink(value -> builder.add((WorkCapacity) value), builder::finish); }
				case TRANSACTION -> {
					var amounts = new PagedProductAmounts();
					yield new ListSink(value -> {
						var entry = (AmountEntry) value;
						if (entry.amount().isZero() || !amounts.amount(entry.key()).isZero()) throw new IllegalArgumentException("Duplicate or empty transaction amount");
						amounts.set(entry.key(), entry.amount());
					}, amounts::snapshot);
				}
				case LAYER -> {
					var entries = new SnapshotRecords<ProductMatcher, ReserveLimit>(Comparator.comparing(ProductMatcher::mode).thenComparing(value -> value.template().orderingKey()));
					yield new ListSink(value -> {
						@SuppressWarnings("unchecked") var entry = (Map.Entry<ProductMatcher, ReserveLimit>) value;
						if (entries.get(entry.getKey()) != null) throw new IllegalArgumentException("Duplicate reserve matcher");
						entries.put(entry.getKey(), entry.getValue());
					}, entries::snapshot);
				}
				default -> throw new IllegalArgumentException("Unexpected list");
			};
		}
		Object finish() {
			if (kind == Kind.EFFECTS) return ((WorkCapacity.EffectsBuilder) state).finish();
			if (kind == Kind.WATERMARKS) return Boolean.TRUE;
			if (kind != Kind.SELECTOR && !seen.equals(FIELDS.get(kind).keySet())) throw new IllegalArgumentException("Missing fields in " + kind);
			return switch (kind) {
				case ROOT -> {
					int version = integer("DataVersion");
					if (version < 0 || version > net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion()) throw new IllegalArgumentException("Unsupported Minecraft data version");
					yield values.get("data");
				}
				case NETWORK -> {
					if (integer("schema") != NetworkCheckpointCodec.SCHEMA) throw new IllegalArgumentException("Unsupported network schema");
					var network = (NetworkRestoreState) state; network.identity = value("identity"); network.revision = nonnegative("revision");
					network.policyRevision = nonnegative("policy"); network.ledgerRevision = nonnegative("ledgerRevision"); network.scheduler = value("scheduler"); yield network;
				}
				case DIRECTORY -> {
					if (integer("schema") != 2) throw new IllegalArgumentException("Unsupported directory schema");
					var index = (DirectoryState) state; index.revision = nonnegative("revision"); yield index;
				}
				case IDENTITY -> new NetworkIdentity(uuid("network"), uuid("controller"), uuid("owner"), number("generation"), value("origin"));
				case CLAIM -> new com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim(uuid("network"), uuid("member"), uuid("transfer"), value("origin"), string("machine"));
				case OWNERSHIP -> new com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord(value("claim"), choice("phase", com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord.Phase.class), new com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage(value("assets")), string("fingerprint"), string("failure"), BeeRecordCodec.decode(value("bees"), validateKey, validateFeeding), CentrifugeRecordCodec.decode(value("centrifuge"), validateKey));
				case ORIGIN -> new MemberCapabilitySnapshot.Origin(ResourceLocation.parse(string("dimension")).toString(), integer("x"), integer("y"), integer("z"));
				case KEY -> { var key = new ProductKey(choice("kind", ProductKey.Kind.class), ResourceLocation.parse(string("id")), value("components")); validateKey.accept(key); yield key; }
				case AMOUNT_ENTRY -> new AmountEntry(value("key"), amount("amount"));
				case TRANSACTION -> new LedgerCheckpoint.Pending(uuid("id"), number("policy"), choice("state", LedgerTransaction.State.class), value("inputs"), value("outputs"));
				case TRANSFER -> new TransferStaging.View(uuid("id"), string("endpoint"), value("key"), choice("direction", TransferStaging.Direction.class), number("offered"), amount("held"), choice("phase", TransferStaging.Phase.class), string("failure"));
				case DISCOVERY -> new ProductPolicyRegistry.Discovery(string("adapter"), value("key"));
				case MEMBER -> new MemberCapabilitySnapshot(uuid("id"), number("revision"), string("machine"), value("origin"), choice("availability", MemberCapabilitySnapshot.Availability.class), integer("bees"), integer("lanes"), value("alternatives"));
				case LANE -> new VirtualLaneState(uuid("member"), number("revision"), integer("index"), value("capacity"), integer("progress"));
				case CAPACITY -> new WorkCapacity(new WorkKey(choice("kind", WorkKey.Kind.class), string("id"), number("recipe"), string("context")), integer("operations"), integer("ticks"), number("energy"), number("laneEnergy"), decimal("multiplier"), decimal("stability"), value("effects"));
				case SCHEDULER -> new SchedulerState((SchedulerCheckpoint.RestoreBuilder) state, choice("mode", ProcessingRuleScheduler.Mode.class), string("cursor"), integer("used"));
				case RULE -> new ProcessingRule(string("id"), number("revision"), bool(values.get("enabled")), integer("priority"), integer("weight"), integer("batch"), value("selector"), new ReservePolicy(choice("scope", ReservePolicy.Scope.class), value("global"), value("local")));
				case SELECTOR -> selector();
				case MATCHER -> new ProductMatcher(choice("mode", ProductMatcher.Mode.class), value("template"));
				case LIMIT -> new ReserveLimit(choice("mode", ReserveLimit.Mode.class), amount("floor"));
				case LAYER -> new ReservePolicy.Layer(value("fallback"), value("entries"));
				case RESERVE_ENTRY -> Map.entry((ProductMatcher) value("matcher"), (ReserveLimit) value("limit"));
				default -> throw new IllegalStateException("Unexpected node " + kind);
			};
		}
		void watermark(String id, boolean value) { ((SchedulerCheckpoint.RestoreBuilder) state).watermark(id, value); }
		private ProcessingRule.Selector selector() {
			String type = string("type");
			Set<String> required = switch (type) {
				case "match" -> Set.of("type", "matcher"); case "tag" -> Set.of("type", "kind", "id");
				case "goal" -> Set.of("type", "product", "lower", "upper"); default -> throw new IllegalArgumentException("Unknown selector");
			};
			if (!seen.equals(required)) throw new IllegalArgumentException("Invalid selector fields");
			return switch (type) {
				case "match" -> new ProcessingRule.Match(value("matcher"));
				case "tag" -> new ProcessingRule.Tag(choice("kind", ProductKey.Kind.class), ResourceLocation.parse(string("id")));
				default -> new ProcessingRule.Goal(value("product"), amount("lower"), amount("upper"));
			};
		}
		@SuppressWarnings("unchecked") private <T> T value(String name) { return (T) Objects.requireNonNull(values.get(name), name); }
		private String string(String name) { return ((StringTag) value(name)).getAsString(); }
		private int integer(String name) { return ((IntTag) value(name)).getAsInt(); }
		private long number(String name) { return ((LongTag) value(name)).getAsLong(); }
		private long nonnegative(String name) { long value = number(name); if (value < 0) throw new IllegalArgumentException("Negative " + name); return value; }
		private double decimal(String name) { return ((DoubleTag) value(name)).getAsDouble(); }
		private UUID uuid(String name) { return NbtUtils.loadUUID((Tag) value(name)); }
		private ProductAmount amount(String name) { var tag = new CompoundTag(); tag.put(name, value(name)); return ProductRecordCodec.readAmount(tag, name); }
		private <E extends Enum<E>> E choice(String name, Class<E> type) { return Enum.valueOf(type, string(name)); }
	}
	record ListSink(Consumer<Object> add, java.util.function.Supplier<Object> finish) { }
	static boolean bool(Object value) {
		byte number = ((ByteTag) value).getAsByte();
		if (number != 0 && number != 1) throw new IllegalArgumentException("Invalid boolean");
		return number == 1;
	}
}
