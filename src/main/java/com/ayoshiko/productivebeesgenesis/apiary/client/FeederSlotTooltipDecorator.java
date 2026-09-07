package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.IFeederSlotContainer;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 喂食槽 Tooltip 装饰器（纯静态，无状态）
 * <br/>
 * 职责（SRP）：当鼠标悬停的槽位是"已禁用的喂食槽"时，在原版物品 Tooltip 末尾追加禁用提示。
 * <p>
 * 为何挂在 {@code getTooltipFromContainerItem} 而不是 MEK 的 {@code GuiSlot.hover}：
 * MEK 的 widget tooltip 与原版悬停物品 tooltip 是两条独立通道，同时给出会叠加渲染两个浮窗；
 * 装饰原版通道则天然只有一个浮窗，且自动继承 JEI/物品说明等第三方追加内容。
 */
final class FeederSlotTooltipDecorator {

	private FeederSlotTooltipDecorator() {
	}

	/**
	 * 追加禁用提示
	 *
	 * @param tooltip 原版物品 Tooltip（不修改入参，命中时返回新列表）
	 * @param tile    蜂箱方块实体（读取服务端同步的禁用位掩码）
	 * @param menu    当前容器（需实现 {@link IFeederSlotContainer} 才能定位喂食槽）
	 * @param hovered 鼠标悬停的槽位，可能为 null
	 * @return 命中禁用喂食槽时返回追加提示后的新列表，否则原样返回
	 */
	static List<Component> decorate(List<Component> tooltip, TileEntityMekApiary tile,
			AbstractContainerMenu menu, @Nullable Slot hovered) {
		if (hovered == null || tile == null || !(menu instanceof IFeederSlotContainer container)) {
			return tooltip;
		}
		List<VirtualInventoryContainerSlot> feederSlots = container.getFeederSlots();
		if (feederSlots == null) {
			return tooltip;
		}
		// 线性查找：仅在悬停含物品槽位时执行一次，槽位数 ≤60，开销可忽略
		for (int i = 0; i < feederSlots.size(); i++) {
			if (feederSlots.get(i) != hovered) {
				continue;
			}
			if (!tile.getFeederSlotManager().isSlotBlocked(i)) {
				return tooltip;
			}
			List<Component> extended = new ArrayList<>(tooltip.size() + 2);
			extended.addAll(tooltip);
			extended.add(Component.translatable("gui.productivebeesgenesis.feeder_window.slot_disabled")
					.withStyle(ChatFormatting.RED));
			extended.add(Component.translatable("gui.productivebeesgenesis.feeder_window.slot_disabled.hint")
					.withStyle(ChatFormatting.DARK_GRAY));
			return extended;
		}
		return tooltip;
	}
}
