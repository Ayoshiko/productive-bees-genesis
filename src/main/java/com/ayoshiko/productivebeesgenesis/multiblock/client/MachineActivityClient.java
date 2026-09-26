package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.multiblock.visual.CombinedApiaryTimeline;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineActivityEvent;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineActivityInbox;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity;
import java.util.IdentityHashMap;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** 客户端活动入口；尚无网络调用者。只跟踪最多 16 台活动机器，结束即释放强引用。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class MachineActivityClient {
	public static final int MAX_ACTIVE = 16;
	private static final IdentityHashMap<MachineControllerEntity, MachineActivityInbox> ACTIVE = new IdentityHashMap<>();
	private static Minecraft client() {
		var client = Minecraft.getInstance();
		if (!client.isSameThread()) throw new IllegalStateException("Machine animation requires the client thread");
		return client;
	}
	private static boolean visible(Minecraft client, MachineControllerEntity controller) {
		return client.level != null && controller.getLevel() == client.level && !controller.isRemoved()
				&& controller.getBlockPos().getCenter().distanceToSqr(client.gameRenderer.getMainCamera().getPosition()) <= 64 * 64;
	}
	public static boolean baseline(MachineControllerEntity controller, long sequence) {
		var client = client();
		if (!visible(client, controller)) return false;
		var frame = controller.visualSnapshot().orElse(null);
		var inbox = controller.visualActivity(); inbox.bind(frame);
		return inbox.baseline(frame, sequence);
	}
	public static MachineActivityInbox.Receipt accept(MachineControllerEntity controller, MachineActivityEvent event) {
		var client = client(); var inbox = controller.visualActivity();
		if (client.level == null || controller.getLevel() != client.level || controller.isRemoved()) return MachineActivityInbox.Receipt.REJECTED;
		inbox.bind(controller.visualSnapshot().orElse(null));
		boolean permitted = visible(client, controller) && (ACTIVE.containsKey(controller) || ACTIVE.size() < MAX_ACTIVE);
		var receipt = inbox.accept(event, client.level.getGameTime(), permitted);
		if (inbox.active()) ACTIVE.put(controller, inbox);
		return receipt;
	}
	public static Optional<CombinedApiaryTimeline.Frame> sample(MachineControllerEntity controller) {
		var client = client();
		if (!visible(client, controller)) { controller.visualActivity().cancel(); ACTIVE.remove(controller); return Optional.empty(); }
		var inbox = controller.visualActivity(); inbox.bind(controller.visualSnapshot().orElse(null));
		var sample = inbox.sample(client.level.getGameTime(), client.getTimer().getGameTimeDeltaPartialTick(true));
		if (!inbox.active()) ACTIVE.remove(controller);
		return sample;
	}
	@SubscribeEvent public static void tick(ClientTickEvent.Post event) {
		var client = client();
		var iterator = ACTIVE.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next(); var controller = entry.getKey(); var inbox = entry.getValue();
			if (!visible(client, controller)) inbox.cancel();
			else { inbox.bind(controller.visualSnapshot().orElse(null)); inbox.advance(client.level.getGameTime()); }
			if (!inbox.active()) iterator.remove();
		}
	}
	@SubscribeEvent public static void beginFrame(RenderFrameEvent.Pre event) { CombinedApiaryRenderer.beginFrame(); }
	/** 资源模型重建时清空活动，仍保留各 BE 的序号水位。 */
	public static void resourcesReloaded() { clear(); }
	@SubscribeEvent public static void levelUnloaded(LevelEvent.Unload event) {
		if (event.getLevel().isClientSide()) clear();
	}
	private static void clear() {
		client(); ACTIVE.values().forEach(MachineActivityInbox::cancel); ACTIVE.clear(); CombinedApiaryRenderer.beginFrame();
	}
	public static int activeCount() { client(); return ACTIVE.size(); }
	private MachineActivityClient() { }
}
