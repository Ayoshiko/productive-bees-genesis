package com.ayoshiko.productivebeesgenesis.apiculture.topology;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 单区块六面 BFS；每步读取一个候选位置，结构版本变化后整次扫描作废。 */
public final class TopologyScan {
	public record Node(BlockPos position, UUID owner, boolean core, int closedFaces, int beeSlots, int lanes) {
		public Node {
			position = position.immutable();
			if (closedFaces < 0 || closedFaces > 63 || beeSlots < 0 || lanes < 0) throw new IllegalArgumentException("Invalid topology node");
		}
		boolean open(Direction side) { return (closedFaces & (1 << side.ordinal())) == 0; }
	}
	public record View(long epoch, List<Node> members, long beeSlots, long lanes, int cores, int denied) {
		public boolean valid() { return cores == 1; }
	}
	private record Edge(BlockPos position, Direction entering) { }
	private final BlockPos origin;
	private final UUID owner;
	private final long epoch;
	private final Function<BlockPos, Node> source;
	private final ArrayDeque<Edge> queue = new ArrayDeque<>();
	private final Set<BlockPos> visited = ConcurrentHashMap.newKeySet();
	private final SnapshotRecords<BlockPos, Node> members = new SnapshotRecords<>(Comparator.comparingLong(BlockPos::asLong));
	private long bees, lanes;
	private int cores, denied;
	private boolean complete;
	public TopologyScan(BlockPos origin, UUID owner, long epoch, Function<BlockPos, Node> source) {
		this.origin = origin.immutable(); this.owner = java.util.Objects.requireNonNull(owner); this.epoch = epoch; this.source = source;
		queue.add(new Edge(this.origin, null));
	}
	public boolean step(long currentEpoch) {
		if (currentEpoch != epoch) throw new IllegalStateException("Stale topology scan");
		if (complete) return true;
		var edge = queue.pollFirst();
		if (edge == null) { complete = true; return true; }
		var pos = edge.position();
		if (visited.contains(pos)) return false;
		Node node = source.apply(pos);
		// 不可通行的入边不能把该节点标记为已访问，另一个开放面仍可能连通。
		if (node == null) { visited.add(pos); return false; }
		if (edge.entering() != null && !node.open(edge.entering())) return false;
		visited.add(pos);
		if (node.core()) cores++;
		if (!owner.equals(node.owner())) { denied++; return false; }
		if (!node.core()) { members.put(pos, node); bees = Math.addExact(bees, node.beeSlots()); lanes = Math.addExact(lanes, node.lanes()); }
		for (var direction : Direction.values()) {
			if (!node.open(direction)) continue;
			var next = pos.relative(direction);
			if (next.getX() >> 4 == origin.getX() >> 4 && next.getZ() >> 4 == origin.getZ() >> 4 && !visited.contains(next)) queue.addLast(new Edge(next, direction.getOpposite()));
		}
		return false;
	}
	public View finish() {
		if (!complete) throw new IllegalStateException("Incomplete topology");
		return new View(epoch, members.valuesSnapshot(), bees, lanes, cores, denied);
	}
}
