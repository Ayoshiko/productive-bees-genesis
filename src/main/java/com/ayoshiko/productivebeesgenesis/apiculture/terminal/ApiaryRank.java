package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** 实际生产轮数／周期的稳定顺序；节能、容器转换和无效插件不虚增生产轮数。 */
public record ApiaryRank(int tier, int cycleTicks, float productivity, Origin origin, UUID member) implements Comparable<ApiaryRank> {
	/** 仅含值身份和能力，不含账本、世界、实体或封存资产；所有终端共享同一份。 */
	public record Order(Object token, java.util.NavigableMap<ApiaryRank, UUID> sorted, java.util.Map<UUID, ApiaryRank> members) { }
	private static final Comparator<Origin> POSITIONS = Comparator.comparing(Origin::dimension)
			.thenComparingInt(Origin::x).thenComparingInt(Origin::y).thenComparingInt(Origin::z);
	public ApiaryRank {
		Objects.requireNonNull(origin); Objects.requireNonNull(member);
		if (tier < 0 || cycleTicks < 1 || !Float.isFinite(productivity) || productivity <= 0)
			throw new IllegalArgumentException("Invalid apiary rank");
	}
	@Override public int compareTo(ApiaryRank other) {
		int value = Integer.compare(other.tier, tier);
		// float 有限倍率乘 int 后在 double 中不会溢出，避免除法舍入改变同率顺序。
		if (value == 0) value = Double.compare((double) other.productivity * cycleTicks, (double) productivity * other.cycleTicks);
		if (value == 0) value = Integer.compare(cycleTicks, other.cycleTicks);
		if (value == 0) value = POSITIONS.compare(origin, other.origin);
		return value == 0 ? member.compareTo(other.member) : value;
	}
}
