package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.IContentsListener;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * 喂食槽位
 * <br/>
 * 在 Mekanism {@link BasicInventorySlot} 基础上附加「逐格禁用」标志：禁用后该格物品
 * 不再参与花朵匹配、转化与多花蜜蜂产物推断（见 {@link FeederSlotManager}）。
 * <p>
 * 标志直接挂在槽位对象上而非外部集合，原因：花朵匹配是 tick 热路径（60 槽 × N 蜂），
 * 遍历时已持有槽位引用，就地读 {@code isActive()} 无额外查表开销（迪米特法则 + 性能）。
 * <p>
 * 线程安全：{@code disabled} 由服务端 tick 线程读、主线程网络包写、客户端同步回调写，
 * 全部落在各自的主线程上；标记为 {@code volatile} 作为跨线程可见性冗余保障。
 */
class FeederInventorySlot extends BasicInventorySlot {

	/** 逐格禁用标志（true = 该格物品不参与蜜蜂产出） */
	private volatile boolean disabled;

	static FeederInventorySlot create(@Nullable IContentsListener listener) {
		return new FeederInventorySlot(listener);
	}

	private FeederInventorySlot(@Nullable IContentsListener listener) {
		super(
				ConstantPredicates.manualOnly(),
				ConstantPredicates.manualOnly(),
				(Predicate<ItemStack>) stack -> !PbUpgradeInventorySlot.isValidUpgradeItem(stack),
				listener, 0, 0
		);
	}

	/** 该格是否被玩家禁用 */
	boolean isDisabled() {
		return disabled;
	}

	/** 该格是否参与蜜蜂产出（非空且未禁用）— 花朵匹配热路径的统一判据 */
	boolean isActive() {
		return !disabled && !isEmpty();
	}

	/**
	 * 写入禁用标志
	 *
	 * @return true 表示状态发生变化（供调用方决定是否失效缓存 / 标记存档脏）
	 */
	boolean setDisabled(boolean value) {
		if (disabled == value) return false;
		disabled = value;
		return true;
	}

	/**
	 * 槽位变空时自动解除禁用
	 * <br/>
	 * 「格子没有物品时禁用无效」的语义落点：避免空格子残留隐形禁用状态，
	 * 玩家放入新物品后困惑于蜜蜂不工作。清标志后再通知监听器，
	 * 使 {@link FeederSlotManager} 的花朵缓存失效发生在标志已更新之后。
	 */
	@Override
	public void onContentsChanged() {
		if (isEmpty()) disabled = false;
		super.onContentsChanged();
	}

	@NotNull
	@Override
	public VirtualInventoryContainerSlot createContainerSlot() {
		return new VirtualInventoryContainerSlot(this, SelectedWindowData.UNSPECIFIED, getSlotOverlay(),
			this::setStackUnchecked);
	}
}
