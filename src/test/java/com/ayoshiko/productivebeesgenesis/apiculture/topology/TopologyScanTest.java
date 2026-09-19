package com.ayoshiko.productivebeesgenesis.apiculture.topology;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TopologyScanTest {
	private final UUID owner = UUID.randomUUID();
	private final Map<BlockPos, TopologyScan.Node> nodes = new ConcurrentHashMap<>();
	private final BlockPos core = new BlockPos(0, 64, 0);
	private void node(BlockPos pos, boolean isCore, int closed) { nodes.put(pos, new TopologyScan.Node(pos, owner, isCore, closed, isCore ? 0 : 5, isCore ? 0 : 3)); }
	private TopologyScan.View scan() { var scan = new TopologyScan(core, owner, 1, nodes::get); while (!scan.step(1)) { } return scan.finish(); }
	@Test void budgetAndChunkBoundaryExcludeDiagonalsAndForeignOwners() {
		node(core, true, 0); node(core.east(), false, 0); node(core.west(), false, 0); node(core.south().south(), false, 0);
		nodes.put(core.above(), new TopologyScan.Node(core.above(), UUID.randomUUID(), false, 0, 9, 9));
		var reads = new AtomicInteger(); var scan = new TopologyScan(core, owner, 1, pos -> { reads.incrementAndGet(); return nodes.get(pos); });
		scan.step(1); assertEquals(1, reads.get()); assertThrows(IllegalStateException.class, scan::finish);
		while (!scan.step(1)) { }
		var view = scan.finish(); assertEquals(1, view.members().size()); assertEquals(5, view.beeSlots()); assertEquals(1, view.denied());
	}
	@Test void closedFirstApproachDoesNotHideAnotherOpenPathAndRemovalSplits() {
		node(core, true, 0); node(core.east(), false, 1 << Direction.WEST.ordinal());
		node(core.south(), false, 0); node(core.south().east(), false, 0);
		assertEquals(3, scan().members().size()); nodes.remove(core.south()); assertEquals(0, scan().members().size());
	}
	@Test void mutationDiscardsOldScanAndSecondCoreConflicts() {
		node(core, true, 0); node(core.east(), true, 0); assertFalse(scan().valid());
		var scan = new TopologyScan(core, owner, 1, nodes::get); scan.step(1); assertThrows(IllegalStateException.class, () -> scan.step(2));
	}
}
