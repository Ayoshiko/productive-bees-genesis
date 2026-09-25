package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.common.item.BeeCage;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.security.IBlockSecurityUtils;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** 潜行右键直接交付蜜蜂或小食；客户端只拦截动画，资产由服务端修改。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ApiaryQuickInsertHandler {

	private ApiaryQuickInsertHandler() { }

	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		var player = event.getEntity();
		if (!player.isShiftKeyDown() || !isCandidate(event.getItemStack())) return;
		var level = event.getLevel();
		if (!(level.getBlockEntity(event.getPos()) instanceof TileEntityMekApiary tile)) return;
		// 两端消费本次交互，满槽时也不能落入物品 useOn，在世界放出蜜蜂。
		// NeoForge 的客户端预测仍会发送原版 UseItemOn 包；无需新增资产修改包。
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
		if (level.isClientSide || player.isSpectator() || !player.mayBuild()
				|| !level.mayInteract(player, event.getPos())
				|| !IBlockSecurityUtils.INSTANCE.canAccess(player, level, event.getPos(), tile)) return;
		ItemStack stack = event.getItemStack();
		if (ApiarySlotManager.isGeneTreat(stack)) {
			try {
				insertGeneTreat(tile.getGeneTreatSlot(), stack);
			} catch (RuntimeException e) {
				ProductiveBeesGenesis.LOGGER.error("Gene-treat quick insertion failed at {} in {}",
						tile.getBlockPos(), level.dimension().location(), e);
			} finally {
				tile.getGeneTreatRestock().observe(tile.getGeneTreatSlot().getStack());
				tile.setChanged();
				player.inventoryMenu.broadcastChanges();
			}
			return;
		} else {
			int index = findEmptySlot(tile.getBeeSlots(), tile.getSelectedBeeSlot());
			if (index < 0) return;
			if (stack.getItem() instanceof BeeCage) tile.releaseBeeAtSlot(index, stack);
			else tile.insertBeeFromSpawnEgg(index, stack, player);
		}
		player.inventoryMenu.broadcastChanges();
	}

	/** 监听器可能在入槽后抛异常；按服务端独占槽的实际增量扣除手持物品。 */
	static int insertGeneTreat(BasicInventorySlot slot, ItemStack held) {
		ItemStack before = slot.getStack().copy();
		RuntimeException failure = null;
		try {
			slot.insertItem(held.copy(), Action.EXECUTE, AutomationType.MANUAL);
		} catch (RuntimeException e) {
			failure = e;
		}
		ItemStack after = slot.getStack();
		int inserted = ItemStack.matches(before, after) ? 0
				: (before.isEmpty() || ItemStack.isSameItemSameComponents(before, held))
				&& ItemStack.isSameItemSameComponents(after, held)
				? after.getCount() - before.getCount() : -1;
		if (inserted < 0 || inserted > held.getCount()) {
			throw new IllegalStateException("Gene-treat slot changed unexpectedly during quick insertion", failure);
		}
		held.shrink(inserted);
		if (failure != null) throw failure;
		return inserted;
	}

	static boolean isCandidate(ItemStack stack) {
		if (ApiarySlotManager.isGeneTreat(stack) || BeeSpawnEggHelper.isResourceBeeSpawnEgg(stack)) return true;
		if (!(stack.getItem() instanceof BeeCage)) return false;
		var data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains("entity");
	}

	/** 与界面操作一致：选中的空槽优先，其余按顺序寻找。 */
	static int findEmptySlot(BeeSlot[] slots, int selected) {
		if (selected >= 0 && selected < slots.length && slots[selected].isEmpty()) return selected;
		for (int i = 0; i < slots.length; i++) if (slots[i].isEmpty()) return i;
		return -1;
	}
}
