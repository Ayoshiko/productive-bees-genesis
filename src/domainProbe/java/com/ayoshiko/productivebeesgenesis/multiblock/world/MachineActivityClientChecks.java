package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.client.MachineActivityClient;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.CombinedApiaryTimeline;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineActivityEvent;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import static com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineActivityInbox.Receipt.*;

/** 开发源集注入显示描述，不发生产包、不生成资产、不进入发行 JAR。 */
final class MachineActivityClientChecks {
	private static long sequence = 100, pausedAt;
	private static MachineActivityEvent original;
	private static MachineControllerEntity armed;
	private static CombinedApiaryTimeline.Frame paused;
	static void start(Minecraft client) {
		armed = (MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.getFirst());
		var frame = armed.visualSnapshot().orElseThrow();
		armed.visualActivity().cancel();
		MachineActivityClient.baseline(armed, sequence);
		var event = new MachineActivityEvent(frame, ++sequence, client.level.getGameTime(), 77, MachineActivityEvent.Activity.COMBINED,
				List.of(new MachineActivityEvent.Appearance(MachineActivityEvent.ResourceKind.ITEM, ResourceLocation.parse("minecraft:diamond"))));
		check(MachineActivityClient.accept(armed, event) == STARTED, "Activity did not start from current display frame");
		check(MachineActivityClient.accept(armed, event) == REJECTED, "Activity replayed duplicate");
		check(MachineActivityClient.sample(armed).isPresent() && MachineActivityClient.activeCount() == 1, "Activity not retained");
		if (original == null) original = event;
		else if (!original.structure().equals(frame)) check(MachineActivityClient.accept(armed, original) == REJECTED, "Old identity activity accepted");
	}
	static void invalidated() {
		check(MachineActivityClient.sample(armed).isEmpty() && armed.visualActivity().retainedEvents() == 0, "Invalid structure kept activity");
	}
	static void cleared(String reason) {
		check(armed.visualActivity().retainedEvents() == 0 && MachineActivityClient.activeCount() == 0, reason + " retained activity");
	}
	static void pauseBaseline(Minecraft client) {
		pausedAt = client.level.getGameTime(); paused = MachineActivityClient.sample(armed).orElseThrow();
	}
	static void paused(Minecraft client) {
		check(client.isPaused() && client.level.getGameTime() == pausedAt, "Integrated game time advanced while paused");
		check(MachineActivityClient.sample(armed).orElseThrow().equals(paused), "Activity phase advanced while paused");
	}
	static boolean resumed(Minecraft client) {
		if (client.isPaused() || client.level.getGameTime() <= pausedAt) return false;
		check(!MachineActivityClient.sample(armed).orElseThrow().equals(paused), "Activity did not resume with game time");
		return true;
	}
	static void disconnected() {
		cleared("World disconnect"); armed = null; original = null; paused = null;
	}
	private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private MachineActivityClientChecks() { }
}
