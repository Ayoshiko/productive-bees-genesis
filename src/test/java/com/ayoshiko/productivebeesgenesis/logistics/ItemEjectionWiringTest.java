package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Minecraft 容器需游戏运行时；此处守住两条输出路径的计费和回退接线。 */
class ItemEjectionWiringTest {

	private static String read(String name) throws Exception {
		return Files.readString(Path.of("src/main/java/com/ayoshiko/productivebeesgenesis/logistics/" + name));
	}

	@Test
	void directOutputAccountsActualRemainderWithoutSimulation() throws Exception {
		String source = read("FastItemEjector.java");
		String direct = source.substring(source.indexOf("public int pushDirect("), source.indexOf("private long pushSlots("));
		assertFalse(direct.contains("simulateInsert("));
		assertTrue(direct.contains("stack.copyWithCount(want)"));
		assertTrue(direct.indexOf("state.isRejected(stack, want, gameTime)")
				< direct.indexOf("stack.copyWithCount(want)"));
		assertTrue(direct.contains("leftover.getCount()"));
		assertTrue(direct.contains("if (accepted == 0) state.rememberRejected(offered, gameTime)"));
		assertTrue(direct.contains("finally"));
		assertTrue(direct.contains("state.budget.record(gameTime, true,"));
	}

	@Test
	void slotsRetainSimulationRollbackAndIndependentProgress() throws Exception {
		String source = read("FastItemEjector.java");
		String slots = source.substring(source.indexOf("private long pushSlots("), source.indexOf("private static void returnHome("));
		assertTrue(slots.indexOf("simulateInsert(") < slots.indexOf("Action.EXECUTE"));
		assertTrue(slots.contains("returnHome(slots, slot, leftover)"));
		assertTrue(slots.contains("state.budget.canAttempt(gameTime, false)"));
		assertTrue(slots.contains("state.budget.record(gameTime, false,"));
		assertTrue(slots.contains("state.slotCursor = RoundRobinSlotTraversal.advance(slotIndex, slotCount)"));
	}

	@Test
	void rejectionTracksFullComponentsAndBatchSizeAndExpires() throws Exception {
		String state = read("NeighborItemTransferState.java");
		assertTrue(state.contains("rejected[i].getCount() == count"));
		assertTrue(state.contains("ItemStack.isSameItemSameComponents(rejected[i], stack)"));
		assertTrue(state.contains("rejectedTick == gameTime"));
		assertTrue(state.contains("Arrays.fill(rejected, 0, rejectedCount, null)"));
		String ejector = read("FastItemEjector.java");
		assertTrue(ejector.contains("state.target != target"));
		assertTrue(ejector.contains("Arrays.fill(transferStates, null)"));
	}
}
