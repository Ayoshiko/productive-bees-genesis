package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** 扳手、等级安装器及普通拆机都必须先交还，避免从源快照产生第二份实物。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class ManagedMemberEvents {
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void interact(PlayerInteractEvent.RightClickBlock event) {
		if (MemberBinding.isolated(event.getLevel().getBlockEntity(event.getPos()))) {
			event.setCanceled(true); event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
		}
	}
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void broken(BlockEvent.BreakEvent event) {
		if (MemberBinding.isolated(event.getLevel().getBlockEntity(event.getPos()))) event.setCanceled(true);
	}
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void explosion(ExplosionEvent.Detonate event) {
		event.getAffectedBlocks().removeIf(pos -> MemberBinding.isolated(event.getLevel().getBlockEntity(pos)));
	}
	private ManagedMemberEvents() { }
}
