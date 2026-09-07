package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.BeeSlot;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.network.ApiaryCageOperationPayload;
import com.ayoshiko.productivebeesgenesis.network.ApiaryFeedBeePayload;
import com.ayoshiko.productivebeesgenesis.network.ApiarySelectBeePayload;
import cy.jdkdigital.productivebees.common.item.HoneyTreat;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 蜜蜂槽点击派发（纯静态，无状态）
 * <br/>
 * 从 {@code GuiMekApiary} 拆分而来，职责（SRP）：把"点到蜜蜂格子之后做什么"这套判定
 * 与 GUI 渲染/布局职责分离。所有分支只做客户端判定并发包，服务端仍是唯一权威
 * （{@code ApiaryPayloadHandlers} 会重新校验容器、距离、索引与光标物品）。
 * <p>
 * 分支优先级（右键）：基因小食喂食 → 桶式蜂笼操作。前者优先是因为手持带基因的小食时，
 * 玩家意图必然是喂食而非取蜂。
 */
final class ApiaryBeeSlotInteraction {

	private ApiaryBeeSlotInteraction() {
	}

	/**
	 * 左键：选中 / 取消选中蜜蜂槽位
	 * <br/>
	 * 再次点击同一槽位即取消选择（发 -1）。空格子也允许选中，用于"先选空格再放蜂"。
	 *
	 * @return 恒为 true（左键落在蜜蜂格子上即视为已消费）
	 */
	static boolean handleSelect(TileEntityMekApiary tile, int slotIndex) {
		int newSelection = tile.getClientSelectedBeeSlot() == slotIndex ? -1 : slotIndex;
		PacketDistributor.sendToServer(new ApiarySelectBeePayload(tile.getBlockPos(), newSelection));
		return true;
	}

	/**
	 * 右键：基因小食喂食 → 桶式蜂笼操作
	 *
	 * @param tile      蜂箱方块实体
	 * @param cursor    玩家光标物品（客户端镜像，服务端会重新读取）
	 * @param slotIndex 目标蜜蜂槽位索引
	 * @return true 表示已发出请求并消费本次点击
	 */
	static boolean handleRightClick(TileEntityMekApiary tile, ItemStack cursor, int slotIndex) {
		if (slotIndex < 0 || slotIndex >= tile.getBeeSlotCount()) return false;
		BeeSlot beeSlot = tile.getBeeSlots()[slotIndex];
		if (handleHoneyTreatFeeding(tile, beeSlot, cursor, slotIndex)) return true;
		return handleCageOperation(tile, beeSlot, cursor, slotIndex);
	}

	/**
	 * 检查光标上的基因小食，并请求服务端喂食指定槽位内的蜜蜂
	 *
	 * @return 满足喂食条件并已发送请求时返回 {@code true}
	 */
	private static boolean handleHoneyTreatFeeding(TileEntityMekApiary tile, BeeSlot beeSlot,
			ItemStack cursor, int slotIndex) {
		if (beeSlot.isEmpty() || !(cursor.getItem() instanceof HoneyTreat) || !HoneyTreat.hasGene(cursor)) {
			return false;
		}
		PacketDistributor.sendToServer(new ApiaryFeedBeePayload(tile.getBlockPos(), slotIndex));
		return true;
	}

	/**
	 * 桶式蜂笼操作：检测光标蜂笼状态并发送操作包
	 * <br/>
	 * <ul>
	 *   <li>空蜂笼 + 有蜜蜂 → EXTRACT（取出）</li>
	 *   <li>含蜜蜂蜂笼 + 空格子 → INSERT（放入）</li>
	 * </ul>
	 * 其余组合（空笼+空格 / 满笼+有蜂）无意义，返回 false 让点击继续下传。
	 *
	 * @return true 如果发送了操作包
	 */
	private static boolean handleCageOperation(TileEntityMekApiary tile, BeeSlot beeSlot,
			ItemStack cursor, int slotIndex) {
		if (!isCageItem(cursor)) return false;
		boolean cursorHasBee = isFilledCage(cursor);
		ApiaryCageOperationPayload.OperationType op = null;
		if (!cursorHasBee && !beeSlot.isEmpty()) {
			op = ApiaryCageOperationPayload.OperationType.EXTRACT;
		} else if (cursorHasBee && beeSlot.isEmpty()) {
			op = ApiaryCageOperationPayload.OperationType.INSERT;
		}
		if (op == null) return false;
		PacketDistributor.sendToServer(new ApiaryCageOperationPayload(tile.getBlockPos(), slotIndex, op));
		return true;
	}

	/** 检查物品栈是否为蜂笼（普通蜂笼或坚固蜂笼） */
	private static boolean isCageItem(ItemStack stack) {
		return stack.is(ModItems.BEE_CAGE.get()) || stack.is(ModItems.STURDY_BEE_CAGE.get());
	}

	/** 检查蜂笼是否装有蜜蜂（CUSTOM_DATA 含 entity 字段） */
	private static boolean isFilledCage(ItemStack stack) {
		var data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return false;
		// copyTag() 必返回非 null CompoundTag，无需冗余 null 检查
		return data.copyTag().contains("entity");
	}
}
