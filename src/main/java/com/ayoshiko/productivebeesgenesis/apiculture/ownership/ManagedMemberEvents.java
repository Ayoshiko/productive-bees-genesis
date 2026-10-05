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
		var source = event.getLevel().getBlockEntity(event.getPos());
		if (com.ayoshiko.productivebeesgenesis.apiculture.core.WorldBeeInputRequest.intercept(event, source)) return;
		if (MemberBinding.isolated(source)) {
			boolean open = event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND && !event.getEntity().isShiftKeyDown();
			if (event.getLevel().isClientSide() && open) return;
			boolean opened = open && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
					&& com.ayoshiko.productivebeesgenesis.apiculture.core.MemberUpgradeMenuAccess.open(player, source);
			event.setCanceled(true); event.setCancellationResult(opened ? net.minecraft.world.InteractionResult.CONSUME : net.minecraft.world.InteractionResult.FAIL);
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
