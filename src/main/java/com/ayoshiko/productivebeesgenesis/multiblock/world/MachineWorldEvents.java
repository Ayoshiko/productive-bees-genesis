package com.ayoshiko.productivebeesgenesis.multiblock.world;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MachineWorldEvents {
	@SubscribeEvent public static void neighbors(BlockEvent.NeighborNotifyEvent event) {
		if (event.getLevel() instanceof ServerLevel level) MachineWorldService.changed(level, event.getPos());
	}
	@SubscribeEvent public static void loaded(ChunkEvent.Load event) { chunk(event, false); }
	@SubscribeEvent public static void unloaded(ChunkEvent.Unload event) { chunk(event, true); }
	private static void chunk(ChunkEvent event, boolean unload) {
		if (event.getLevel() instanceof ServerLevel level) {
			var pos = event.getChunk().getPos();
			if (level.getServer().isSameThread()) MachineWorldService.chunkChanged(level, pos, unload);
			else level.getServer().execute(() -> MachineWorldService.chunkChanged(level, pos, unload));
		}
	}
	@SubscribeEvent public static void levelUnloaded(LevelEvent.Unload event) { if (event.getLevel() instanceof ServerLevel level) MachineWorldService.unload(level); }
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { MachineWorldService.stop(event.getServer()); }
	private MachineWorldEvents() { }
}
