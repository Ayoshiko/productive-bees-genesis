package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 候选只在一次服务器调用内存活；不占有物品，不修改拾取许可或实体堆叠。 */
final class WirelessMagnetTargets {
	private static final EntityTypeTest<Entity, ItemEntity> ITEMS = EntityTypeTest.forClass(ItemEntity.class);
	record Target(ItemEntity entity, ItemStack stack, Vec3 position, Vec3 motion) { }

	static List<Target> find(ServerPlayer player, Set<UUID> moved) {
		if (!loaded(player)) return List.of();
		var candidates = new ArrayList<ItemEntity>(16);
		// 先截断空间查询，再做过滤；拒绝项不能使返回列表扩展成全区域物品集合。
		player.serverLevel().getEntities(ITEMS, player.getBoundingBox().inflate(4), entity -> true, candidates, 16);
		candidates.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));
		var result = new ArrayList<Target>(4);
		var inventory = TerminalCraftingPlan.copy(player.getInventory().items);
		for (var entity : candidates) {
			if (moved.contains(entity.getUUID()) || !eligible(player, entity)) continue;
			var stack = entity.getItem().copy();
			var next = TerminalCraftingPlan.copy(inventory);
			if (!TerminalCraftingPlan.insert(next, stack).isEmpty()) continue;
			result.add(new Target(entity, stack, entity.position(), entity.getDeltaMovement()));
			inventory = next;
			if (result.size() == 4) break;
		}
		return result;
	}
	static boolean current(ServerPlayer player, Target target) {
		var entity = target.entity();
		return loaded(player) && eligible(player, entity) && unchanged(target);
	}
	static boolean unchanged(Target target) {
		var entity = target.entity();
		return entity.isAlive() && ItemStack.matches(target.stack(), entity.getItem())
				&& target.position().equals(entity.position()) && target.motion().equals(entity.getDeltaMovement());
	}
	static Vec3 velocity(ServerPlayer player, Target target) {
		return player.position().add(0, 0.5, 0).subtract(target.position()).normalize().scale(0.35);
	}
	private static boolean eligible(ServerPlayer player, ItemEntity entity) {
		double distance = entity.distanceToSqr(player);
		if (entity.getClass() != ItemEntity.class || entity.level() != player.level() || !entity.isAlive() || entity.noPhysics
				|| entity.hasPickUpDelay() || entity.getTarget() != null && !entity.getTarget().equals(player.getUUID())
				|| !Double.isFinite(distance) || distance <= 0.5625 || distance > 16
				|| entity.getTags().contains("productivebeesgenesis:no_magnet")
				|| entity.getPersistentData().getBoolean("PreventRemoteMovement")) return false;
		var stack = entity.getItem();
		if (stack.isEmpty() || stack.getItem() instanceof WirelessTerminalItem
				|| stack.getCount() > Math.min(64, stack.getMaxStackSize())
				|| !player.serverLevel().mayInteract(player, entity.blockPosition())) return false;
		var hit = player.serverLevel().clip(new ClipContext(entity.getBoundingBox().getCenter(),
				player.position().add(0, 0.5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS;
	}
	private static boolean loaded(ServerPlayer player) {
		int minX = Mth.floor(player.getX() - 5) >> 4, maxX = Mth.floor(player.getX() + 5) >> 4;
		int minZ = Mth.floor(player.getZ() - 5) >> 4, maxZ = Mth.floor(player.getZ() + 5) >> 4;
		for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
			if (player.serverLevel().getChunkSource().getChunkNow(x, z) == null) return false;
		return true;
	}
	private WirelessMagnetTargets() { }
}
