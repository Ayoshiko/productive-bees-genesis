package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Function;

/**
	 * PB 升级安装桥接器 — 将 PB 原版潜行右键安装机制委托给自定义升级系统
	 * <br/>
	 * PB 原版 {@code AbstractUpgradeItem.useOn} 要求 {@code IUpgradeableBlockEntity.getUpgradeHandler()}
	 * 返回 {@link InventoryHandlerHelper.UpgradeHandler} 实例，验证通过后调用 {@code insertItem} 安装。
	 * 本类继承 UpgradeHandler 使 instanceof 检查通过，重写 {@code insertItem} 拦截安装请求，
	 * 委托给自定义的安装回调（通常为 {@code tile.installPbUpgrade}），避免物品进入虚拟槽位
	 * （自定义系统使用 EnumMap 管理升级数量，不使用 PB 原版槽位）。
	 * <p>
	 * 设计原理：
	 * <ul>
	 *   <li>依赖倒置：通过 {@link Function} 回调解耦，不依赖具体的 handler 实现类</li>
	 *   <li>单一职责：仅负责桥接 PB 原版安装入口与自定义升级系统</li>
	 * </ul>
	 * <p>
	 * 线程安全：仅在主线程（玩家潜行右键交互）被调用，无并发。
	 */
public class PbUpgradeInstallHandler extends InventoryHandlerHelper.UpgradeHandler {

	/** 安装回调 — 接收升级类型，返回是否安装成功 */
	private final Function<PbUpgradeType, Boolean> installer;

	/**
	 * 构造 PB 升级安装桥接器
	 *
	 * @param tileEntity 所属方块实体（用于 CollectValidUpgradesEvent 触发）
	 * @param installer  安装回调（通常为 tile.installPbUpgrade 或 pbUpgradeHandler::installPbUpgrade）
	 */
	public PbUpgradeInstallHandler(BlockEntity tileEntity, Function<PbUpgradeType, Boolean> installer) {
		super(1, tileEntity, List.of());
		this.installer = installer;
	}

	@Override
	public int getSlots() {
		return 1;
	}

	@NotNull
	@Override
	public ItemStack getStackInSlot(int slot) {
		// 始终返回空，使 PB 认为槽位可用，触发 insertItem 调用
		return ItemStack.EMPTY;
	}

	@NotNull
	@Override
	public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
		PbUpgradeType type = PbUpgradeInventorySlot.getUpgradeType(stack);
		if (type == null) {
			return stack;
		}
		if (simulate) {
			// 模拟时不实际安装，返回空表示可插入
			return ItemStack.EMPTY;
		}
		// 实际安装：委托给自定义升级系统，成功返回空（物品被消耗），失败返回原栈
		return installer.apply(type) ? ItemStack.EMPTY : stack;
	}
}
