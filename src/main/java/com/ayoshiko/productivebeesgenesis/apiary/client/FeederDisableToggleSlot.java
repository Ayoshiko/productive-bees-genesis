package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.network.ToggleFeederSlotDisabledPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.function.BooleanSupplier;

/**
 * 支持逐格禁用的喂食槽位元素
 * <br/>
 * 在 MEK {@link GuiVirtualSlot} 基础上叠加两件事：
 * <ul>
 *   <li><b>灰色遮罩</b>：通过 {@code overlayColor} 钩子在物品之上叠加半透明深灰，
 *       状态取自服务端同步的位掩码（{@code isSlotBlocked}），客户端不本地猜测</li>
 *   <li><b>切换手势</b>：仅当处于禁用编辑模式或按住 Alt 时，左键才被解释为"切换禁用"，
 *       其余情况原样交给父类 → 原版容器交互（左键拿取、右键分堆）完全保留</li>
 * </ul>
 * <p>
 * 设计原因（为何不直接用右键）：右键在原版容器里是"取半堆/放一个"，
 * 抢占它会破坏玩家已有肌肉记忆；改为"模式按钮 + Alt 快捷键"后，
 * 常规交互零改动，批量禁用也只需开一次模式连续点击。
 */
final class FeederDisableToggleSlot extends GuiVirtualSlot {

	/** 禁用格遮罩色（ARGB，半透明深灰，压暗物品但仍可辨识是什么） */
	private static final int DISABLED_OVERLAY_COLOR = 0xB0303030;

	/** 未禁用时的遮罩色（全透明 = 不可见，等价于无遮罩） */
	private static final int NO_OVERLAY_COLOR = 0x00000000;

	private final TileEntityMekApiary tile;

	/** 对应的容器虚拟槽位 — 用于判定该格是否有物品（空格子不发包） */
	private final VirtualInventoryContainerSlot containerSlot;

	/** 该格在喂食槽管理器中的索引（跨页构建时由窗口传入真实索引） */
	private final int slotIndex;

	/** 禁用编辑模式状态查询（窗口持有真值） */
	private final BooleanSupplier disableModeActive;

	FeederDisableToggleSlot(GuiWindow window, IGuiWrapper gui, int x, int y,
			VirtualInventoryContainerSlot containerSlot, TileEntityMekApiary tile, int slotIndex,
			BooleanSupplier disableModeActive) {
		super(window, SlotType.NORMAL, gui, x, y, containerSlot);
		this.tile = tile;
		this.containerSlot = containerSlot;
		this.slotIndex = slotIndex;
		this.disableModeActive = disableModeActive;
		overlayColor(this::resolveOverlayColor);
	}

	/** 遮罩色解析 — 每帧调用，仅一次数组读 + 位测试，无分配 */
	private int resolveOverlayColor() {
		return tile.getFeederSlotManager().isSlotBlocked(slotIndex)
				? DISABLED_OVERLAY_COLOR : NO_OVERLAY_COLOR;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && isToggleGesture() && isOverSlot(mouseX, mouseY)
				&& !containerSlot.getItem().isEmpty()) {
			PacketDistributor.sendToServer(new ToggleFeederSlotDisabledPayload(tile.getBlockPos(), slotIndex));
			playClickSound(BUTTON_CLICK_SOUND);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	/** 切换手势判定：禁用编辑模式已开启，或临时按住 Alt */
	private boolean isToggleGesture() {
		return disableModeActive.getAsBoolean() || Screen.hasAltDown();
	}

	/** 与父类 {@code GuiVirtualSlot.mouseClicked} 相同的命中判定，避免坐标语义分叉 */
	private boolean isOverSlot(double mouseX, double mouseY) {
		return mouseX >= getX() && mouseY >= getY() && mouseX < getRight() && mouseY < getBottom();
	}
}
