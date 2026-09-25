package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.Direction;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineVisualState;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineActivityInbox.Receipt.*;
import static org.junit.jupiter.api.Assertions.*;

class MachineActivityInboxTest {
	private final MachineVisualSnapshot structure = CombinedApiaryTimelineTest.event(0, MachineActivityEvent.Activity.COMBINED).structure();
	private MachineActivityEvent event(long sequence, long tick) {
		return new MachineActivityEvent(structure, sequence, tick, 7, MachineActivityEvent.Activity.COMBINED, List.of());
	}
	private MachineActivityInbox ready() {
		var inbox = new MachineActivityInbox(); inbox.bind(structure); assertTrue(inbox.baseline(structure, 0)); return inbox;
	}
	@Test void firstTrackingIsBaselineAndDuplicateInitialFramesDoNotReplayOrCancel() {
		var inbox = new MachineActivityInbox(); inbox.bind(structure);
		assertEquals(BASELINE, inbox.accept(event(11, 100), 100, true)); assertFalse(inbox.active());
		assertEquals(REJECTED, inbox.accept(event(11, 100), 100, true));
		assertEquals(STARTED, inbox.accept(event(12, 100), 100, true));
		assertFalse(inbox.baseline(structure, 11)); assertTrue(inbox.active());
		inbox.bind(structure); assertTrue(inbox.active());
	}
	@Test void lateFutureDistanceAndBudgetSkipsConsumeSequenceWithoutBacklog() {
		var inbox = ready();
		assertEquals(SKIPPED, inbox.accept(event(1, 0), 160, true));
		assertEquals(SKIPPED, inbox.accept(event(2, 161), 160, true));
		assertEquals(REJECTED, inbox.accept(event(2, 161), 161, true));
		assertEquals(SKIPPED, inbox.accept(event(3, 161), 161, false));
		assertEquals(REJECTED, inbox.accept(event(3, 161), 161, true));
		assertEquals(0, inbox.retainedEvents());
		assertEquals(STARTED, inbox.accept(event(4, 155), 161, true));
		assertEquals(CombinedApiaryTimeline.sample(event(4, 155), 6.5), inbox.sample(161, 0.5F).orElseThrow());
	}
	@Test void highAccelerationRetainsOnlyCurrentAndLatestFragment() {
		var inbox = ready(); assertEquals(STARTED, inbox.accept(event(1, 100), 100, true));
		for (int i = 2; i <= 257; i++) {
			assertEquals(MERGED, inbox.accept(event(i, 101), 101, true)); assertEquals(2, inbox.retainedEvents());
		}
		for (int tick = 120; tick <= 240; tick += 20) inbox.advance(tick);
		var latest = new MachineActivityEvent(structure, 258, 250, 9, MachineActivityEvent.Activity.CENTRIFUGE, List.of());
		assertEquals(MERGED, inbox.accept(latest, 250, true));
		inbox.advance(260);
		assertEquals(1, inbox.retainedEvents());
		assertEquals(CombinedApiaryTimeline.Stage.PRESS, inbox.sample(260, 0).orElseThrow().stage());
		assertEquals(0, inbox.sample(260, 0).orElseThrow().bee().alpha());
	}
	@Test void expiredSuccessorIsDroppedInsteadOfReplaying() {
		var inbox = ready(); inbox.accept(event(1, 100), 100, true); inbox.accept(event(2, 100), 100, true);
		for (int tick = 120; tick <= 260; tick += 20) inbox.advance(tick);
		assertEquals(0, inbox.retainedEvents());
	}
	@Test void newWorkAfterLongIdleStartsWithoutAHiddenPerMachineTicker() {
		var inbox = ready(); inbox.accept(event(1, 100), 100, true);
		for (int tick = 120; tick <= 260; tick += 20) inbox.advance(tick);
		assertFalse(inbox.active());
		assertEquals(STARTED, inbox.accept(event(2, 10000), 10000, true));
	}
	@Test void cancellationAndClockJumpsDoNotReplayConsumedEvents() {
		var inbox = ready(); inbox.accept(event(1, 100), 100, true); inbox.accept(event(2, 100), 100, true);
		inbox.cancel(); assertEquals(0, inbox.retainedEvents());
		assertEquals(REJECTED, inbox.accept(event(2, 100), 100, true));
		assertEquals(STARTED, inbox.accept(event(3, 100), 100, true));
		assertFalse(inbox.advance(99)); assertFalse(inbox.active());
		assertEquals(STARTED, inbox.accept(event(4, 99), 99, true));
		assertEquals(SKIPPED, inbox.accept(event(5, 120), 120, true));
		assertFalse(inbox.active()); assertEquals(REJECTED, inbox.accept(event(5, 120), 120, true));
		assertEquals(STARTED, inbox.accept(event(6, 120), 120, true));
	}
	@Test void pausedGameTimeFreezesAndLargeLongTimePreservesFractionalPrecision() {
		var inbox = ready(); long time = Long.MAX_VALUE - 1000;
		inbox.accept(event(1, time), time, true);
		var pose = inbox.sample(time, 0.25F).orElseThrow();
		for (int i = 0; i < 100; i++) assertEquals(pose, inbox.sample(time, 0.25F).orElseThrow());
		assertNotEquals(pose.bee(), inbox.sample(time, 0.75F).orElseThrow().bee());
		assertNotEquals(pose.bee(), inbox.sample(time + 1, 0.25F).orElseThrow().bee());
		assertTrue(inbox.sample(time + 1, Float.NaN).isEmpty());
	}
	@Test void replacementGenerationAndVersionRejectAllOldEvents() {
		var inbox = ready(); inbox.accept(event(1, 100), 100, true);
		var replacement = new MachineVisualSnapshot(2, UUID.randomUUID(), 2, 0, Direction.NORTH, MachineVisualState.READY);
		inbox.bind(replacement); assertFalse(inbox.active());
		assertEquals(REJECTED, inbox.accept(event(2, 100), 100, true));
		inbox.bind(structure); assertEquals(REJECTED, inbox.accept(event(3, 100), 100, true));
		var next = new MachineActivityEvent(replacement, 1, 100, 0, MachineActivityEvent.Activity.APIARY, List.of());
		assertEquals(BASELINE, inbox.accept(next, 100, true));
		inbox.invalidate(); inbox.bind(replacement);
		assertEquals(REJECTED, inbox.accept(next, 100, true)); assertEquals(0, inbox.retainedEvents());
	}
	@Test void sequenceExhaustionNeverWraps() {
		var inbox = ready(); assertEquals(STARTED, inbox.accept(event(Long.MAX_VALUE, 100), 100, true));
		inbox.cancel(); assertEquals(REJECTED, inbox.accept(event(1, 100), 100, true));
	}
	@Test void inactiveFramesAdvanceWatermarkAndStaleInactiveFramesCannotCancelNewGeneration() {
		var inbox = ready(); inbox.accept(event(1, 100), 100, true);
		var inactive = new MachineVisualSnapshot(3, structure.machine(), 2, -1, Direction.NORTH, MachineVisualState.UNFORMED);
		inbox.bind(inactive); inbox.bind(structure);
		assertEquals(REJECTED, inbox.accept(event(2, 100), 100, true));
		var next = new MachineVisualSnapshot(4, structure.machine(), 3, 0, Direction.NORTH, MachineVisualState.READY);
		inbox.bind(next); inbox.baseline(next, 0);
		var work = new MachineActivityEvent(next, 1, 100, 0, MachineActivityEvent.Activity.APIARY, List.of());
		assertEquals(STARTED, inbox.accept(work, 100, true));
		inbox.bind(inactive); assertTrue(inbox.active());
		assertTrue(inbox.sample(100, 1).isPresent());
	}
}
