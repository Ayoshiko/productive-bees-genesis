package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** 绑定原蜂箱状态的单蜂进出计划；不授予产物、能量或其它蜜蜂的修改权。 */
public final class BeeRosterChange {
	private final BeeMemberState source;
	private final BeeMemberState candidate;
	private final BeeRecord bee;
	private final boolean insertion;

	private BeeRosterChange(BeeMemberState source, BeeRecord bee, boolean insertion) {
		this.source = source;
		this.bee = bee;
		this.insertion = insertion;
		var roster = new ArrayList<>(source.bees());
		if (insertion) roster.add(bee); else roster.remove(bee);
		roster.sort(Comparator.comparingInt(BeeRecord::slot));
		candidate = new BeeMemberState(source.member(), Math.incrementExact(source.revision()), source.energy(),
				source.energyCapacity(), roster, source.feeding(), source.networkPowered());
	}

	/** 每次实际装入使用成员版本派生新身份；模拟不消耗随机状态，也不复用原槽蜜蜂身份。 */
	public static BeeRosterChange insert(BeeMemberState source, int slot, AssetImage original, StaticBeePlan plan) {
		Objects.requireNonNull(source);
		if (slot < 0 || slot >= 3 || source.bees().stream().anyMatch(value -> value.slot() == slot)) {
			throw new IllegalArgumentException("Bee target is occupied or outside the basic apiary");
		}
		var raw = original.copy();
		if (raw.getInt("slot_index") != slot || raw.getInt("ticks_in_hive") != 0) {
			throw new IllegalArgumentException("Inserted bee cannot carry old work progress");
		}
		var id = UUID.nameUUIDFromBytes((source.member() + ":cage:" + source.revision() + ":" + slot)
				.getBytes(StandardCharsets.UTF_8));
		var bee = new BeeRecord(id, source.member(), slot, original, plan, 0, 0, 0, ProductAmount.ZERO);
		return new BeeRosterChange(source, bee, true);
	}

	/** 只取出当前身份且已结清完整周期的蜜蜂；未完成周期按实体装笼语义取消。 */
	public static BeeRosterChange extract(BeeMemberState source, int slot, UUID expectedBee) {
		var bee = source.bee(slot);
		if (!bee.id().equals(expectedBee)) throw new IllegalArgumentException("Stale bee identity");
		if (!bee.drained()) throw new IllegalStateException("Settle paid bee results before caging");
		return new BeeRosterChange(source, bee, false);
	}

	/** 原根匹配才可提交；恢复出来的等值快照也需要重新准备。 */
	public boolean matches(BeeMemberState state) { return source == state; }
	/** 受本次交换影响的唯一蜜蜂。 */
	public BeeRecord bee() { return bee; }
	/** true 为放蜂入网，false 为装笼取出。 */
	public boolean insertion() { return insertion; }
	/** 不包含其它资产变化的不可变后继。 */
	public BeeMemberState candidate() { return candidate; }
}
