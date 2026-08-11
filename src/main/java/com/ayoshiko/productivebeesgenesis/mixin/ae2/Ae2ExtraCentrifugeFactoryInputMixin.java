package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.tile.base.TileEntityMekanism;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;

/**
	 * ME 工厂离心机 AE2 输入拉取接口注入 Mixin — 仅在 AE2 且 ME 加载时应用
	 * <br/>
	 * <b>原理</b>：通过 Mixin 接口注入，使 {@code TileEntityExtraMekCentrifugeFactory} 动态实现
	 * {@link IAe2InputHost} 接口，使 AE2 网络能向 ME 工厂离心机的输入槽拉取蜜脾。
	 * <p>
	 * <b>与现有 Mixin 的关系</b>：{@link Ae2ExtraCentrifugeFactoryMixin} 已为目标类注入
	 * {@code IAe2OutputHost}（输出推送契约），本 Mixin 注入 {@link IAe2InputHost}（输入拉取契约）。
	 * 两个 Mixin 注入不同接口，互不冲突；共享的 {@code IAe2OutputHostBase} default 方法
	 * 由目标类已有实现提供，Mixin 框架不会重复注入。
	 * <p>
	 * <b>方法实现</b>：
	 * <ul>
	 *   <li>{@code toggleAeItemInput} / {@code toggleAeInputNbtIgnore} — 委托给
	 *       {@link Ae2OutputStateHolder}（通过 {@code IAe2OutputHostBase} default 方法获取），
	 *       切换后调用 {@code markForSave()} 持久化 per-tile 状态</li>
	 *   <li>{@code getInputSlotsForPull} — 遍历所有进程的输入槽（每进程1个），
	 *       按 round-robin 顺序填充，与 {@code AbstractMekCentrifugeFactory} 实现一致</li>
	 * </ul>
	 * <p>
	 * <b>方法调用来源</b>：{@code processes()} 和 {@code inputSlot(int)} 来自
	 * {@code PbRecipeContext}（{@code IAe2InputHost} 继承链），目标类已通过
	 * {@code IFactoryPbDelegateAccess} 实现这两个方法，Mixin 调用时自动分派到目标类实现。
	 * {@code markForSave()} 来自 {@link TileEntityMekanism}（目标类继承链），
	 * Mixin 中通过转型访问（1.21.1 版 {@code TileEntityMekanism} 非泛型）。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 */
@Mixin(targets = "com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory", remap = false)
public abstract class Ae2ExtraCentrifugeFactoryInputMixin implements IAe2InputHost {
	@Unique
	private List<IInventorySlot> productivebeesgenesis$cachedInputSlots;

	/** 切换 per-tile AE2 输入拉取开关（取反当前状态） */
	@Override
	public void productivebeesgenesis$toggleAeItemInput() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder != null) {
			holder.toggleAeItemInputEnabled();
			((TileEntityMekanism) (Object) this).markForSave();
		}
	}

	/** 切换 per-tile AE2 输入 NBT 忽略开关（取反当前状态） */
	@Override
	public void productivebeesgenesis$toggleAeInputNbtIgnore() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder != null) {
			holder.toggleAeInputNbtIgnore();
			((TileEntityMekanism) (Object) this).markForSave();
		}
	}

	/**
	 * 获取所有进程的输入槽列表（工厂版多输入槽 round-robin 填充）
	 * <br/>
	 * 与 {@code AbstractMekCentrifugeFactory.productivebeesgenesis$getInputSlotsForPull()} 实现一致，
	 * 容量预设为 processes() 避免 ArrayList 扩容开销。
	 */
	@Override
	public List<IInventorySlot> productivebeesgenesis$getInputSlotsForPull() {
		if (productivebeesgenesis$cachedInputSlots != null) return productivebeesgenesis$cachedInputSlots;
		int processes = processes();
		List<IInventorySlot> slots = new ArrayList<>(processes);
		for (int i = 0; i < processes; i++) {
			IInventorySlot slot = inputSlot(i);
			if (slot != null) slots.add(slot);
		}
		return productivebeesgenesis$cachedInputSlots = slots;
	}
}
