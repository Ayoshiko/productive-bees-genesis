package com.ayoshiko.productivebeesgenesis.domainprobe;

import appeng.api.stacks.AEItemKey;
import appeng.client.gui.me.common.FinishedJobToast;
import appeng.client.gui.me.common.PendingCraftingJobs;
import appeng.core.AEConfig;
import appeng.core.network.clientbound.CraftingJobStatusPacket.Status;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.world.item.Items;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 正式 CPU 完成后检查原生 toast；直接状态调用只补去重、开关和清理边界。 */
final class CraftingNotificationClient {
	static boolean verified;
	private static boolean prepared;
	private static UUID submitted;
	private static long observedAt;
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.meNotifications"); }
	static void prepare(Minecraft client) {
		if (prepared) return;
		require(!ModConfig.CLIENT.terminalPreferences.notifyCraftingFinished.get(), "Completion notifications should default off");
		AEConfig.instance().setNotifyForFinishedCraftingJobs(false); client.getToasts().clear();
		ModConfig.CLIENT.terminalPreferences.notifyCraftingFinished.set(true); ModConfig.CLIENT_SPEC.save(); prepared = true;
	}
	static void submitted() throws Exception {
		var field = PendingCraftingJobs.class.getDeclaredField("jobs"); field.setAccessible(true);
		var jobs = (Map<?, ?>) field.get(null); require(jobs.size() == 1, "Expected one native crafting job"); submitted = (UUID) jobs.keySet().iterator().next();
	}
	static void cancelled(Minecraft client) { require(client.getToasts().getToast(FinishedJobToast.class, Toast.NO_TOKEN) == null, "Cancellation generated a completion notification"); }
	static boolean completed(Minecraft client) throws Exception {
		if (verified) return true;
		if (observedAt == 0) observedAt = Util.getMillis();
		var toast = client.getToasts().getToast(FinishedJobToast.class, Toast.NO_TOKEN);
		if (toast == null) { require(Util.getMillis() - observedAt < 3000, "Real completed CPU did not notify"); return false; }
		if (Util.getMillis() - observedAt < 700) return false;
		Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results/me-completed-toast.png")); }
		var output = AEItemKey.of(Items.IRON_INGOT);
		client.getToasts().clear(); PendingCraftingJobs.jobStatus(submitted, output, 2, 0, Status.FINISHED);
		require(client.getToasts().getToast(FinishedJobToast.class, Toast.NO_TOKEN) == null, "Repeated completion notified again");
		ModConfig.CLIENT.terminalPreferences.notifyCraftingFinished.set(false);
		PendingCraftingJobs.jobStatus(UUID.randomUUID(), output, 2, 0, Status.FINISHED);
		require(client.getToasts().getToast(FinishedJobToast.class, Toast.NO_TOKEN) == null, "Disabled additional notification still displayed");
		ModConfig.CLIENT.terminalPreferences.notifyCraftingFinished.set(true); AEConfig.instance().setNotifyForFinishedCraftingJobs(true);
		var both = UUID.randomUUID(); PendingCraftingJobs.jobStatus(both, output, 2, 0, Status.FINISHED);
		require(client.getToasts().getToast(FinishedJobToast.class, Toast.NO_TOKEN) != null, "Both enabled settings suppressed notification");
		client.getToasts().clear(); PendingCraftingJobs.jobStatus(both, output, 2, 0, Status.FINISHED);
		require(client.getToasts().getToast(FinishedJobToast.class, Toast.NO_TOKEN) == null, "Both enabled settings duplicated notification");
		PendingCraftingJobs.clearPendingJobs(); PendingCraftingJobs.jobStatus(both, output, 2, 0, Status.FINISHED);
		require(client.getToasts().getToast(FinishedJobToast.class, Toast.NO_TOKEN) != null, "Native job-history cleanup retained completed IDs");
		client.getToasts().clear(); ModConfig.CLIENT_SPEC.save(); verified = true; return true;
	}
	private CraftingNotificationClient() { }
}
