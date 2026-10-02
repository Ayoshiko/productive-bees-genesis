package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.BeeRecordCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.CentrifugeRecordCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.ProductRecordCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.StrictNbt;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** 独立机器 schema 1；读取完整候选后才发布，失败由调用方隔离原始存档。 */
public final class CombinedWorkCodec {
	public static CompoundTag encode(CombinedMachineWork work) {
		CombinedMachineCapacity.validate(work);
		var tag = new CompoundTag(); tag.putInt("schema", 1); tag.putInt("capacityVersion", CombinedMachineCapacity.VERSION);
		tag.putUUID("machine", work.machine()); tag.putLong("generation", work.generation()); tag.putLong("revision", work.revision());
		tag.putInt("beeSlots", work.beeSlots()); tag.putInt("lanes", work.lanes());
		tag.putLong("energy", work.energy()); tag.putLong("energyCapacity", work.energyCapacity());
		var bees = new ListTag(); for (var bee : work.bees()) bees.add(BeeRecordCodec.encodeBee(bee)); tag.put("bees", bees);
		var jobs = new ListTag();
		work.centrifuges().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
			var value = new CompoundTag(); value.putInt("lane", entry.getKey());
			value.put("job", CentrifugeRecordCodec.encodeJob(entry.getValue().job()));
			value.put("delivered", ProductRecordCodec.amounts(entry.getValue().delivered())); jobs.add(value);
		});
		tag.put("jobs", jobs); tag.putInt("tankCapacity", work.buffer().tankCapacity());
		tag.put("items", cells(work.buffer().items())); tag.put("fluids", cells(work.buffer().fluids())); return tag;
	}
	public static CombinedMachineWork decode(CompoundTag tag, Consumer<ProductKey> validate, ToIntFunction<ProductKey> itemLimits) {
		fields(tag, "schema", "capacityVersion", "machine", "generation", "revision", "beeSlots", "lanes", "energy", "energyCapacity",
				"bees", "jobs", "tankCapacity", "items", "fluids");
		if (StrictNbt.integer(tag, "schema") != 1 || StrictNbt.integer(tag, "capacityVersion") != CombinedMachineCapacity.VERSION)
			throw new IllegalArgumentException("Unsupported combined work schema or capacity version");
		var machine = StrictNbt.uuid(tag, "machine"); var codec = new ProductRecordCodec(validate);
		var beeTags = StrictNbt.list(tag, "bees"); var jobTags = StrictNbt.list(tag, "jobs");
		if (beeTags.size() > CombinedMachineCapacity.BEE_SLOTS || jobTags.size() > CombinedMachineCapacity.LANES)
			throw new IllegalArgumentException("Too many combined work records");
		var bees = new ArrayList<BeeRecord>();
		for (var raw : beeTags) bees.add(BeeRecordCodec.decodeBee((CompoundTag) raw, machine, validate));
		var jobs = new HashMap<Integer, CentrifugeDelivery>();
		for (var raw : jobTags) {
			var value = (CompoundTag) raw; fields(value, "lane", "job", "delivered");
			var delivery = new CentrifugeDelivery(CentrifugeRecordCodec.decodeJob(StrictNbt.compound(value, "job"), validate),
					codec.readAmounts(StrictNbt.list(value, "delivered")));
			if (jobs.putIfAbsent(StrictNbt.integer(value, "lane"), delivery) != null) throw new IllegalArgumentException("Duplicate combined lane");
		}
		var items = readCells(StrictNbt.list(tag, "items"), CombinedMachineCapacity.ITEM_SLOTS, codec, itemLimits);
		var fluids = readCells(StrictNbt.list(tag, "fluids"), CombinedMachineCapacity.FLUID_TANKS, codec, itemLimits);
		var work = new CombinedMachineWork(machine, StrictNbt.number(tag, "generation"), StrictNbt.number(tag, "revision"),
				StrictNbt.integer(tag, "beeSlots"), StrictNbt.integer(tag, "lanes"), StrictNbt.number(tag, "energy"), StrictNbt.number(tag, "energyCapacity"),
				bees, jobs, new FiniteProductBuffer(items, fluids, StrictNbt.integer(tag, "tankCapacity")));
		CombinedMachineCapacity.validate(work); return work;
	}
	private static ListTag cells(List<FiniteProductBuffer.Cell> cells) {
		var list = new ListTag();
		for (var cell : cells) {
			var value = new CompoundTag();
			if (cell.key() != null) {
				value.put("key", ProductRecordCodec.key(cell.key())); value.putLong("count", cell.count()); value.putInt("limit", cell.limit());
			}
			list.add(value);
		}
		return list;
	}
	private static List<FiniteProductBuffer.Cell> readCells(ListTag list, int expectedSize, ProductRecordCodec codec, ToIntFunction<ProductKey> itemLimits) {
		if (list.size() != expectedSize) throw new IllegalArgumentException("Unsupported combined buffer size");
		var result = new ArrayList<FiniteProductBuffer.Cell>(expectedSize);
		for (var raw : list) {
			var value = (CompoundTag) raw;
			if (value.isEmpty()) { result.add(FiniteProductBuffer.Cell.empty()); continue; }
			fields(value, "key", "count", "limit"); var key = codec.readKey(StrictNbt.compound(value, "key"));
			var cell = new FiniteProductBuffer.Cell(key, StrictNbt.number(value, "count"), StrictNbt.integer(value, "limit"));
			// 实际组件变更导致上限下降时隔离保留，不截断或把新上限默认为 64。
			if (key.kind() == ProductKey.Kind.ITEM && cell.limit() > Math.min(64, itemLimits.applyAsInt(key)))
				throw new IllegalArgumentException("Persisted stack limit exceeds current item limit");
			result.add(cell);
		}
		return result;
	}
	private static void fields(CompoundTag tag, String... names) {
		if (!tag.getAllKeys().equals(Set.of(names))) throw new IllegalArgumentException("Unknown or missing combined work fields");
	}
	private CombinedWorkCodec() { }
}
